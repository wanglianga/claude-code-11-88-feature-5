package com.lakegov.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 保留策略服务：
 * 1. 基础截止日 = 创建时间 + 策略年限（类别 × 研究协议）；
 * 2. 传播规则一（源未到期，派生物不得先销毁）：对象有效截止日 ≥ 所有祖先输入截止日；
 * 3. 传播规则二（算法包/校准证据）：至少保留至最后一个下游依赖结果期限届满；
 * 4. 争议冻结：冻结对象及其下游全部不可销毁；
 * 5. 校验失败/规则变化/冻结均生成治理事件并传播到所有依赖结果。
 */
@Service
public class RetentionService {

    private final JdbcTemplate jdbc;
    private final LineageService lineage;
    private final ObjectMapper mapper = new ObjectMapper();

    public RetentionService(JdbcTemplate jdbc, LineageService lineage) {
        this.jdbc = jdbc;
        this.lineage = lineage;
    }

    /** 全量重算有效保留截止日 */
    @Transactional
    public List<Map<String, Object>> recalculate() {
        List<Map<String, Object>> objects = jdbc.queryForList(
                "SELECT id, object_uid AS uid, category, created_at AS \"createdAt\", " +
                "litigation_hold AS hold FROM data_object ORDER BY id");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> o : objects) {
            long id = ((Number) o.get("id")).longValue();
            String uid = (String) o.get("uid");
            String category = (String) o.get("category");
            boolean hold = Boolean.TRUE.equals(o.get("hold"));

            LocalDate base = baseDeadline(category, protocolOf(uid),
                    ((java.sql.Timestamp) o.get("createdAt")).toLocalDateTime().toLocalDate());
            jdbc.update("UPDATE data_object SET base_retention_deadline = ? WHERE id = ?",
                    java.sql.Date.valueOf(base), id);

            // 规则一：全部祖先的基础截止日；同时检查祖先链上的争议冻结
            LocalDate effective = base;
            boolean ancestorHold = false;
            for (Map<String, Object> anc : lineage.dependencyClosure(id)) {
                long ancId = ((Number) anc.get("id")).longValue();
                if (ancId == id) continue;
                LocalDate ancBase = baseOf(ancId);
                if (ancBase != null && ancBase.isAfter(effective)) effective = ancBase;
                if (Boolean.TRUE.equals(anc.get("litigationHold"))) ancestorHold = true;
            }
            // 规则二：算法包/校准取全部下游结果的基础截止日
            if ("SOFTWARE_PACKAGE".equals(category) || "CALIBRATION".equals(category)) {
                for (Map<String, Object> down : lineage.impactPropagation(id)) {
                    long downId = ((Number) down.get("id")).longValue();
                    if (downId == id) continue;
                    LocalDate downBase = baseOf(downId);
                    if (downBase != null && downBase.isAfter(effective)) effective = downBase;
                }
            }
            // 自身或任一祖先冻结 → 截止日置空（无限期保留，冻结沿血缘传播）
            java.sql.Date deadline = (hold || ancestorHold)
                    ? null : java.sql.Date.valueOf(effective);
            jdbc.update("UPDATE data_object SET retention_deadline = ? WHERE id = ?", deadline, id);
            result.add(Map.of("uid", uid, "baseDeadline", base.toString(),
                    "effectiveDeadline", hold ? "LITIGATION_HOLD" : effective.toString(),
                    "litigationHold", hold));
        }
        return result;
    }

    private String protocolOf(String uid) {
        return uid != null && uid.contains("-B") ? "PROTO-B" : "PROTO-A";
    }

    private LocalDate baseDeadline(String category, String protocol, LocalDate created) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT retention_years FROM retention_policy WHERE category = ? AND protocol_code = ?",
                category, protocol);
        if (rows.isEmpty()) {
            rows = jdbc.queryForList(
                    "SELECT retention_years FROM retention_policy WHERE category = ? ORDER BY protocol_code LIMIT 1",
                    category);
        }
        if (rows.isEmpty()) return created.plusYears(5);
        double years = ((Number) rows.get(0).get("retention_years")).doubleValue();
        long wholeYears = (long) years;
        long extraDays = Math.round((years - wholeYears) * 365);
        return created.plusYears(wholeYears).plusDays(extraDays);
    }

    private LocalDate baseOf(long objectId) {
        java.sql.Date d = jdbc.queryForObject(
                "SELECT base_retention_deadline FROM data_object WHERE id = ?",
                java.sql.Date.class, objectId);
        return d == null ? null : d.toLocalDate();
    }

    /** 保留概览：每个对象的截止日与销毁资格 */
    public List<Map<String, Object>> overview() {
        LocalDate today = LocalDate.now();
        return jdbc.queryForList("""
                SELECT o.object_uid AS uid, o.name, o.category, p.code AS "projectCode",
                       o.base_retention_deadline AS "baseDeadline",
                       o.retention_deadline AS "effectiveDeadline",
                       o.litigation_hold AS "litigationHold",
                       o.checksum_status AS "checksumStatus",
                       CASE
                         WHEN o.litigation_hold OR o.retention_deadline IS NULL THEN 'BLOCKED_HOLD'
                         WHEN o.retention_deadline < ? THEN 'ELIGIBLE'
                         ELSE 'RETAINED'
                       END AS disposition,
                       o.retention_deadline - ? AS "daysRemaining"
                FROM data_object o JOIN project p ON p.id = o.owning_project_id
                ORDER BY o.id
                """, java.sql.Date.valueOf(today), java.sql.Date.valueOf(today));
    }

    /** 争议冻结/解除，并向全部下游依赖结果传播 */
    @Transactional
    public Map<String, Object> setHold(String uid, boolean hold, String reason) {
        long id = requireId(uid);
        jdbc.update("UPDATE data_object SET litigation_hold = ? WHERE id = ?", hold, id);
        List<String> affected = lineage.impactPropagation(id).stream()
                .map(m -> (String) m.get("uid")).toList();
        recordEvent(hold ? "LITIGATION_HOLD" : "LITIGATION_HOLD_RELEASED", id,
                Map.of("uid", uid, "hold", hold, "reason", reason == null ? "" : reason), affected);
        recalculate();
        return Map.of("uid", uid, "litigationHold", hold, "propagatedTo", affected);
    }

    /** 标记校验失败并传播到所有依赖结果（派生物完整性声明同步失效） */
    @Transactional
    public Map<String, Object> markChecksumFailed(String uid, String reason) {
        long id = requireId(uid);
        jdbc.update("UPDATE data_object SET checksum_status = 'FAILED' WHERE id = ?", id);
        List<String> affected = lineage.impactPropagation(id).stream()
                .map(m -> (String) m.get("uid")).toList();
        recordEvent("CHECKSUM_FAILED", id,
                Map.of("uid", uid, "reason", reason == null ? "sha256 mismatch" : reason), affected);
        return Map.of("uid", uid, "checksumStatus", "FAILED", "propagatedTo", affected);
    }

    /** 恢复校验成功后回写 */
    @Transactional
    public void markChecksumOk(String uid) {
        jdbc.update("UPDATE data_object SET checksum_status = 'OK' WHERE id = ?", requireId(uid));
    }

    /** 修改保留策略年限，重算并记录规则变化事件（传播到受影响对象及下游） */
    @Transactional
    public Map<String, Object> updatePolicy(String category, String protocol, double years, String ruleText) {
        jdbc.update("""
                INSERT INTO retention_policy(category, protocol_code, retention_years, rule_text)
                VALUES (?,?,?,?)
                ON CONFLICT (category, protocol_code)
                DO UPDATE SET retention_years = EXCLUDED.retention_years, rule_text = EXCLUDED.rule_text
                """, category, protocol, years, ruleText);
        List<Long> objectIds = jdbc.queryForList(
                "SELECT id FROM data_object WHERE category = ?", Long.class, category);
        List<String> affected = new ArrayList<>();
        for (Long id : objectIds) {
            lineage.impactPropagation(id).forEach(m -> affected.add((String) m.get("uid")));
        }
        recordEvent("RETENTION_RULE_CHANGED", null,
                Map.of("category", category, "protocol", protocol, "retentionYears", years),
                affected.stream().distinct().toList());
        recalculate();
        return Map.of("category", category, "protocol", protocol, "retentionYears", years,
                "propagatedTo", affected.stream().distinct().toList());
    }

    /**
     * 销毁资格判定（含共享保护）：
     * 共享对象在某项目退出后仍被其他 ACTIVE 项目引用时，不得整体删除。
     */
    public Map<String, Object> deletionCheck(String uid) {
        long id = requireId(uid);
        Map<String, Object> o = jdbc.queryForMap(
                "SELECT o.litigation_hold AS hold, o.retention_deadline AS deadline, " +
                "o.owning_project_id AS \"ownerId\", p.status AS \"ownerStatus\" " +
                "FROM data_object o JOIN project p ON p.id = o.owning_project_id WHERE o.id = ?", id);
        LocalDate deadline = o.get("deadline") == null ? null
                : ((java.sql.Date) o.get("deadline")).toLocalDate();
        List<Map<String, Object>> otherRefs = jdbc.queryForList("""
                SELECT p.code AS "projectCode", p.status AS status, s.frozen_share AS share
                FROM object_share s JOIN project p ON p.id = s.project_id
                WHERE s.object_id = ? AND p.id <> ?
                """, id, o.get("ownerId"));
        boolean blockedByShare = otherRefs.stream().anyMatch(r -> "ACTIVE".equals(r.get("status")));
        String verdict;
        if (Boolean.TRUE.equals(o.get("hold"))) verdict = "BLOCKED_HOLD";
        else if (blockedByShare) verdict = "BLOCKED_SHARED_REFERENCE";
        else if (deadline != null && deadline.isAfter(LocalDate.now())) verdict = "BLOCKED_RETENTION";
        else verdict = "DELETABLE";
        return Map.of("uid", uid, "verdict", verdict,
                "ownerStatus", o.get("ownerStatus"), "otherReferences", otherRefs,
                "retentionExpired", deadline == null || !deadline.isAfter(LocalDate.now()));
    }

    public List<Map<String, Object>> events() {
        return jdbc.queryForList("""
                SELECT id, event_type AS "eventType", object_id AS "objectId", payload,
                       propagated, affected_uids AS "affectedUids", created_at AS "createdAt"
                FROM governance_event ORDER BY id DESC
                """);
    }

    private long requireId(String uid) {
        Long id = jdbc.queryForObject("SELECT id FROM data_object WHERE object_uid = ?",
                Long.class, uid);
        if (id == null) throw new IllegalArgumentException("对象不存在: " + uid);
        return id;
    }

    private void recordEvent(String type, Long objectId, Map<String, Object> payload, List<String> affected) {
        try {
            jdbc.update("""
                    INSERT INTO governance_event(event_type, object_id, payload, propagated, affected_uids)
                    VALUES (?,?,?::jsonb,true,?::jsonb)
                    """, type, objectId, mapper.writeValueAsString(payload),
                    mapper.writeValueAsString(affected));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(RetentionService.class)
                    .error("recordEvent failed type={} payload={} affected={}", type, payload, affected, e);
            throw new IllegalStateException("事件写入失败: " + e.getMessage(), e);
        }
    }
}
