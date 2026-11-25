package com.lakegov.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 对象目录：查询、登记、按访问热度分层迁移。 */
@Service
public class CatalogService {

    private final JdbcTemplate jdbc;
    private final RetentionService retention;

    public CatalogService(JdbcTemplate jdbc, RetentionService retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    public List<Map<String, Object>> list(String category, String tier, Long projectId) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.id, o.object_uid AS uid, o.name, o.category, o.byte_size AS "byteSize",
                       o.checksum, o.checksum_status AS "checksumStatus", o.tier_code AS tier,
                       p.code AS "projectCode", o.owning_project_id AS "projectId",
                       o.created_at AS "createdAt", o.last_accessed_at AS "lastAccessedAt",
                       o.base_retention_deadline AS "baseDeadline",
                       o.retention_deadline AS "retentionDeadline",
                       o.litigation_hold AS "litigationHold", o.software_version AS "softwareVersion",
                       o.recipe, o.directory_available AS "directoryAvailable",
                       o.is_restore_package AS "restorePackage", o.package_target_uid AS "packageTarget",
                       o.withdrawn, o.note
                FROM data_object o JOIN project p ON p.id = o.owning_project_id
                WHERE 1=1
                """);
        List<Object> args = new java.util.ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" AND o.category = ?");
            args.add(category);
        }
        if (tier != null && !tier.isBlank()) {
            sql.append(" AND o.tier_code = ?");
            args.add(tier);
        }
        if (projectId != null) {
            sql.append(" AND o.owning_project_id = ?");
            args.add(projectId);
        }
        sql.append(" ORDER BY o.id");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> detail(String uid) {
        return jdbc.queryForMap("""
                SELECT o.*, p.code AS "projectCode"
                FROM data_object o JOIN project p ON p.id = o.owning_project_id
                WHERE o.object_uid = ?
                """, uid);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String uid = (String) body.get("uid");
        Long projectId = jdbc.queryForObject("SELECT id FROM project WHERE code = ?",
                Long.class, body.get("projectCode"));
        jdbc.update("""
                INSERT INTO data_object(object_uid, name, category, byte_size, checksum, tier_code,
                    owning_project_id, created_at, last_accessed_at, software_version, recipe, note)
                VALUES (?,?,?,?,?,?,?,now(),now(),?,?,?)
                """, uid, body.get("name"), body.get("category"),
                Long.valueOf(body.get("byteSize").toString()), body.get("checksum"),
                body.get("tier"), projectId,
                body.getOrDefault("softwareVersion", null),
                body.getOrDefault("recipe", null), body.getOrDefault("note", null));
        retention.recalculate();
        return Map.of("uid", uid, "created", true);
    }

    /**
     * 分层迁移：按对象访问热度给出建议或执行迁移。
     * 热度规则：90 天内访问→HOT；180 天→WARM；365 天→COLD；更久→ARCHIVE（受4h RTO约束的恢复目标保持在可满足层）。
     */
    public Map<String, Object> tierRecommendation() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT object_uid AS uid, name, category, tier_code AS "currentTier",
                       last_accessed_at AS "lastAccessedAt",
                       EXTRACT(DAY FROM (now() - last_accessed_at)) AS "idleDays",
                       is_restore_package AS "restorePackage"
                FROM data_object ORDER BY id
                """);
        List<Map<String, Object>> recs = new java.util.ArrayList<>();
        for (Map<String, Object> r : rows) {
            double idle = ((Number) r.get("idleDays")).doubleValue();
            String recommended;
            if (Boolean.TRUE.equals(r.get("restorePackage"))) recommended = "RESTORE_TIER";
            else if (idle <= 90) recommended = "HOT";
            else if (idle <= 180) recommended = "WARM";
            else if (idle <= 365) recommended = "COLD";
            else recommended = "ARCHIVE";
            recs.add(Map.of("uid", r.get("uid"), "category", r.get("category"),
                    "currentTier", r.get("currentTier"), "recommendedTier", recommended,
                    "idleDays", r.get("idleDays"),
                    "migrate", !recommended.equals(r.get("currentTier"))));
        }
        return Map.of("rule", "idle<=90d HOT; <=180d WARM; <=365d COLD; >365d ARCHIVE; 恢复包固定 RESTORE_TIER",
                "recommendations", recs);
    }

    @Transactional
    public Map<String, Object> migrate(String uid, String targetTier, String reason) {
        jdbc.update("UPDATE data_object SET tier_code = ? WHERE object_uid = ?", targetTier, uid);
        Long oid = jdbc.queryForObject("SELECT id FROM data_object WHERE object_uid = ?",
                Long.class, uid);
        jdbc.update("""
                INSERT INTO governance_event(event_type, object_id, payload, propagated, affected_uids)
                VALUES ('TIER_MIGRATION', ?, ?::jsonb, true, ?::jsonb)
                """, oid,
                "{\"uid\":\"" + uid + "\",\"targetTier\":\"" + targetTier
                        + "\",\"reason\":\"" + (reason == null ? "" : reason) + "\"}",
                "[" + "\"" + uid + "\"" + "]");
        return Map.of("uid", uid, "tier", targetTier, "migrated", true);
    }

    public List<Map<String, Object>> tiers() {
        return jdbc.queryForList("SELECT * FROM storage_tier ORDER BY gb_month_rate");
    }
}
