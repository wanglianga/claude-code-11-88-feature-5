package com.lakegov.web;

import com.lakegov.service.LineageService;
import com.lakegov.service.RetentionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/retention")
public class RetentionController {

    private final RetentionService retention;
    private final LineageService lineage;
    private final JdbcTemplate jdbc;

    public RetentionController(RetentionService retention, LineageService lineage, JdbcTemplate jdbc) {
        this.retention = retention;
        this.lineage = lineage;
        this.jdbc = jdbc;
    }

    @PostMapping("/recalculate")
    public List<Map<String, Object>> recalculate() {
        return retention.recalculate();
    }

    @GetMapping("/overview")
    public List<Map<String, Object>> overview() {
        return retention.overview();
    }

    @PostMapping("/hold")
    public Map<String, Object> hold(@RequestBody Map<String, Object> body) {
        return retention.setHold((String) body.get("uid"),
                Boolean.TRUE.equals(body.get("litigationHold")),
                (String) body.get("reason"));
    }

    @PostMapping("/checksum-failed")
    public Map<String, Object> checksumFailed(@RequestBody Map<String, Object> body) {
        return retention.markChecksumFailed((String) body.get("uid"), (String) body.get("reason"));
    }

    @PostMapping("/checksum-ok")
    public Map<String, Object> checksumOk(@RequestBody Map<String, String> body) {
        retention.markChecksumOk(body.get("uid"));
        return Map.of("uid", body.get("uid"), "checksumStatus", "OK");
    }

    @PostMapping("/policy")
    public Map<String, Object> updatePolicy(@RequestBody Map<String, Object> body) {
        return retention.updatePolicy((String) body.get("category"),
                (String) body.get("protocol"),
                Double.valueOf(body.get("retentionYears").toString()),
                (String) body.get("ruleText"));
    }

    @GetMapping("/policies")
    public List<Map<String, Object>> policies() {
        return jdbc.queryForList("""
                SELECT category, protocol_code AS protocol, retention_years AS "retentionYears", rule_text AS "ruleText"
                FROM retention_policy ORDER BY category, protocol
                """);
    }

    @GetMapping("/deletion-check/{uid}")
    public Map<String, Object> deletionCheck(@PathVariable String uid) {
        return retention.deletionCheck(uid);
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events() {
        return retention.events();
    }

    @GetMapping("/closure/{uid}")
    public List<Map<String, Object>> closure(@PathVariable String uid) {
        Long id = jdbc.queryForObject("SELECT id FROM data_object WHERE object_uid = ?",
                Long.class, uid);
        return lineage.dependencyClosure(id);
    }
}
