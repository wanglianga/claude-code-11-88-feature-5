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
 * 归档快照：固定目录水位、依赖、费率版本与迁移/保留/计费决定，
 * 可回放“当时为何迁移、保留或计费”。
 */
@Service
public class SnapshotService {

    private final JdbcTemplate jdbc;
    private final LineageService lineage;
    private final RetentionService retention;
    private final ObjectMapper mapper = new ObjectMapper();

    public SnapshotService(JdbcTemplate jdbc, LineageService lineage, RetentionService retention) {
        this.jdbc = jdbc;
        this.lineage = lineage;
        this.retention = retention;
    }

    @Transactional
    public Map<String, Object> createSnapshot() {
        LocalDate today = LocalDate.now();
        String rateVersion = jdbc.queryForObject(
                "SELECT rate_version FROM storage_tier LIMIT 1", String.class);

        // 目录水位
        List<Map<String, Object>> catalog = jdbc.queryForList("""
                SELECT o.object_uid AS uid, o.name, o.category, o.byte_size AS "byteSize",
                       o.checksum, o.checksum_status AS "checksumStatus", o.tier_code AS tier,
                       o.owning_project_id AS "projectId", o.created_at AS "createdAt",
                       o.last_accessed_at AS "lastAccessedAt", o.base_retention_deadline AS "baseDeadline",
                       o.retention_deadline AS "retentionDeadline", o.litigation_hold AS "litigationHold",
                       o.software_version AS "softwareVersion", o.directory_available AS "directoryAvailable",
                       o.is_restore_package AS "restorePackage", o.package_target_uid AS "packageTarget"
                FROM data_object o ORDER BY o.id
                """);
        // 依赖水位
        Map<String, Object> graph = lineage.fullGraph();
        // 账单水位
        List<Map<String, Object>> billing = jdbc.queryForList("""
                SELECT p.code AS "projectCode", b.billing_month AS "billingMonth",
                       b.storage_fee AS "storageFee", b.retrieval_fee AS "retrievalFee",
                       b.restore_compute_fee AS "restoreComputeFee", b.total_fee AS "totalFee",
                       b.allocation_check AS "allocationCheck"
                FROM monthly_bill b JOIN project p ON p.id = b.project_id
                ORDER BY b.billing_month, p.code
                """);
        // 决定回放：迁移/保留/冻结/校验事件 + 当前保留处置
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (Map<String, Object> row : retention.overview()) {
            decisions.add(Map.of(
                    "kind", "RETENTION",
                    "uid", row.get("uid"),
                    "disposition", row.get("disposition"),
                    "effectiveDeadline", String.valueOf(row.get("effectiveDeadline")),
                    "reason", "基础期限=" + row.get("baseDeadline")
                            + (Boolean.TRUE.equals(row.get("litigationHold"))
                            ? "；争议冻结，无限期保留" : "；经血缘传播后取最严截止日")));
        }
        decisions.addAll(jdbc.queryForList("""
                SELECT event_type AS kind, payload, affected_uids AS "affectedUids",
                       created_at AS "decidedAt"
                FROM governance_event ORDER BY id
                """));

        jdbc.update("""
                INSERT INTO archive_snapshot(snapshot_date, rate_version, catalog_json, lineage_json,
                                             billing_json, decisions_json)
                VALUES (?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb)
                """, java.sql.Date.valueOf(today), rateVersion,
                writeJson(catalog), writeJson(graph), writeJson(billing), writeJson(decisions));
        long id = jdbc.queryForObject("SELECT currval('archive_snapshot_id_seq')", Long.class);
        return Map.of("snapshotId", id, "snapshotDate", today.toString(),
                "rateVersion", rateVersion, "catalogObjects", catalog.size(),
                "lineageEdges", ((List<?>) graph.get("edges")).size(),
                "billingEntries", billing.size(), "decisions", decisions.size());
    }

    public List<Map<String, Object>> listSnapshots() {
        return jdbc.queryForList("""
                SELECT id AS "snapshotId", snapshot_date AS "snapshotDate", rate_version AS "rateVersion",
                       jsonb_array_length(catalog_json) AS "catalogObjects",
                       jsonb_array_length(lineage_json->'edges') AS "lineageEdges",
                       jsonb_array_length(billing_json) AS "billingEntries",
                       jsonb_array_length(decisions_json) AS decisions,
                       created_at AS "createdAt"
                FROM archive_snapshot ORDER BY id DESC
                """);
    }

    /** 回放单个快照（完整 JSON 水位） */
    public Map<String, Object> replay(long snapshotId) {
        return jdbc.queryForMap("""
                SELECT id AS "snapshotId", snapshot_date AS "snapshotDate", rate_version AS "rateVersion",
                       catalog_json AS catalog, lineage_json AS lineage,
                       billing_json AS billing, decisions_json AS decisions,
                       created_at AS "createdAt"
                FROM archive_snapshot WHERE id = ?
                """, snapshotId);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
