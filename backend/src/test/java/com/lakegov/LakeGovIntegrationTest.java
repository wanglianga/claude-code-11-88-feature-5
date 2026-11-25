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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：依赖本地 PostgreSQL（ccdev 库），应用启动时自动建表+种子。
 * 与 scripts/verify_e2e.py 覆盖相同核心规则，供 mvn test 使用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LakeGovIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private HttpHeaders auth() {
        Map<String, Object> resp = rest.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "admin123"), Map.class).getBody();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth((String) resp.get("token"));
        return h;
    }

    @Test
    @Order(1)
    void catalogSeededWithResearchASizes() {
        Map[] objects = rest.exchange("/api/catalog/objects", HttpMethod.GET,
                new HttpEntity<>(auth()), Map[].class).getBody();
        assertNotNull(objects);
        assertEquals(10, objects.length);
        Map<String, Object> raw = List.of(objects).stream()
                .filter(o -> "RAW-IMG-A01".equals(o.get("uid"))).findFirst().orElseThrow();
        assertEquals(2_000_000_000_000L, ((Number) raw.get("byteSize")).longValue());
        assertEquals(64, ((String) raw.get("checksum")).length());
    }

    @Test
    @Order(2)
    void dependencyClosureContainsSevenObjectsWithSoftwareAndCalibration() {
        Map[] closure = rest.exchange("/api/retention/closure/RESULT-A01", HttpMethod.GET,
                new HttpEntity<>(auth()), Map[].class).getBody();
        assertNotNull(closure);
        assertEquals(7, closure.length);
        var uids = List.of(closure).stream().map(o -> (String) o.get("uid")).toList();
        assertTrue(uids.containsAll(List.of(
                "RESULT-A01", "FEAT-A01", "MASK-A01", "RAW-IMG-A01",
                "ANN-A01", "CAL-A01", "ALGO-PKG-01")));
    }

    @Test
    @Order(3)
    void retentionRawImageTenYearsAndSoftwareExtended() {
        rest.exchange("/api/retention/recalculate", HttpMethod.POST,
                new HttpEntity<>(auth()), List.class);
        Map[] overview = rest.exchange("/api/retention/overview", HttpMethod.GET,
                new HttpEntity<>(auth()), Map[].class).getBody();
        Map<String, Map> byUid = new java.util.HashMap<>();
        for (Map o : overview) byUid.put((String) o.get("uid"), o);
        assertEquals("2036-01-15", String.valueOf(byUid.get("RAW-IMG-A01").get("baseDeadline")));
        assertTrue(String.valueOf(byUid.get("ALGO-PKG-01").get("effectiveDeadline"))
                .compareTo("2034-02-05") >= 0);
    }

    @Test
    @Order(4)
    void litigationHoldPropagatesDownstreamAndReleases() {
        HttpHeaders h = auth();
        Map hold = rest.exchange("/api/retention/hold", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "RAW-IMG-A01", "litigationHold", true,
                        "reason", "junit"), h), Map.class).getBody();
        @SuppressWarnings("unchecked")
        List<String> propagated = (List<String>) hold.get("propagatedTo");
        assertTrue(propagated.containsAll(List.of("MASK-A01", "FEAT-A01", "RESULT-A01")));
        Map[] overview = rest.exchange("/api/retention/overview", HttpMethod.GET,
                new HttpEntity<>(h), Map[].class).getBody();
        Map<String, Map> byUid = new java.util.HashMap<>();
        for (Map o : overview) byUid.put((String) o.get("uid"), o);
        assertEquals("BLOCKED_HOLD", byUid.get("RESULT-A01").get("disposition"));
        rest.exchange("/api/retention/hold", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "RAW-IMG-A01", "litigationHold", false,
                        "reason", "junit release"), h), Map.class);
    }

    @Test
    @Order(5)
    void missingCalibrationBlocksRestoreBeforeStart() {
        HttpHeaders h = auth();
        rest.exchange("/api/restore/availability", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "CAL-A01", "available", false), h), Map.class);
        Map plan = rest.exchange("/api/restore/plans", HttpMethod.POST,
                new HttpEntity<>(Map.of("targetUid", "RESULT-A01",
                        "strategy", "SCHEDULED_TIERING", "frequencyPerYear", 1), h), Map.class).getBody();
        assertEquals("UNSATISFIABLE", plan.get("status"));
        @SuppressWarnings("unchecked")
        List<Map> missing = (List<Map>) plan.get("missingItems");
        assertTrue(missing.stream().anyMatch(m -> "CAL-A01".equals(m.get("uid"))));
        rest.exchange("/api/restore/availability", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "CAL-A01", "available", true), h), Map.class);
    }

    @Test
    @Order(6)
    void strategyComparisonBreakevenBetweenYearlyAndMonthly() {
        Map cmp = rest.exchange(
                "/api/cost/strategy-comparison?targetUid=RESULT-A01&frequencies=1&frequencies=12",
                HttpMethod.GET, new HttpEntity<>(auth()), Map.class).getBody();
        double breakeven = Double.parseDouble(String.valueOf(cmp.get("breakevenFrequencyPerYear")));
        assertTrue(breakeven > 1 && breakeven < 12, "breakeven=" + breakeven);
        @SuppressWarnings("unchecked")
        List<Map> scenarios = (List<Map>) cmp.get("scenarios");
        assertEquals("SCHEDULED_TIERING", scenarios.get(0).get("cheaperStrategy"));
        assertEquals("RESTORE_PACKAGE", scenarios.get(1).get("cheaperStrategy"));
    }

    @Test
    @Order(7)
    void monthlyBillingAllocationsReconcile() {
        HttpHeaders h = auth();
        Map bill = rest.exchange("/api/cost/billing/generate", HttpMethod.POST,
                new HttpEntity<>(Map.of("billingMonth", "2026-11"), h), Map.class).getBody();
        assertEquals(Boolean.TRUE, bill.get("allocationCheck"));
        assertNotNull(bill.get("grandTotal"));
    }

    @Test
    @Order(8)
    void snapshotCapturesCatalogLineageAndDecisions() {
        Map snap = rest.exchange("/api/snapshots", HttpMethod.POST,
                new HttpEntity<>(auth()), Map.class).getBody();
        assertEquals("RV-2026Q4", snap.get("rateVersion"));
        assertTrue(((Number) snap.get("catalogObjects")).intValue() >= 10);
        assertTrue(((Number) snap.get("lineageEdges")).intValue() >= 9);
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM archive_snapshot", Integer.class);
        assertNotNull(rows);
        assertTrue(rows > 0);
    }
}
