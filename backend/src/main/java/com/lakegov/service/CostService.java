package com.lakegov.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成本账服务：
 * - 日账：每日对象字节时长（byte×24h）× 所在层 GB·月费率 / 30；
 * - 月账单：存储费 + 取回费 + 恢复计算费；
 * - 独占对象归所属项目；共享对象按 object_share 中冻结的引用/使用份额分摊；
 * - 分摊总额必须等于月账单（allocation_check）；
 * - 两路线盈亏平衡：按需预取 vs 可校验恢复包。
 * 计量口径：1 GB = 10^9 字节，月 = 30 天。
 */
@Service
public class CostService {

    public static final BigDecimal BYTES_PER_GB = new BigDecimal("1000000000");
    public static final int DAYS_PER_MONTH = 30;

    private final JdbcTemplate jdbc;
    private final LineageService lineage;

    public CostService(JdbcTemplate jdbc, LineageService lineage) {
        this.jdbc = jdbc;
        this.lineage = lineage;
    }

    private Map<String, Object> tier(String code) {
        return jdbc.queryForMap("SELECT * FROM storage_tier WHERE code = ?", code);
    }

    private BigDecimal rate(Map<String, Object> tier, String key) {
        return ((BigDecimal) tier.get(key));
    }

    /** 生成指定月份的日账与月账单（幂等重算） */
    @Transactional
    public Map<String, Object> generateMonthlyBilling(String billingMonth) {
        LocalDate first = LocalDate.parse(billingMonth + "-01");
        int days = first.lengthOfMonth();
        jdbc.update("DELETE FROM cost_daily WHERE day >= ? AND day < ?",
                java.sql.Date.valueOf(first),
                java.sql.Date.valueOf(first.plusMonths(1)));
        jdbc.update("DELETE FROM monthly_bill WHERE billing_month = ?", billingMonth);

        List<Map<String, Object>> objects = jdbc.queryForList(
                "SELECT id, object_uid AS uid, byte_size AS bytes, tier_code AS tier, " +
                "owning_project_id AS \"ownerId\" FROM data_object ORDER BY id");

        // 项目 → 费用累计；共享对象按冻结份额拆分
        Map<Long, Map<String, BigDecimal>> billAgg = new LinkedHashMap<>();
        List<Map<String, Object>> allocationDetails = new ArrayList<>();

        for (Map<String, Object> o : objects) {
            long oid = ((Number) o.get("id")).longValue();
            long ownerId = ((Number) o.get("ownerId")).longValue();
            BigDecimal bytes = new BigDecimal(o.get("bytes").toString());
            BigDecimal gb = bytes.divide(BYTES_PER_GB, 6, RoundingMode.HALF_UP);
            Map<String, Object> t = tier((String) o.get("tier"));
            BigDecimal gbMonthRate = rate(t, "gb_month_rate");
            BigDecimal monthlyStorage = gb.multiply(gbMonthRate).setScale(6, RoundingMode.HALF_UP);
            BigDecimal dailyStorage = monthlyStorage.divide(BigDecimal.valueOf(DAYS_PER_MONTH),
                    6, RoundingMode.HALF_UP);

            for (int d = 1; d <= days; d++) {
                LocalDate day = first.withDayOfMonth(d);
                BigDecimal byteHours = bytes.multiply(BigDecimal.valueOf(24));
                jdbc.update("""
                        INSERT INTO cost_daily(object_id, day, byte_hours, tier_code, gb_month_rate, storage_charge)
                        VALUES (?,?,?,?,?,?)
                        ON CONFLICT (object_id, day) DO UPDATE SET
                          byte_hours=EXCLUDED.byte_hours, tier_code=EXCLUDED.tier_code,
                          gb_month_rate=EXCLUDED.gb_month_rate, storage_charge=EXCLUDED.storage_charge
                        """, oid, java.sql.Date.valueOf(day), byteHours, t.get("code"),
                        gbMonthRate, dailyStorage);
            }

            // 分摊：有冻结份额的共享对象按份额；否则独占归所属项目
            List<Map<String, Object>> shares = jdbc.queryForList(
                    "SELECT s.project_id AS pid, s.frozen_share AS share, s.share_basis AS basis " +
                    "FROM object_share s WHERE s.object_id = ?", oid);
            BigDecimal shareSum = shares.stream()
                    .map(s -> (BigDecimal) s.get("share"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<Map<String, Object>> objectAlloc = new ArrayList<>();
            if (shares.isEmpty()) {
                addStorage(billAgg, ownerId, monthlyStorage);
                objectAlloc.add(Map.of("projectId", ownerId, "share", BigDecimal.ONE,
                        "storageFee", monthlyStorage, "basis", "EXCLUSIVE"));
            } else {
                if (shareSum.compareTo(BigDecimal.ONE) != 0) {
                    throw new IllegalStateException(
                            "共享对象 " + o.get("uid") + " 冻结份额之和=" + shareSum + "，必须等于1");
                }
                for (Map<String, Object> s : shares) {
                    long pid = ((Number) s.get("pid")).longValue();
                    BigDecimal part = monthlyStorage.multiply((BigDecimal) s.get("share"))
                            .setScale(6, RoundingMode.HALF_UP);
                    addStorage(billAgg, pid, part);
                    objectAlloc.add(Map.of("projectId", pid, "share", s.get("share"),
                            "storageFee", part, "basis", "SHARED_" + s.get("basis")));
                }
            }
            allocationDetails.add(Map.of("uid", o.get("uid"), "tier", t.get("code"),
                    "gb", gb, "monthlyStorageFee", monthlyStorage, "allocations", objectAlloc));
        }

        // 取回费 / 恢复计算费按事件归属项目汇总
        List<Map<String, Object>> retrievalAgg = jdbc.queryForList("""
                SELECT project_id AS pid,
                       COALESCE(SUM(retrieval_fee),0) AS "retrievalFee",
                       COALESCE(SUM(restore_compute_fee),0) AS "computeFee"
                FROM retrieval_event
                WHERE to_char(event_time,'YYYY-MM') = ?
                GROUP BY project_id
                """, billingMonth);
        for (Map<String, Object> r : retrievalAgg) {
            long pid = ((Number) r.get("pid")).longValue();
            billAgg.computeIfAbsent(pid, k -> emptyAgg());
            billAgg.get(pid).put("retrieval", ((BigDecimal) r.get("retrievalFee")));
            billAgg.get(pid).put("compute", ((BigDecimal) r.get("computeFee")));
        }

        // 写月账单并做总额核对
        BigDecimal grandTotal = BigDecimal.ZERO;
        BigDecimal grandAlloc = BigDecimal.ZERO;
        List<Map<String, Object>> bills = new ArrayList<>();
        for (Map.Entry<Long, Map<String, BigDecimal>> e : billAgg.entrySet()) {
            long pid = e.getKey();
            Map<String, BigDecimal> agg = e.getValue();
            BigDecimal storage = agg.get("storage");
            BigDecimal retrieval = agg.get("retrieval");
            BigDecimal compute = agg.get("compute");
            BigDecimal total = storage.add(retrieval).add(compute).setScale(6, RoundingMode.HALF_UP);
            String projectCode = jdbc.queryForObject("SELECT code FROM project WHERE id = ?",
                    String.class, pid);
            Map<String, Object> detail = Map.of(
                    "projectCode", projectCode, "billingMonth", billingMonth,
                    "storageFee", storage, "retrievalFee", retrieval,
                    "restoreComputeFee", compute, "totalFee", total);
            // 核对：该项目分摊明细之和
            BigDecimal allocSum = allocationDetails.stream()
                    .flatMap(d -> ((List<Map<String, Object>>) d.get("allocations")).stream())
                    .filter(a -> ((Number) a.get("projectId")).longValue() == pid)
                    .map(a -> (BigDecimal) a.get("storageFee"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .add(retrieval).add(compute);
            boolean check = allocSubtract(allocSum, total) == 0;
            jdbc.update("""
                    INSERT INTO monthly_bill(project_id, billing_month, storage_fee, retrieval_fee,
                        restore_compute_fee, total_fee, allocation_json, allocation_check)
                    VALUES (?,?,?,?,?,?,?::jsonb,?)
                    """, pid, billingMonth, storage, retrieval, compute, total,
                    writeJson(detail), check);
            grandTotal = grandTotal.add(total);
            grandAlloc = grandAlloc.add(allocSum);
            bills.add(detail);
        }
        boolean globalCheck = allocSubtract(grandAlloc, grandTotal) == 0;
        return Map.of("billingMonth", billingMonth, "days", days,
                "allocationCheck", globalCheck, "bills", bills,
                "objectAllocations", allocationDetails,
                "grandTotal", grandTotal.setScale(6, RoundingMode.HALF_UP));
    }

    private int allocSubtract(BigDecimal a, BigDecimal b) {
        return a.setScale(4, RoundingMode.HALF_UP)
                .compareTo(b.setScale(4, RoundingMode.HALF_UP));
    }

    private Map<String, BigDecimal> emptyAgg() {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("storage", BigDecimal.ZERO);
        m.put("retrieval", BigDecimal.ZERO);
        m.put("compute", BigDecimal.ZERO);
        return m;
    }

    private void addStorage(Map<Long, Map<String, BigDecimal>> agg, long pid, BigDecimal fee) {
        agg.computeIfAbsent(pid, k -> emptyAgg())
                .merge("storage", fee, BigDecimal::add);
    }

    /** 历史月账单查询 */
    public List<Map<String, Object>> monthlyBills(String billingMonth) {
        return jdbc.queryForList("""
                SELECT p.code AS "projectCode", b.billing_month AS "billingMonth",
                       b.storage_fee AS "storageFee", b.retrieval_fee AS "retrievalFee",
                       b.restore_compute_fee AS "restoreComputeFee", b.total_fee AS "totalFee",
                       b.allocation_check AS "allocationCheck", b.allocation_json AS allocation
                FROM monthly_bill b JOIN project p ON p.id = b.project_id
                WHERE ? IS NULL OR b.billing_month = ?
                ORDER BY b.billing_month DESC, p.code
                """, billingMonth, billingMonth);
    }

    /**
     * 路线对比：对同一恢复目标，比较
     * A. SCHEDULED_TIERING（按热度分层 + 预约后提前72h按DAG取回闭包）
     * B. RESTORE_PACKAGE（可校验恢复包常驻4h RTO层）
     * 返回不同年频次下的年费与盈亏平衡频次。
     */
    public Map<String, Object> strategyComparison(String targetUid, List<Double> frequencies) {
        Long targetId = jdbc.queryForObject(
                "SELECT id FROM data_object WHERE object_uid = ?", Long.class, targetUid);
        List<Map<String, Object>> closure = lineage.dependencyClosure(targetId);

        BigDecimal perRunRetrieval = BigDecimal.ZERO;
        BigDecimal perRunCompute = BigDecimal.ZERO;
        List<Map<String, Object>> perObject = new ArrayList<>();
        for (Map<String, Object> o : closure) {
            BigDecimal gb = new BigDecimal(o.get("byteSize").toString())
                    .divide(BYTES_PER_GB, 6, RoundingMode.HALF_UP);
            Map<String, Object> t = tier((String) o.get("tier"));
            BigDecimal rFee = gb.multiply(rate(t, "retrieval_gb_rate")).setScale(6, RoundingMode.HALF_UP);
            BigDecimal cFee = gb.multiply(rate(t, "restore_compute_gb_rate")).setScale(6, RoundingMode.HALF_UP);
            perRunRetrieval = perRunRetrieval.add(rFee);
            perRunCompute = perRunCompute.add(cFee);
            perObject.add(Map.of("uid", o.get("uid"), "tier", t.get("code"), "gb", gb,
                    "retrievalFee", rFee, "computeFee", cFee));
        }
        BigDecimal perRunTotal = perRunRetrieval.add(perRunCompute);

        // 恢复包路线
        Map<String, Object> pkgRow = jdbc.queryForMap("""
                SELECT o.id, o.byte_size AS bytes, t.*
                FROM data_object o JOIN storage_tier t ON t.code = o.tier_code
                WHERE o.package_target_uid = ?
                LIMIT 1
                """, targetUid);
        BigDecimal pkgGb = new BigDecimal(pkgRow.get("bytes").toString())
                .divide(BYTES_PER_GB, 6, RoundingMode.HALF_UP);
        BigDecimal pkgMonthly = pkgGb.multiply(rate(pkgRow, "gb_month_rate")).setScale(6, RoundingMode.HALF_UP);
        BigDecimal pkgPerRun = pkgGb.multiply(
                rate(pkgRow, "retrieval_gb_rate").add(rate(pkgRow, "restore_compute_gb_rate")))
                .setScale(6, RoundingMode.HALF_UP);

        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (Double f : frequencies) {
            BigDecimal freq = BigDecimal.valueOf(f);
            BigDecimal scheduledAnnual = perRunTotal.multiply(freq).setScale(2, RoundingMode.HALF_UP);
            BigDecimal packageAnnual = pkgMonthly.multiply(BigDecimal.valueOf(12))
                    .add(pkgPerRun.multiply(freq)).setScale(2, RoundingMode.HALF_UP);
            scenarios.add(Map.of(
                    "frequencyPerYear", f,
                    "scheduledAnnualFee", scheduledAnnual,
                    "packageAnnualFee", packageAnnual,
                    "cheaperStrategy",
                    scheduledAnnual.compareTo(packageAnnual) <= 0 ? "SCHEDULED_TIERING" : "RESTORE_PACKAGE",
                    "saving", scheduledAnnual.subtract(packageAnnual).abs()
                            .setScale(2, RoundingMode.HALF_UP)));
        }
        // 盈亏平衡频次 f*：perRunTotal*f = 12*pkgMonthly + pkgPerRun*f
        BigDecimal denominator = perRunTotal.subtract(pkgPerRun);
        BigDecimal breakeven = denominator.compareTo(BigDecimal.ZERO) <= 0 ? null
                : pkgMonthly.multiply(BigDecimal.valueOf(12))
                        .divide(denominator, 2, RoundingMode.HALF_UP);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("targetUid", targetUid);
        response.put("rateVersion", pkgRow.get("rate_version"));
        response.put("closureSize", closure.size());
        response.put("perRunRetrievalFee", perRunRetrieval.setScale(6, RoundingMode.HALF_UP));
        response.put("perRunComputeFee", perRunCompute.setScale(6, RoundingMode.HALF_UP));
        response.put("perRunTotalFee", perRunTotal.setScale(6, RoundingMode.HALF_UP));
        response.put("packageGb", pkgGb);
        response.put("packageMonthlyFee", pkgMonthly);
        response.put("packagePerRunFee", pkgPerRun);
        response.put("breakevenFrequencyPerYear", breakeven == null ? "N/A" : breakeven);
        response.put("scenarios", scenarios);
        response.put("perObjectFees", perObject);
        return response;
    }

    /** 记录一次取回/恢复计算事件（执行恢复计划时调用） */
    @Transactional
    public void recordRetrieval(long objectId, long projectId, Long planId, String reason) {
        Map<String, Object> o = jdbc.queryForMap(
                "SELECT byte_size AS bytes, tier_code AS tier FROM data_object WHERE id = ?", objectId);
        Map<String, Object> t = tier((String) o.get("tier"));
        BigDecimal gb = new BigDecimal(o.get("bytes").toString())
                .divide(BYTES_PER_GB, 6, RoundingMode.HALF_UP);
        BigDecimal rFee = gb.multiply(rate(t, "retrieval_gb_rate")).setScale(6, RoundingMode.HALF_UP);
        BigDecimal cFee = gb.multiply(rate(t, "restore_compute_gb_rate")).setScale(6, RoundingMode.HALF_UP);
        jdbc.update("""
                INSERT INTO retrieval_event(object_id, project_id, plan_id, bytes, tier_code,
                    retrieval_fee, restore_compute_fee, reason)
                VALUES (?,?,?,?,?,?,?,?)
                """, objectId, projectId, planId, o.get("bytes"), t.get("code"), rFee, cFee, reason);
    }

    public List<Map<String, Object>> retrievalEvents() {
        return jdbc.queryForList("""
                SELECT e.id, o.object_uid AS uid, p.code AS "projectCode", e.bytes,
                       e.tier_code AS tier, e.retrieval_fee AS "retrievalFee",
                       e.restore_compute_fee AS "computeFee", e.reason, e.event_time AS "eventTime"
                FROM retrieval_event e
                JOIN data_object o ON o.id = e.object_id
                JOIN project p ON p.id = e.project_id
                ORDER BY e.id DESC
                """);
    }

    private String writeJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
