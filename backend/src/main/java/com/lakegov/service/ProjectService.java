package com.lakegov.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 项目服务：项目审阅视图、项目退出与共享对象保护。
 * 规则：项目退出后，其独占对象按保留规则处置；仍被其他 ACTIVE 项目引用的共享对象不得整体删除。
 */
@Service
public class ProjectService {

    private final JdbcTemplate jdbc;
    private final LineageService lineage;
    private final RetentionService retention;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProjectService(JdbcTemplate jdbc, LineageService lineage, RetentionService retention) {
        this.jdbc = jdbc;
        this.lineage = lineage;
        this.retention = retention;
    }

    public List<Map<String, Object>> listProjects() {
        return jdbc.queryForList("""
                SELECT p.id, p.code, p.name, p.status, p.withdrawn_at AS "withdrawnAt",
                  (SELECT count(*) FROM data_object o WHERE o.owning_project_id = p.id) AS "objectCount",
                  (SELECT COALESCE(sum(o.byte_size),0) FROM data_object o
                     WHERE o.owning_project_id = p.id) AS "totalBytes"
                FROM project p ORDER BY p.code
                """);
    }

    /** 项目审阅：对象、共享引用、依赖结果、保留处置与事件 */
    public Map<String, Object> review(String projectCode) {
        Long pid = jdbc.queryForObject("SELECT id FROM project WHERE code = ?",
                Long.class, projectCode);
        List<Map<String, Object>> objects = jdbc.queryForList("""
                SELECT o.object_uid AS uid, o.name, o.category, o.byte_size AS "byteSize",
                       o.tier_code AS tier, o.checksum_status AS "checksumStatus",
                       o.retention_deadline AS "retentionDeadline", o.litigation_hold AS "litigationHold",
                       o.directory_available AS "directoryAvailable"
                FROM data_object o WHERE o.owning_project_id = ? ORDER BY o.id
                """, pid);
        List<Map<String, Object>> sharedOut = jdbc.queryForList("""
                SELECT o.object_uid AS uid, o.name, pr.code AS "referencedByProject",
                       s.frozen_share AS "frozenShare", s.share_basis AS "shareBasis"
                FROM object_share s
                JOIN data_object o ON o.id = s.object_id
                JOIN project pr ON pr.id = s.project_id
                WHERE o.owning_project_id = ? AND pr.id <> ?
                """, pid, pid);
        List<Map<String, Object>> sharedIn = jdbc.queryForList("""
                SELECT o.object_uid AS uid, o.name, po.code AS "ownedByProject",
                       s.frozen_share AS "frozenShare", s.share_basis AS "shareBasis"
                FROM object_share s
                JOIN data_object o ON o.id = s.object_id
                JOIN project po ON po.id = o.owning_project_id
                WHERE s.project_id = ? AND po.id <> ?
                """, pid, pid);
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type AS "eventType", payload, affected_uids AS "affectedUids",
                       created_at AS "createdAt"
                FROM governance_event
                WHERE project_id = ? OR payload->>'uid' IN (
                    SELECT object_uid FROM data_object WHERE owning_project_id = ?)
                ORDER BY id DESC
                """, pid, pid);
        return Map.of("projectCode", projectCode,
                "objects", objects, "sharedOutReferences", sharedOut,
                "sharedInReferences", sharedIn, "governanceEvents", events);
    }

    /**
     * 项目退出：
     * - 标记项目 WITHDRAWN；
     * - 独占对象标记 withdrawn（仍受保留期约束，不立即删除）；
     * - 共享对象若仍被其他 ACTIVE 项目引用，禁止整体删除并显式列出。
     */
    @Transactional
    public Map<String, Object> withdraw(String projectCode, String reason) {
        Long pid = jdbc.queryForObject("SELECT id FROM project WHERE code = ?",
                Long.class, projectCode);
        jdbc.update("UPDATE project SET status='WITHDRAWN', withdrawn_at=now() WHERE id = ?", pid);

        List<Map<String, Object>> owned = jdbc.queryForList(
                "SELECT id, object_uid AS uid FROM data_object WHERE owning_project_id = ?", pid);
        List<Map<String, Object>> protectedShared = new java.util.ArrayList<>();
        List<String> withdrawnObjects = new java.util.ArrayList<>();

        for (Map<String, Object> o : owned) {
            long oid = ((Number) o.get("id")).longValue();
            String uid = (String) o.get("uid");
            Integer activeRefs = jdbc.queryForObject("""
                    SELECT count(*) FROM object_share s
                    JOIN project p ON p.id = s.project_id
                    WHERE s.object_id = ? AND p.id <> ? AND p.status = 'ACTIVE'
                    """, Integer.class, oid, pid);
            if (activeRefs != null && activeRefs > 0) {
                protectedShared.add(Map.of("uid", uid,
                        "verdict", "BLOCKED_SHARED_REFERENCE",
                        "activeReferencingProjects", activeRefs,
                        "reason", "项目退出后仍被其他活跃项目引用，不得整体删除"));
            } else {
                jdbc.update("UPDATE data_object SET withdrawn = true WHERE id = ?", oid);
                withdrawnObjects.add(uid);
            }
        }
        List<String> affected = owned.stream().map(o -> (String) o.get("uid")).toList();
        jdbc.update("""
                INSERT INTO governance_event(event_type, project_id, payload, propagated, affected_uids)
                VALUES ('PROJECT_WITHDRAWN', ?, ?::jsonb, true, ?::jsonb)
                """, pid, writeJson(Map.of("projectCode", projectCode,
                        "reason", reason == null ? "" : reason,
                        "protectedShared", protectedShared,
                        "withdrawnObjects", withdrawnObjects)),
                writeJson(affected));
        // 退出后重算保留（冻结/共享状态变化需传播）
        retention.recalculate();
        return Map.of("projectCode", projectCode, "status", "WITHDRAWN",
                "withdrawnObjects", withdrawnObjects, "protectedSharedObjects", protectedShared);
    }

    /** 恢复项目（演示/测试用） */
    @Transactional
    public Map<String, Object> reactivate(String projectCode) {
        jdbc.update("UPDATE project SET status='ACTIVE', withdrawn_at=NULL WHERE code = ?", projectCode);
        Long pid = jdbc.queryForObject("SELECT id FROM project WHERE code = ?",
                Long.class, projectCode);
        jdbc.update("UPDATE data_object SET withdrawn = false WHERE owning_project_id = ?", pid);
        retention.recalculate();
        return Map.of("projectCode", projectCode, "status", "ACTIVE");
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
