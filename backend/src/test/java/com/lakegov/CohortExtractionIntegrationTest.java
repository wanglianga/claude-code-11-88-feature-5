package com.lakegov;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 研究队列抽取器集成测试。
 * 重点核验题目三场景：重复扫描整体分配、许可失效患者排除、
 * 罕见协议覆盖不足时报告缺口而不是拆分患者造成泄漏。
 * 另覆盖双路线硬约束、确定性复现、增量与 RBAC。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CohortExtractionIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private HttpHeaders adminHeaders() {
        Map<String, Object> resp = rest.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "admin123"), Map.class).getBody();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth((String) resp.get("token"));
        return h;
    }

    private HttpHeaders reviewerHeaders() {
        Map<String, Object> resp = rest.postForEntity("/api/auth/login",
                Map.of("username", "reviewer", "password", "review123"), Map.class).getBody();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth((String) resp.get("token"));
        return h;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postRun(HttpHeaders h, Map<String, Object> body) {
        ResponseEntity<Map> resp = rest.exchange("/api/cohort/runs", HttpMethod.POST,
                new HttpEntity<>(body, h), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), () -> "run failed: " + resp.getBody());
        return resp.getBody();
    }

    @Test
    @Order(1)
    void seedCatalogHas120Patients1600ScansTwoDevicesEightRare() {
        Map<String, Object> ov = rest.exchange("/api/cohort/overview", HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), Map.class).getBody();
        assertNotNull(ov);
        assertEquals(120, ((Number) ov.get("patientCount")).intValue());
        assertEquals(1600, ((Number) ov.get("scanCount")).intValue());
        assertEquals(2, ((Number) ov.get("deviceCount")).intValue());
        assertEquals(8, ((Number) ov.get("rarePatients")).intValue());
        assertEquals(7, ((Number) ov.get("rareEligible")).intValue(),
                "8 名罕见协议患者中 1 人许可过期，合格池应为 7");
        assertEquals(5, ((Number) ov.get("consentExpired")).intValue());
        assertEquals(2, ((Number) ov.get("catalogExcluded")).intValue());
        assertEquals(113, ((Number) ov.get("eligiblePatients")).intValue());
        assertNotNull(ov.get("inputWatermark"));
        assertTrue(String.valueOf(ov.get("inputWatermark")).startsWith("cohort-wm/v1:p120:s1600:"));
    }

    @Test
    @Order(2)
    void bothRoutesMeetPresetHardConstraintsAcrossSeeds() {
        HttpHeaders h = adminHeaders();
        for (String strategy : List.of("GLOBAL_ASSIGN", "STREAM_STRATIFIED")) {
            for (long seed : List.of(1L, 42L, 20261125L, 777L, 99L)) {
                Map<String, Object> run = postRun(h, Map.of(
                        "strategy", strategy, "profileCode", "DEFAULT_80_20", "seed", seed));
                Map<String, Object> v = validation(run);
                assertEquals(Boolean.TRUE, v.get("passed"),
                        () -> strategy + " seed=" + seed + " 应满足全部预设硬约束，缺口="
                                + v.get("coverageGaps") + " quota=" + v.get("quota"));
                assertTrue((Boolean) ((Map<?, ?>) v.get("patientLeakage")).get("leakageFree"));
                assertTrue((Boolean) ((Map<?, ?>) v.get("scanWholeness")).get("allScansWholeGroup"));
                assertTrue((Boolean) ((Map<?, ?>) v.get("consentScope")).get("consentScopeOk"));
                assertTrue(((List<?>) v.get("coverageGaps")).isEmpty());
            }
        }
    }

    @Test
    @Order(3)
    void sameSeedReproducesIdenticalAssignment() {
        HttpHeaders h = adminHeaders();
        for (String strategy : List.of("GLOBAL_ASSIGN", "STREAM_STRATIFIED")) {
            Map<String, Object> r1 = postRun(h, Map.of(
                    "strategy", strategy, "profileCode", "DEFAULT_80_20", "seed", 123456L));
            Map<String, Object> r2 = postRun(h, Map.of(
                    "strategy", strategy, "profileCode", "DEFAULT_80_20", "seed", 123456L));
            assertEquals(assignmentMap(r1), assignmentMap(r2),
                    strategy + " 相同种子必须得到完全一致的分区分配");
            // 注：不强制不同种子产出不同分配——求解器足够稳健时可能收敛到同一最优，
            // 题目要求的是“种子固定即可复现”，而非种子必然改变结果。
        }
    }

    @Test
    @Order(4)
    void repeatScansStayWholeAndConsentExpiredExcluded() {
        HttpHeaders h = adminHeaders();
        Map<String, Object> run = postRun(h, Map.of(
                "strategy", "GLOBAL_ASSIGN", "profileCode", "DEFAULT_80_20", "seed", 20261125L));
        Long runId = ((Number) run.get("runId")).longValue();

        // 显式构造的 5 名许可过期 + 2 名 QC 排除患者必须全部在拒绝账中
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rejections = (List<Map<String, Object>>) run.get("rejections");
        Map<String, String> reasonByPatient = new HashMap<>();
        for (Map<String, Object> r : rejections) {
            reasonByPatient.put((String) r.get("patientUid"), (String) r.get("reasonCode"));
        }
        for (String uid : List.of("CP-013", "CP-044", "CP-061", "CP-097", "CP-115")) {
            assertEquals("CONSENT_EXPIRED", reasonByPatient.get(uid), uid + " 应因许可过期被拒绝");
        }
        assertEquals("QC_MOTION", reasonByPatient.get("CP-026"));
        assertEquals("QC_MOTION", reasonByPatient.get("CP-083"));

        // 被拒绝患者不得出现在分配中
        Map<String, String> assigned = assignmentMap(run);
        for (String uid : reasonByPatient.keySet()) {
            assertFalse(assigned.containsKey(uid), "被拒绝患者不得被分配: " + uid);
        }

        // 库内核验：每名已分配患者的全部扫描只映射到同一个分区，且扫描数与目录一致
        Integer cross = jdbc.queryForObject("""
                SELECT count(*) FROM (
                  SELECT s.patient_uid FROM cohort_scan s
                  JOIN cohort_assignment a ON a.patient_uid = s.patient_uid AND a.run_id = ?
                  GROUP BY s.patient_uid HAVING count(DISTINCT a.split_code) > 1
                ) t
                """, Integer.class, runId);
        assertEquals(0, cross);
        Integer mismatch = jdbc.queryForObject("""
                SELECT count(*) FROM cohort_assignment a
                WHERE a.run_id = ? AND a.scan_count <>
                  (SELECT count(*) FROM cohort_scan s WHERE s.patient_uid = a.patient_uid)
                """, Integer.class, runId);
        assertEquals(0, mismatch);

        // 至少存在大量“多次复扫”患者被整体分配（样例 scanCount>=9）
        Integer multi = jdbc.queryForObject(
                "SELECT count(*) FROM cohort_assignment WHERE run_id=? AND scan_count > 1",
                Integer.class, runId);
        assertNotNull(multi);
        assertTrue(multi >= 100, "绝大多数患者应有多次扫描，实际=" + multi);
    }

    @Test
    @Order(5)
    void rareProtocolShortfallReportedAsGapWithoutLeakage() {
        HttpHeaders h = adminHeaders();
        for (String strategy : List.of("GLOBAL_ASSIGN", "STREAM_STRATIFIED")) {
            Map<String, Object> run = postRun(h, Map.of(
                    "strategy", strategy, "profileCode", "RARE_COVERAGE_GAP", "seed", 7L));
            Map<String, Object> v = validation(run);

            // 必须报告罕见协议缺口，而不是假装通过
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> gaps = (List<Map<String, Object>>) v.get("coverageGaps");
            assertTrue(gaps.stream().anyMatch(g -> "RARE_PERF_FMRI".equals(g.get("value"))
                    && ((Number) g.get("missing")).intValue() >= 1),
                    strategy + " 应报告罕见协议覆盖缺口: " + gaps);
            assertEquals(Boolean.FALSE, v.get("passed"));
            assertEquals("COVERAGE_GAP", run.get("status"));

            // 但患者不跨分区、扫描整组、许可范围仍然全部成立 —— 不靠泄漏补覆盖
            assertTrue((Boolean) ((Map<?, ?>) v.get("patientLeakage")).get("leakageFree"));
            assertTrue((Boolean) ((Map<?, ?>) v.get("scanWholeness")).get("allScansWholeGroup"));
            assertTrue((Boolean) ((Map<?, ?>) v.get("consentScope")).get("consentScopeOk"));

            Map<String, String> assigned = assignmentMap(run);
            long rareAssigned = assigned.keySet().stream().filter(uid -> {
                Integer c = jdbc.queryForObject(
                        "SELECT count(*) FROM cohort_patient WHERE patient_uid=? "
                                + "AND jsonb_exists(protocol_features,'RARE_PERF_FMRI') "
                                + "AND consent_status='ACTIVE' AND catalog_exclusion_reason IS NULL",
                        Integer.class, uid);
                return c != null && c > 0;
            }).count();
            assertTrue(rareAssigned <= 7, "合格罕见协议患者只有 7 人，实际分配=" + rareAssigned);
            // 每名患者至多一个分区（UNIQUE(run_id,patient_uid) 保证，这里再断言语义）
            long distinctPatients = assigned.size();
            Integer assignmentRows = jdbc.queryForObject(
                    "SELECT count(*) FROM cohort_assignment WHERE run_id=?",
                    Integer.class, ((Number) run.get("runId")).longValue());
            assertEquals(distinctPatients, assignmentRows.longValue());
        }
    }

    @Test
    @Order(6)
    void streamingIncrementalReusesBaselineAndDeltaOnly() {
        HttpHeaders h = adminHeaders();
        String uid = "CP-TMP-JUNIT-" + System.nanoTime();
        try {
            // 先有基线抽取
            Map<String, Object> base = postRun(h, Map.of(
                    "strategy", "STREAM_STRATIFIED", "profileCode", "DEFAULT_80_20", "seed", 555L));
            // 目录新增一名罕见协议患者（输入水位之后的增量）
            ResponseEntity<Map> created = rest.exchange("/api/cohort/candidates", HttpMethod.POST,
                    new HttpEntity<>(Map.of("patientUid", uid, "diseaseGrade", 3,
                            "rare", true, "scanCount", 6), h), Map.class);
            assertEquals(HttpStatus.OK, created.getStatusCode());

            Map<String, Object> inc1 = postRun(h, Map.of(
                    "strategy", "STREAM_STRATIFIED",
                    "incrementalFromRunId", ((Number) base.get("runId")).longValue(),
                    "profileCode", "DEFAULT_80_20", "seed", 555L));
            assertTrue(((Number) inc1.get("deltaPatients")).intValue() >= 1,
                    "增量轮应至少处理 1 名新患者");
            assertTrue(assignmentMap(inc1).containsKey(uid), "新患者必须进入增量分配");
            assertEquals(Boolean.TRUE, validation(inc1).get("passed"));

            // 目录不再变化，再次增量：delta=0，且分配与上一账本完全一致（无重算抖动）
            Map<String, Object> inc2 = postRun(h, Map.of(
                    "strategy", "STREAM_STRATIFIED",
                    "incrementalFromRunId", ((Number) inc1.get("runId")).longValue(),
                    "profileCode", "DEFAULT_80_20", "seed", 555L));
            assertEquals(0, ((Number) inc2.get("deltaPatients")).intValue());
            assertEquals(assignmentMap(inc1), assignmentMap(inc2),
                    "无目录变更时增量重算必须保持分配稳定");
        } finally {
            rest.exchange("/api/cohort/candidates/" + uid, HttpMethod.DELETE,
                    new HttpEntity<>(h), Map.class);
        }
    }

    @Test
    @Order(7)
    void reviewerIsReadOnlyForCohortRuns() {
        ResponseEntity<Map> resp = rest.exchange("/api/cohort/runs", HttpMethod.POST,
                new HttpEntity<>(Map.of("strategy", "STREAM_STRATIFIED"), reviewerHeaders()),
                Map.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validation(Map<String, Object> run) {
        return (Map<String, Object>) run.get("validation");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> assignmentMap(Map<String, Object> run) {
        Map<String, String> map = new HashMap<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) run.get("assignments")) {
            map.put((String) a.get("patientUid"), (String) a.get("split"));
        }
        return map;
    }
}
