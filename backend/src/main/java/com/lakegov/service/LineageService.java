package com.lakegov.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 血缘 DAG：PostgreSQL 递归 CTE 实现
 * - 反向依赖闭包（恢复计划用）：沿 to→from 求全部输入
 * - 正向影响传播（保留/冻结/校验失败用）：沿 from→to 求全部派生结果
 */
@Service
public class LineageService {

    private final JdbcTemplate jdbc;

    public LineageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 完整 DAG（节点 + 边），供前端 SVG 渲染 */
    public Map<String, Object> fullGraph() {
        List<Map<String, Object>> nodes = jdbc.queryForList("""
                SELECT o.id, o.object_uid AS uid, o.name, o.category, o.byte_size AS "byteSize",
                       o.tier_code AS tier, o.checksum_status AS "checksumStatus",
                       o.litigation_hold AS "litigationHold", o.owning_project_id AS "projectId",
                       p.code AS "projectCode"
                FROM data_object o JOIN project p ON p.id = o.owning_project_id
                ORDER BY o.id
                """);
        List<Map<String, Object>> edges = jdbc.queryForList("""
                SELECT e.id, f.object_uid AS "fromUid", t.object_uid AS "toUid",
                       e.edge_version AS "edgeVersion", e.recipe, e.software_version AS "softwareVersion",
                       e.shared_reference AS "sharedReference"
                FROM lineage_edge e
                JOIN data_object f ON f.id = e.from_object_id
                JOIN data_object t ON t.id = e.to_object_id
                ORDER BY e.id
                """);
        return Map.of("nodes", nodes, "edges", edges);
    }

    /**
     * 反向依赖闭包：给定目标对象，沿血缘反向求完整输入集合（含目标自身）。
     * 返回顺序按依赖深度。
     */
    public List<Map<String, Object>> dependencyClosure(long targetObjectId) {
        return jdbc.queryForList("""
                WITH RECURSIVE closure AS (
                    SELECT o.*, 0 AS depth
                    FROM data_object o WHERE o.id = ?
                    UNION
                    SELECT d.*, c.depth + 1
                    FROM data_object d
                    JOIN lineage_edge e ON e.from_object_id = d.id
                    JOIN closure c ON c.id = e.to_object_id
                )
                SELECT DISTINCT ON (id) id, object_uid AS uid, name, category,
                       byte_size AS "byteSize", checksum, checksum_status AS "checksumStatus",
                       tier_code AS tier, software_version AS "softwareVersion", recipe,
                       directory_available AS "directoryAvailable", litigation_hold AS "litigationHold",
                       depth
                FROM closure
                ORDER BY id, depth
                """, targetObjectId);
    }

    /** 闭包的 uid 列表（保持插入顺序，便于快照/清单比对） */
    public List<String> closureUids(long targetObjectId) {
        return dependencyClosure(targetObjectId).stream()
                .map(m -> (String) m.get("uid")).toList();
    }

    /**
     * 正向影响传播：给定源对象，求其全部派生（下游）结果，含自身。
     * 用于校验失败、保留规则变化、争议冻结向所有依赖结果传播。
     */
    public List<Map<String, Object>> impactPropagation(long sourceObjectId) {
        return jdbc.queryForList("""
                WITH RECURSIVE impact AS (
                    SELECT o.*, 0 AS depth
                    FROM data_object o WHERE o.id = ?
                    UNION
                    SELECT d.*, i.depth + 1
                    FROM data_object d
                    JOIN lineage_edge e ON e.to_object_id = d.id
                    JOIN impact i ON i.id = e.from_object_id
                )
                SELECT DISTINCT ON (id) id, object_uid AS uid, name, category,
                       tier_code AS tier, checksum_status AS "checksumStatus",
                       litigation_hold AS "litigationHold", depth
                FROM impact
                ORDER BY id, depth
                """, sourceObjectId);
    }

    /** 直接下游 uid（一层） */
    public List<String> directDownstreamUids(long objectId) {
        return jdbc.queryForList("""
                SELECT t.object_uid FROM lineage_edge e
                JOIN data_object t ON t.id = e.to_object_id
                WHERE e.from_object_id = ?
                """, String.class, objectId);
    }

    /** 新增血缘边（管理操作） */
    public Map<String, Object> addEdge(String fromUid, String toUid, String version,
                                       String recipe, String softwareVersion, boolean shared) {
        Long from = jdbc.queryForObject("SELECT id FROM data_object WHERE object_uid = ?",
                Long.class, fromUid);
        Long to = jdbc.queryForObject("SELECT id FROM data_object WHERE object_uid = ?",
                Long.class, toUid);
        if (from == null || to == null) {
            throw new IllegalArgumentException("对象不存在: " + fromUid + " / " + toUid);
        }
        if (wouldCreateCycle(from, to)) {
            throw new IllegalStateException("该边将形成血缘环，拒绝写入: " + fromUid + " -> " + toUid);
        }
        jdbc.update("""
                INSERT INTO lineage_edge(from_object_id, to_object_id, edge_version, recipe,
                                         software_version, shared_reference)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (from_object_id, to_object_id) DO NOTHING
                """, from, to, version == null ? "v1" : version, recipe, softwareVersion, shared);
        return Map.of("from", fromUid, "to", toUid, "accepted", true);
    }

    /** 环检测：若 to 已在 from 的反向闭包中，则加边成环 */
    private boolean wouldCreateCycle(long fromId, long newToId) {
        Integer hit = jdbc.queryForObject("""
                WITH RECURSIVE closure AS (
                    SELECT id FROM data_object WHERE id = ?
                    UNION
                    SELECT e.from_object_id FROM lineage_edge e
                    JOIN closure c ON c.id = e.to_object_id
                )
                SELECT count(*) FROM closure WHERE id = ?
                """, Integer.class, fromId, newToId);
        return hit != null && hit > 0;
    }

    /** 共享引用视图：对象 → 引用项目及冻结份额 */
    public List<Map<String, Object>> sharedReferences() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT o.object_uid AS uid, o.name, p.code AS "projectCode",
                       s.frozen_share AS "frozenShare", s.share_basis AS "shareBasis",
                       s.frozen_at AS "frozenAt"
                FROM object_share s
                JOIN data_object o ON o.id = s.object_id
                JOIN project p ON p.id = s.project_id
                ORDER BY o.object_uid, p.code
                """);
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String uid = (String) row.get("uid");
            Map<String, Object> agg = grouped.computeIfAbsent(uid, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("uid", uid);
                m.put("name", row.get("name"));
                m.put("allocations", new ArrayList<Map<String, Object>>());
                return m;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> allocs = (List<Map<String, Object>>) agg.get("allocations");
            allocs.add(Map.of("projectCode", row.get("projectCode"),
                    "frozenShare", row.get("frozenShare"),
                    "shareBasis", row.get("shareBasis")));
        }
        return new ArrayList<>(grouped.values());
    }
}
