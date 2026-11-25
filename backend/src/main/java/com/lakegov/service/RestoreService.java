package com.lakegov.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 恢复计划服务：
 * - 从指定论文结果/实验版本沿血缘反向求完整依赖闭包；
 * - 逐项核对目录可用性、sha256 摘要、算法软件包是否齐备；
 * - 缺件或预计恢复超过 4h RTO：任务启动前标记 UNSATISFIABLE 并列缺项；
 * - 可校验恢复包：清单与当前闭包比对，移除校准文件即判定不可复现；
 * - 执行恢复时再次校验并写回取回/恢复计算费用。
 */
@Service
public class RestoreService {

    private final JdbcTemplate jdbc;
    private final LineageService lineage;
    private final CostService costService;
    private final ObjectMapper mapper = new ObjectMapper();

    public RestoreService(JdbcTemplate jdbc, LineageService lineage, CostService costService) {
        this.jdbc = jdbc;
        this.lineage = lineage;
        this.costService = costService;
    }

    /**
     * 创建恢复计划（启动前可满足性检查）。
     * strategy: SCHEDULED_TIERING | RESTORE_PACKAGE
     */
    @Transactional
    public Map<String, Object> createPlan(String targetUid, String strategy, double frequencyPerYear) {
        Map<String, Object> target = jdbc.queryForMap(
                "SELECT id, object_uid AS uid, owning_project_id AS \"projectId\", name " +
                "FROM data_object WHERE object_uid = ?", targetUid);
        long targetId = ((Number) target.get("id")).longValue();
        long projectId = ((Number) target.get("projectId")).longValue();
        List<Map<String, Object>> closure = lineage.dependencyClosure(targetId);

        List<Map<String, Object>> missing = new ArrayList<>();
        boolean softwarePresent = false;
        BigDecimal perRunRetrieval = BigDecimal.ZERO;
        BigDecimal perRunCompute = BigDecimal.ZERO;
        double maxTierHours = 0d;
        List<Map<String, Object>> verification = new ArrayList<>();

        for (Map<String, Object> o : closure) {
            String uid = (String) o.get("uid");
            Map<String, Object> detail = jdbc.queryForMap("""
                    SELECT o.directory_available AS available, o.checksum_status AS "checksumStatus",
                           o.software_version AS "softwareVersion", o.category,
                           t.retrieval_hours AS "retrievalHours", t.rto_four_hour_capable AS "rtoCapable",
                           t.retrieval_gb_rate AS "retrievalRate", t.restore_compute_gb_rate AS "computeRate",
                           o.byte_size AS bytes, t.code AS tier
                    FROM data_object o JOIN storage_tier t ON t.code = o.tier_code
                    WHERE o.id = ?
                    """, ((Number) o.get("id")).longValue());
            if ("SOFTWARE_PACKAGE".equals(detail.get("category"))) softwarePresent = true;
            maxTierHours = Math.max(maxTierHours, ((BigDecimal) detail.get("retrievalHours")).doubleValue());

            List<String> problems = new ArrayList<>();
            if (!Boolean.TRUE.equals(detail.get("available"))) {
                problems.add("目录不可用/文件缺失");
            }
            if ("FAILED".equals(detail.get("checksumStatus"))) {
                problems.add("sha256 校验失败");
            }
            if (!Boolean.TRUE.equals(detail.get("rtoCapable"))) {
                problems.add("所在层不满足4h RTO（取回" + detail.get("retrievalHours") + "h）");
            }
            if (!problems.isEmpty()) {
                missing.add(Map.of("uid", uid, "category", detail.get("category"),
                        "reasons", problems));
            }

            BigDecimal gb = new BigDecimal(detail.get("bytes").toString())
                    .divide(CostService.BYTES_PER_GB, 6, RoundingMode.HALF_UP);
            BigDecimal rFee = gb.multiply((BigDecimal) detail.get("retrievalRate"))
                    .setScale(6, RoundingMode.HALF_UP);
            BigDecimal cFee = gb.multiply((BigDecimal) detail.get("computeRate"))
                    .setScale(6, RoundingMode.HALF_UP);
            perRunRetrieval = perRunRetrieval.add(rFee);
            perRunCompute = perRunCompute.add(cFee);
            verification.add(Map.of("uid", uid, "category", detail.get("category"),
                    "tier", detail.get("tier"), "available", detail.get("available"),
                    "checksumStatus", detail.get("checksumStatus"),
                    "softwareVersion", detail.get("softwareVersion") == null
                            ? "" : detail.get("softwareVersion"),
                    "retrievalFee", rFee, "computeFee", cFee,
                    "ok", problems.isEmpty()));
        }
        // 算法包缺失：即使哈希全通过也不可宣称复现
        if (!softwarePresent) {
            missing.add(Map.of("uid", "(software package)", "category", "SOFTWARE_PACKAGE",
                    "reasons", List.of("闭包缺少算法软件包；仅迁移结果文件不能宣称可复现")));
        }

        double estimatedHours;
        BigDecimal packageMonthly = null;
        if ("RESTORE_PACKAGE".equals(strategy)) {
            Map<String, Object> pkg = packageOf(targetUid);
            estimatedHours = ((BigDecimal) pkg.get("retrievalHours")).doubleValue();
            packageMonthly = new BigDecimal(pkg.get("bytes").toString())
                    .divide(CostService.BYTES_PER_GB, 6, RoundingMode.HALF_UP)
                    .multiply((BigDecimal) pkg.get("gbMonthRate")).setScale(6, RoundingMode.HALF_UP);
            // 包自身清单校验
            Map<String, Object> pkgValidation = validatePackage((String) pkg.get("uid"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pkgMissing = (List<Map<String, Object>>) pkgValidation.get("missingItems");
            missing.addAll(pkgMissing);
        } else {
            // 预约分层：72h 窗口内按 DAG 深度预取，并行度按层，瓶颈=最慢层取回时长
            estimatedHours = maxTierHours;
        }

        boolean rtoOk = estimatedHours <= 4d;
        if (!rtoOk) {
            missing.add(Map.of("uid", "(RTO)", "category", "TIMING",
                    "reasons", List.of("预计恢复 " + estimatedHours + "h 超过 4h RTO 目标")));
        }
        boolean satisfiable = missing.isEmpty();

        LocalDateTime scheduledAt = LocalDateTime.now().plusHours(72);
        String status = satisfiable ? "SATISFIABLE" : "UNSATISFIABLE";
        String note = satisfiable
                ? "提前72小时预约，按依赖DAG在窗口内预取完整闭包，瓶颈层取回 " + estimatedHours + "h ≤ 4h"
                : "任务启动前检查不可满足，阻断执行";

        jdbc.update("""
                INSERT INTO restore_plan(target_object_id, project_id, strategy, scheduled_at,
                    rto_hours, lead_hours, frequency_per_year, status, estimated_hours,
                    closure_count, missing_items, per_run_retrieval_fee, per_run_compute_fee,
                    package_monthly_fee, note)
                VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)
                """, targetId, projectId, strategy, java.sql.Timestamp.valueOf(scheduledAt),
                4, 72, frequencyPerYear, status, estimatedHours, closure.size(),
                writeJson(missing), perRunRetrieval, perRunCompute, packageMonthly, note);
        long planId = jdbc.queryForObject("SELECT currval('restore_plan_id_seq')", Long.class);

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("planId", planId);
        response.put("targetUid", targetUid);
        response.put("strategy", strategy);
        response.put("scheduledAt", scheduledAt.toString());
        response.put("rtoHours", 4);
        response.put("leadHours", 72);
        response.put("frequencyPerYear", frequencyPerYear);
        response.put("status", status);
        response.put("estimatedHours", estimatedHours);
        response.put("closureCount", closure.size());
        response.put("missingItems", missing);
        response.put("perRunRetrievalFee", perRunRetrieval);
        response.put("perRunComputeFee", perRunCompute);
        response.put("packageMonthlyFee", packageMonthly == null ? "N/A" : packageMonthly);
        response.put("verification", verification);
        response.put("note", note);
        return response;
    }

    /** 执行恢复计划：UNSATISFIABLE 直接阻断；SATISFIABLE 执行并记账 */
    @Transactional
    public Map<String, Object> executePlan(long planId) {
        Map<String, Object> plan = jdbc.queryForMap("SELECT * FROM restore_plan WHERE id = ?", planId);
        String status = (String) plan.get("status");
        long targetId = ((Number) plan.get("target_object_id")).longValue();
        long projectId = ((Number) plan.get("project_id")).longValue();
        if ("UNSATISFIABLE".equals(status) || "BLOCKED".equals(status)) {
            jdbc.update("INSERT INTO governance_event(event_type, object_id, project_id, payload, propagated, affected_uids) " +
                    "VALUES ('RESTORE_BLOCKED',?,?,?::jsonb,true,?::jsonb)",
                    targetId, projectId, writeJson(Map.of("planId", planId, "reason", "启动前检查不可满足")),
                    writeJson(lineage.closureUids(targetId)));
            throw new IllegalStateException("恢复计划 " + planId + " 不可满足，已阻断：" + plan.get("missing_items"));
        }
        // 执行时再次实时核对
        Map<String, Object> recheck = createPlan(
                jdbc.queryForObject("SELECT object_uid FROM data_object WHERE id = ?",
                        String.class, targetId),
                (String) plan.get("strategy"),
                ((Number) plan.get("frequency_per_year")).doubleValue());
        if (!"SATISFIABLE".equals(recheck.get("status"))) {
            jdbc.update("UPDATE restore_plan SET status = 'BLOCKED' WHERE id = ?", planId);
            throw new IllegalStateException("执行时复核失败，恢复阻断：" + recheck.get("missingItems"));
        }
        List<Map<String, Object>> closure = lineage.dependencyClosure(targetId);
        List<Map<String, Object>> report = new ArrayList<>();
        for (Map<String, Object> o : closure) {
            long oid = ((Number) o.get("id")).longValue();
            costService.recordRetrieval(oid, projectId, planId,
                    "恢复计划#" + planId + " 取回 " + o.get("uid"));
            report.add(Map.of("uid", o.get("uid"), "checksum", o.get("checksum"),
                    "checksumStatus", o.get("checksumStatus"), "restored", true));
        }
        jdbc.update("UPDATE restore_plan SET status = 'EXECUTED' WHERE id = ?", planId);
        return Map.of("planId", planId, "status", "EXECUTED",
                "restoredCount", closure.size(), "report", report,
                "completedWithinRto", true);
    }

    /** 校验恢复包：封存清单 vs 当前目录/摘要/软件版本 */
    @Transactional
    public Map<String, Object> validatePackage(String packageUid) {
        Map<String, Object> pkg = jdbc.queryForMap("""
                SELECT o.id, o.package_target_uid AS "targetUid", o.checksum_status AS "pkgChecksum"
                FROM data_object o WHERE o.object_uid = ?
                """, packageUid);
        String targetUid = (String) pkg.get("targetUid");
        Long targetId = jdbc.queryForObject("SELECT id FROM data_object WHERE object_uid = ?",
                Long.class, targetUid);
        List<Map<String, Object>> closure = lineage.dependencyClosure(targetId);
        List<Map<String, Object>> missing = new ArrayList<>();
        List<Map<String, Object>> checked = new ArrayList<>();
        boolean softwarePresent = false;
        for (Map<String, Object> o : closure) {
            Map<String, Object> live = jdbc.queryForMap("""
                    SELECT directory_available AS available, checksum_status AS "checksumStatus",
                           software_version AS sv, category
                    FROM data_object WHERE id = ?
                    """, ((Number) o.get("id")).longValue());
            if ("SOFTWARE_PACKAGE".equals(live.get("category"))) softwarePresent = true;
            List<String> reasons = new ArrayList<>();
            if (!Boolean.TRUE.equals(live.get("available"))) reasons.add("文件已从包/目录中移除");
            if ("FAILED".equals(live.get("checksumStatus"))) reasons.add("sha256 校验失败");
            if (!reasons.isEmpty()) {
                missing.add(Map.of("uid", o.get("uid"), "category", live.get("category"),
                        "reasons", reasons));
            }
            checked.add(Map.of("uid", o.get("uid"), "category", live.get("category"),
                    "available", live.get("available"),
                    "checksumStatus", live.get("checksumStatus"),
                    "softwareVersion", live.get("sv") == null ? "" : live.get("sv")));
        }
        if (!softwarePresent) {
            missing.add(Map.of("uid", "(software package)", "category", "SOFTWARE_PACKAGE",
                    "reasons", List.of("缺少算法软件包，不可宣称复现")));
        }
        boolean valid = missing.isEmpty();
        jdbc.update("UPDATE restore_package SET validated = ?, validation_detail = ?::jsonb " +
                "WHERE package_object_id = ?", valid,
                writeJson(Map.of("valid", valid, "missingItems", missing, "checked", checked)),
                pkg.get("id"));
        return Map.of("packageUid", packageUid, "targetUid", targetUid, "valid", valid,
                "missingItems", missing, "checked", checked);
    }

    /** 模拟缺件（如故意移除校准文件），用于阻断验证 */
    @Transactional
    public Map<String, Object> setAvailability(String uid, boolean available) {
        jdbc.update("UPDATE data_object SET directory_available = ? WHERE object_uid = ?",
                available, uid);
        return Map.of("uid", uid, "directoryAvailable", available);
    }

    public List<Map<String, Object>> listPlans() {
        return jdbc.queryForList("""
                SELECT rp.id AS "planId", o.object_uid AS "targetUid", p.code AS "projectCode",
                       rp.strategy, rp.status, rp.estimated_hours AS "estimatedHours",
                       rp.rto_hours AS "rtoHours", rp.lead_hours AS "leadHours",
                       rp.frequency_per_year AS "frequencyPerYear",
                       rp.closure_count AS "closureCount", rp.missing_items AS "missingItems",
                       rp.per_run_retrieval_fee AS "perRunRetrievalFee",
                       rp.per_run_compute_fee AS "perRunComputeFee",
                       rp.package_monthly_fee AS "packageMonthlyFee",
                       rp.scheduled_at AS "scheduledAt", rp.note
                FROM restore_plan rp
                JOIN data_object o ON o.id = rp.target_object_id
                JOIN project p ON p.id = rp.project_id
                ORDER BY rp.id DESC
                """);
    }

    private Map<String, Object> packageOf(String targetUid) {
        return jdbc.queryForMap("""
                SELECT o.object_uid AS uid, o.byte_size AS bytes,
                       t.gb_month_rate AS "gbMonthRate", t.retrieval_hours AS "retrievalHours",
                       t.code AS tier
                FROM data_object o JOIN storage_tier t ON t.code = o.tier_code
                WHERE o.package_target_uid = ?
                """, targetUid);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 按 DAG 深度排序的预取日程（72h 窗口） */
    public List<Map<String, Object>> prefetchSchedule(String targetUid) {
        Long targetId = jdbc.queryForObject("SELECT id FROM data_object WHERE object_uid = ?",
                Long.class, targetUid);
        List<Map<String, Object>> closure = lineage.dependencyClosure(targetId);
        Map<Long, Double> hours = new HashMap<>();
        jdbc.query("SELECT o.id, t.retrieval_hours FROM data_object o " +
                "JOIN storage_tier t ON t.code = o.tier_code", rs -> {
            hours.put(rs.getLong(1), rs.getDouble(2));
        });
        double cumulative = 0d;
        List<Map<String, Object>> schedule = new ArrayList<>();
        for (Map<String, Object> o : closure) {
            long oid = ((Number) o.get("id")).longValue();
            int depth = ((Number) o.get("depth")).intValue();
            double h = hours.getOrDefault(oid, 0d);
            schedule.add(Map.of("uid", o.get("uid"), "depth", depth,
                    "tierRetrievalHours", h, "windowStartHour", cumulative,
                    "windowEndHour", cumulative + h, "within72hLead", cumulative + h <= 72d));
            cumulative += h;
        }
        return schedule;
    }
}
