package com.lakegov.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakegov.cohort.Blueprint;
import com.lakegov.cohort.ConstraintCompiler;
import com.lakegov.cohort.ConstraintSpec;
import com.lakegov.cohort.CohortModel;
import com.lakegov.cohort.GlobalAssignmentSolver;
import com.lakegov.cohort.PatientGroup;
import com.lakegov.cohort.RunRequest;
import com.lakegov.cohort.SolveOutcome;
import com.lakegov.cohort.StreamingStratifiedSolver;
import com.lakegov.config.CohortDataSeeder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 可复现研究队列抽取服务。
 *
 * 职责：候选目录查询、输入水位（计数 + 规范内容 SHA-256）、特征池统计、
 * 约束编译、两条求解路线编排、抽取账（种子/水位/拒绝原因）持久化，
 * 以及独立于求解器、直接核对库内分配的校验器
 * （患者跨分区、扫描整组性、配额偏差、覆盖缺口、许可范围）。
 */
@Service
public class CohortService {

    public static final long DEFAULT_SEED = CohortDataSeeder.GEN_SEED;
    public static final String STRATEGY_GLOBAL = "GLOBAL_ASSIGN";
    public static final String STRATEGY_STREAM = "STREAM_STRATIFIED";

    /** 纳入规则：研究许可有效且目录 QC/重复等排除原因为空。 */
    private static final String ELIGIBLE =
            "consent_status = 'ACTIVE' AND catalog_exclusion_reason IS NULL";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StreamingStratifiedSolver streamSolver = new StreamingStratifiedSolver();

    public CohortService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ============================================================
    // 候选目录 / 总览
    // ============================================================

    public Map<String, Object> overview() {
        Map<String, Object> o = new LinkedHashMap<>();
        int patients = count("SELECT count(*) FROM cohort_patient");
        int scans = count("SELECT count(*) FROM cohort_scan");
        o.put("patientCount", patients);
        o.put("scanCount", scans);
        o.put("deviceCount", count("SELECT count(DISTINCT device_code) FROM cohort_scan"));
        o.put("deviceCodes", jdbc.queryForList(
                "SELECT DISTINCT device_code FROM cohort_scan ORDER BY 1", String.class));
        o.put("rareProtocol", CohortDataSeeder.RARE_PROTOCOL);
        o.put("rarePatients", countFeaturePatients("PROTOCOL", CohortDataSeeder.RARE_PROTOCOL, false));
        o.put("rareEligible", countFeaturePatients("PROTOCOL", CohortDataSeeder.RARE_PROTOCOL, true));
        o.put("consentExpired", count("SELECT count(*) FROM cohort_patient WHERE consent_status='EXPIRED'"));
        o.put("consentRevoked", count("SELECT count(*) FROM cohort_patient WHERE consent_status='REVOKED'"));
        o.put("catalogExcluded", count(
                "SELECT count(*) FROM cohort_patient WHERE catalog_exclusion_reason IS NOT NULL"));
        int eligible = count("SELECT count(*) FROM cohort_patient WHERE " + ELIGIBLE);
        o.put("eligiblePatients", eligible);
        o.put("rejectedPatients", patients - eligible);

        List<Map<String, Object>> grades = new ArrayList<>();
        for (int g = 0; g <= 3; g++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("grade", String.valueOf(g));
            row.put("label", CohortDataSeeder.gradeLabels().get(String.valueOf(g)));
            row.put("patients", count("SELECT count(*) FROM cohort_patient WHERE disease_grade=" + g));
            row.put("eligible", count("SELECT count(*) FROM cohort_patient WHERE disease_grade=" + g
                    + " AND " + ELIGIBLE));
            grades.add(row);
        }
        o.put("diseaseGrades", grades);

        List<Map<String, Object>> devices = new ArrayList<>();
        for (String d : List.of(CohortDataSeeder.DEVICE_A, CohortDataSeeder.DEVICE_B)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", d);
            row.put("patients", countFeaturePatients("DEVICE", d, false));
            row.put("eligible", countFeaturePatients("DEVICE", d, true));
            devices.add(row);
        }
        o.put("devices", devices);

        List<String> protocols = jdbc.queryForList(
                "SELECT DISTINCT protocol_code FROM cohort_scan ORDER BY 1", String.class);
        List<Map<String, Object>> protoRows = new ArrayList<>();
        for (String p : protocols) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", p);
            row.put("patients", countFeaturePatients("PROTOCOL", p, false));
            row.put("eligible", countFeaturePatients("PROTOCOL", p, true));
            protoRows.add(row);
        }
        o.put("protocols", protoRows);

        o.put("exclusionReasons", jdbc.queryForList("""
                SELECT catalog_exclusion_reason AS reasonCode, count(*) AS patients
                FROM cohort_patient
                WHERE catalog_exclusion_reason IS NOT NULL
                GROUP BY catalog_exclusion_reason ORDER BY 1
                """));

        Watermark wm = computeWatermark();
        o.put("inputWatermark", wm.text());
        o.put("watermark", wm.json());
        return o;
    }

    public List<Map<String, Object>> listPatients(String eligibility, String q) {
        StringBuilder sql = new StringBuilder("""
                SELECT patient_uid AS "patientUid", disease_grade AS "diseaseGrade",
                       disease_label AS "diseaseLabel", primary_device AS "primaryDevice",
                       device_features AS "deviceFeatures",
                       protocol_features AS "protocolFeatures",
                       scan_count AS "scanCount", consent_status AS "consentStatus",
                       consent_scope AS "consentScope",
                       consent_expires_at AS "consentExpiresAt",
                       catalog_exclusion_reason AS "exclusionReason",
                       exclusion_detail AS "exclusionDetail",
                       source_updated_at AS "sourceUpdatedAt",
                       (""" + ELIGIBLE + """
                       ) AS "eligible"
                FROM cohort_patient WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if ("only".equalsIgnoreCase(eligibility) || "eligible".equalsIgnoreCase(eligibility)) {
            sql.append(" AND ").append(ELIGIBLE);
        } else if ("excluded".equalsIgnoreCase(eligibility) || "rejected".equalsIgnoreCase(eligibility)) {
            sql.append(" AND NOT (").append(ELIGIBLE).append(")");
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND patient_uid ILIKE ?");
            args.add("%" + q.trim() + "%");
        }
        sql.append(" ORDER BY patient_uid");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> listScans(String patientUid) {
        if (patientUid != null && !patientUid.isBlank()) {
            return jdbc.queryForList("""
                    SELECT scan_uid AS "scanUid", patient_uid AS "patientUid",
                           device_code AS "deviceCode", protocol_code AS "protocolCode",
                           sequence_name AS "sequenceName", session_no AS "sessionNo",
                           is_repeat_rescan AS "repeatRescan", acquired_at AS "acquiredAt"
                    FROM cohort_scan WHERE patient_uid = ? ORDER BY scan_uid
                    """, patientUid);
        }
        return jdbc.queryForList("""
                SELECT scan_uid AS "scanUid", patient_uid AS "patientUid",
                       device_code AS "deviceCode", protocol_code AS "protocolCode",
                       sequence_name AS "sequenceName", session_no AS "sessionNo",
                       is_repeat_rescan AS "repeatRescan", acquired_at AS "acquiredAt"
                FROM cohort_scan ORDER BY scan_uid
                """);
    }

    public List<Map<String, Object>> listProfiles() {
        return jdbc.queryForList("""
                SELECT code, name, spec_json AS "spec", builtin
                FROM cohort_constraint_profile ORDER BY id
                """);
    }

    /** 约束编译器试算（不落账）：返回硬约束/软目标与必不可行预警。 */
    public Blueprint.CompiledView compilePreview(Map<String, Object> body) {
        ConstraintSpec spec;
        Object rawSpec = body == null ? null : body.get("spec");
        if (rawSpec != null) {
            spec = mapper.convertValue(rawSpec, ConstraintSpec.class);
        } else {
            String code = body == null || body.get("profileCode") == null
                    ? "DEFAULT_80_20" : String.valueOf(body.get("profileCode"));
            spec = loadProfileSpec(code);
        }
        int eligible = count("SELECT count(*) FROM cohort_patient WHERE " + ELIGIBLE);
        return ConstraintCompiler.compile(spec, eligible, featurePools(spec)).toView();
    }

    // ============================================================
    // 抽取运行
    // ============================================================

    @Transactional
    public Map<String, Object> runExtraction(RunRequest req, String username) {
        RunRequest request = req == null ? new RunRequest(null, null, null, null, null, null) : req;

        Map<String, Object> parent = null;
        Long parentId = request.incrementalFromRunId();
        if (parentId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM cohort_run WHERE id = ?", parentId);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("父抽取账本不存在: runId=" + parentId);
            }
            parent = rows.get(0);
        }

        ConstraintSpec spec = request.spec();
        String profileCode = request.profileCode();
        long seed = request.seed() == null ? DEFAULT_SEED : request.seed();
        if (parent != null) {
            if (spec == null) {
                spec = readJson(String.valueOf(parent.get("spec_json")), ConstraintSpec.class);
            }
            if (profileCode == null && parent.get("profile_code") != null) {
                profileCode = String.valueOf(parent.get("profile_code"));
            }
            if (request.seed() == null && parent.get("seed") != null) {
                seed = ((Number) parent.get("seed")).longValue();
            }
        }
        if (spec == null) {
            spec = loadProfileSpec(profileCode == null ? "DEFAULT_80_20" : profileCode);
        }
        if (profileCode == null) {
            profileCode = "CUSTOM";
        }

        String strategy = request.strategy() == null ? STRATEGY_GLOBAL
                : request.strategy().trim().toUpperCase();
        if (!STRATEGY_GLOBAL.equals(strategy) && !STRATEGY_STREAM.equals(strategy)) {
            throw new IllegalArgumentException("未知求解路线: " + strategy
                    + "（支持 GLOBAL_ASSIGN / STREAM_STRATIFIED）");
        }
        boolean incremental = parent != null;
        if (incremental && !STRATEGY_STREAM.equals(strategy)) {
            throw new IllegalArgumentException(
                    "增量沿用仅支持 STREAM_STRATIFIED 路线；GLOBAL_ASSIGN 为一次性整体重算");
        }

        Watermark wm = computeWatermark();
        int candidateCount = count("SELECT count(*) FROM cohort_patient");
        int eligibleCount = count("SELECT count(*) FROM cohort_patient WHERE " + ELIGIBLE);

        // ---- 拒绝原因入账（许可失效 / 许可撤销 / 目录排除） ----
        List<Map<String, Object>> rejected = jdbc.queryForList("""
                SELECT patient_uid, consent_status, consent_expires_at,
                       catalog_exclusion_reason, exclusion_detail
                FROM cohort_patient WHERE NOT (""" + ELIGIBLE + ") ORDER BY patient_uid");
        List<Object[]> rejectionRows = new ArrayList<>();
        for (Map<String, Object> row : rejected) {
            String uid = String.valueOf(row.get("patient_uid"));
            String consent = String.valueOf(row.get("consent_status"));
            if ("EXPIRED".equals(consent)) {
                rejectionRows.add(new Object[]{null, uid, "CONSENT_EXPIRED",
                        "研究许可已于 " + row.get("consent_expires_at") + " 到期，不得纳入训练/验证队列"});
            } else if ("REVOKED".equals(consent)) {
                rejectionRows.add(new Object[]{null, uid, "CONSENT_REVOKED", "研究许可已被撤销"});
            }
            Object reason = row.get("catalog_exclusion_reason");
            if (reason != null) {
                rejectionRows.add(new Object[]{null, uid, String.valueOf(reason),
                        row.get("exclusion_detail") == null ? "候选目录标注的排除原因"
                                : String.valueOf(row.get("exclusion_detail"))});
            }
        }

        Map<String, Integer> pools = featurePools(spec);
        Blueprint blueprint = ConstraintCompiler.compile(spec, eligibleCount, pools);

        // ---- 按路线求解 ----
        long t0 = System.nanoTime();
        List<PatientGroup> alignedGroups;
        SolveOutcome outcome;

        if (STRATEGY_GLOBAL.equals(strategy)) {
            alignedGroups = loadEligibleGroups();
            CohortModel model = new CohortModel(blueprint, alignedGroups);
            outcome = new GlobalAssignmentSolver().solve(model, seed);
        } else if (incremental) {
            alignedGroups = loadEligibleGroups();
            Map<String, String> parentSplit = new LinkedHashMap<>();
            Map<String, String> parentPath = new LinkedHashMap<>();
            jdbc.queryForList("""
                    SELECT patient_uid, split_code, decision_path
                    FROM cohort_assignment WHERE run_id = ?
                    """, parentId).forEach(r -> {
                parentSplit.put(String.valueOf(r.get("patient_uid")),
                        String.valueOf(r.get("split_code")));
                parentPath.put(String.valueOf(r.get("patient_uid")),
                        String.valueOf(r.get("decision_path")));
            });
            outcome = streamSolver.solveIncremental(
                    blueprint, alignedGroups, parentSplit, parentPath, seed);
        } else {
            // 真·流式：RowCallbackHandler 逐行读取患者聚合特征，扫描行不进入求解器
            StreamingStratifiedSolver.Session session =
                    streamSolver.start(blueprint, eligibleCount, seed);
            jdbc.query("""
                    SELECT patient_uid, disease_grade, device_features::text AS device_features,
                           protocol_features::text AS protocol_features,
                           consent_scope::text AS consent_scope, scan_count
                    FROM cohort_patient WHERE""" + " " + ELIGIBLE + " ORDER BY patient_uid",
                    (RowCallbackHandler) rs -> {
                        try {
                            session.accept(new PatientGroup(
                                    rs.getString("patient_uid"),
                                    rs.getInt("disease_grade"),
                                    mapper.readValue(rs.getString("device_features"),
                                            new TypeReference<List<String>>() {
                                            }),
                                    mapper.readValue(rs.getString("protocol_features"),
                                            new TypeReference<List<String>>() {
                                            }),
                                    mapper.readValue(rs.getString("consent_scope"),
                                            new TypeReference<List<String>>() {
                                            }),
                                    rs.getInt("scan_count")));
                        } catch (Exception e) {
                            throw new IllegalStateException("流式读取候选患者失败: " + e.getMessage(), e);
                        }
                    });
            outcome = session.finish();
            alignedGroups = new ArrayList<>();
            for (int i = 0; i < session.seen(); i++) {
                alignedGroups.add(session.groupAt(i));
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        outcome.stats.put("solveElapsedMs", elapsedMs);

        if (alignedGroups.size() != outcome.assignment.length) {
            throw new IllegalStateException("内部错误：分组数与分配数不一致");
        }

        int assignedScans = 0;
        List<Object[]> assignmentRows = new ArrayList<>();
        for (int i = 0; i < alignedGroups.size(); i++) {
            PatientGroup g = alignedGroups.get(i);
            int s = outcome.assignment[i];
            if (s < 0) {
                continue;
            }
            assignedScans += g.scanCount;
            assignmentRows.add(new Object[]{
                    null, g.uid, blueprint.splitCodes.get(s), g.scanCount, outcome.paths[i]
            });
        }

        String runUid = "CR-" + UUID.randomUUID().toString().substring(0, 8);
        String specJson = writeJson(spec);
        String compiledJson = writeJson(blueprint.toView());
        String wmJson = writeJson(wm.json());
        String statsJson = writeJson(outcome.stats);

        Long runId = jdbc.queryForObject("""
                INSERT INTO cohort_run
                  (run_uid, profile_code, run_name, strategy, seed, spec_json, compiled_json,
                   input_watermark, watermark_json, status,
                   candidate_patients, eligible_patients, rejected_patients,
                   assigned_patients, assigned_scans, objective_score, solver_stats,
                   incremental, parent_run_id, delta_patients, created_by)
                VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?,?::jsonb,'OK', ?,?,?, ?,?, ?,?::jsonb, ?,?,?,?)
                RETURNING id
                """, Long.class,
                runUid, profileCode,
                request.runName() == null ? profileCode + "-" + strategy : request.runName(),
                strategy, seed, specJson, compiledJson, wm.text(), wmJson,
                candidateCount, eligibleCount, rejected.size(),
                assignmentRows.size(), assignedScans, outcome.eval.scalar, statsJson,
                incremental, parentId, outcome.stats.getOrDefault("deltaNewGroups", 0),
                username == null ? "system" : username);

        for (Object[] r : rejectionRows) {
            r[0] = runId;
        }
        jdbc.batchUpdate("""
                INSERT INTO cohort_rejection(run_id, patient_uid, reason_code, detail)
                VALUES (?,?,?,?)
                """, rejectionRows);
        for (Object[] r : assignmentRows) {
            r[0] = runId;
        }
        jdbc.batchUpdate("""
                INSERT INTO cohort_assignment(run_id, patient_uid, split_code, scan_count, decision_path)
                VALUES (?,?,?,?,?)
                """, assignmentRows);

        validateRun(runId);
        return getRun(runId);
    }

    public List<Map<String, Object>> listRuns() {
        return jdbc.queryForList("""
                SELECT r.id AS "runId", r.run_uid AS "runUid", r.run_name AS "runName",
                       r.profile_code AS "profileCode", r.strategy, r.seed, r.status,
                       r.candidate_patients AS "candidatePatients",
                       r.eligible_patients AS "eligiblePatients",
                       r.rejected_patients AS "rejectedPatients",
                       r.assigned_patients AS "assignedPatients",
                       r.assigned_scans AS "assignedScans",
                       r.objective_score AS "objectiveScore",
                       r.input_watermark AS "inputWatermark",
                       r.incremental, r.parent_run_id AS "parentRunId",
                       r.delta_patients AS "deltaPatients",
                       r.created_by AS "createdBy", r.created_at AS "createdAt",
                       v.passed AS "validationPassed"
                FROM cohort_run r
                LEFT JOIN cohort_validation v ON v.run_id = r.id
                ORDER BY r.id DESC
                """);
    }

    public Map<String, Object> getRun(Long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id AS "runId", run_uid AS "runUid", profile_code AS "profileCode",
                       run_name AS "runName", strategy, seed, status,
                       spec_json AS "spec", compiled_json AS "compiled",
                       input_watermark AS "inputWatermark", watermark_json AS "watermark",
                       candidate_patients AS "candidatePatients",
                       eligible_patients AS "eligiblePatients",
                       rejected_patients AS "rejectedPatients",
                       assigned_patients AS "assignedPatients",
                       assigned_scans AS "assignedScans",
                       objective_score AS "objectiveScore",
                       solver_stats AS "solverStats",
                       incremental, parent_run_id AS "parentRunId",
                       delta_patients AS "deltaPatients",
                       created_by AS "createdBy", created_at AS "createdAt"
                FROM cohort_run WHERE id = ?
                """, runId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("抽取账本不存在: runId=" + runId);
        }
        Map<String, Object> run = rows.get(0);
        run.put("rejections", jdbc.queryForList("""
                SELECT patient_uid AS "patientUid", reason_code AS "reasonCode", detail
                FROM cohort_rejection WHERE run_id = ? ORDER BY id
                """, runId));
        run.put("assignments", jdbc.queryForList("""
                SELECT a.patient_uid AS "patientUid", a.split_code AS "split",
                       a.scan_count AS "scanCount", a.decision_path AS "decisionPath",
                       p.disease_grade AS "diseaseGrade", p.disease_label AS "diseaseLabel",
                       p.primary_device AS "primaryDevice",
                       p.device_features AS "deviceFeatures",
                       p.protocol_features AS "protocolFeatures",
                       p.consent_status AS "consentStatus",
                       p.consent_scope AS "consentScope"
                FROM cohort_assignment a
                JOIN cohort_patient p ON p.patient_uid = a.patient_uid
                WHERE a.run_id = ?
                ORDER BY a.split_code, a.patient_uid
                """, runId));
        List<Map<String, Object>> v = jdbc.queryForList(
                "SELECT passed, report_json::text AS report FROM cohort_validation WHERE run_id=?",
                runId);
        if (!v.isEmpty()) {
            run.put("validationPassed", v.get(0).get("passed"));
            run.put("validation", readJson(String.valueOf(v.get(0).get("report")), Map.class));
        }
        return run;
    }

    // ============================================================
    // 校验器：直接核对库内分配，不依赖求解器自我声明
    // ============================================================

    @Transactional
    public Map<String, Object> validateRun(Long runId) {
        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM cohort_run WHERE id = ?", runId);
        JsonNode compiled = readTree(String.valueOf(run.get("compiled_json")));
        List<String> splitCodes = new ArrayList<>();
        compiled.path("splitCodes").forEach(n -> splitCodes.add(n.asText()));

        Map<String, Object> report = new LinkedHashMap<>();

        // 1) 患者跨分区（唯一约束已使重复不可能，仍独立核对）
        List<Map<String, Object>> cross = jdbc.queryForList("""
                SELECT patient_uid AS "patientUid", count(DISTINCT split_code) AS "splitCount"
                FROM cohort_assignment WHERE run_id = ?
                GROUP BY patient_uid HAVING count(DISTINCT split_code) > 1
                """, runId);
        Map<String, Object> leakage = new LinkedHashMap<>();
        leakage.put("leakageFree", cross.isEmpty());
        leakage.put("crossSplitPatientCount", cross.size());
        leakage.put("crossSplitPatients", cross);
        report.put("patientLeakage", leakage);

        // 2) 扫描整组性：同一患者的扫描经分配只可能映射到一个分区
        List<Map<String, Object>> wholenessBad = jdbc.queryForList("""
                SELECT s.patient_uid AS "patientUid",
                       count(DISTINCT a.split_code) AS "distinctSplits"
                FROM cohort_scan s
                JOIN cohort_assignment a
                  ON a.patient_uid = s.patient_uid AND a.run_id = ?
                GROUP BY s.patient_uid
                HAVING count(DISTINCT a.split_code) > 1
                """, runId);
        List<Map<String, Object>> countMismatch = jdbc.queryForList("""
                SELECT a.patient_uid AS "patientUid",
                       a.scan_count AS "assignedScanCount",
                       count(s.scan_uid) AS "catalogScanCount"
                FROM cohort_assignment a
                LEFT JOIN cohort_scan s ON s.patient_uid = a.patient_uid
                WHERE a.run_id = ?
                GROUP BY a.patient_uid, a.scan_count
                HAVING a.scan_count <> count(s.scan_uid)
                """, runId);
        Integer multiScanPatients = jdbc.queryForObject("""
                SELECT count(*) FROM cohort_assignment a
                WHERE a.run_id = ? AND a.scan_count > 1
                """, Integer.class, runId);
        Map<String, Object> wholeness = new LinkedHashMap<>();
        boolean wholenessOk = wholenessBad.isEmpty() && countMismatch.isEmpty();
        wholeness.put("allScansWholeGroup", wholenessOk);
        wholeness.put("splitViolationCount", wholenessBad.size());
        wholeness.put("violatingPatients", wholenessBad);
        wholeness.put("scanCountMismatchCount", countMismatch.size());
        wholeness.put("scanCountMismatches", countMismatch);
        wholeness.put("multiScanAssignedPatients", multiScanPatients);
        report.put("scanWholeness", wholeness);

        // 3) 许可范围
        List<Map<String, Object>> consentBad = jdbc.queryForList("""
                SELECT a.patient_uid AS "patientUid", a.split_code AS split,
                       p.consent_status AS "consentStatus",
                       p.consent_scope::text AS "consentScope"
                FROM cohort_assignment a
                JOIN cohort_patient p ON p.patient_uid = a.patient_uid
                WHERE a.run_id = ?
                  AND (p.consent_status <> 'ACTIVE'
                       OR NOT jsonb_exists(p.consent_scope, a.split_code))
                """, runId);
        Map<String, Object> consent = new LinkedHashMap<>();
        consent.put("consentScopeOk", consentBad.isEmpty());
        consent.put("violationCount", consentBad.size());
        consent.put("violations", consentBad);
        report.put("consentScope", consent);

        // 4) 配额 + 覆盖：从库内分配重新统计
        List<Map<String, Object>> assignedFeatures = jdbc.queryForList("""
                SELECT a.split_code AS split, p.disease_grade AS grade,
                       p.device_features::text AS dev, p.protocol_features::text AS proto
                FROM cohort_assignment a
                JOIN cohort_patient p ON p.patient_uid = a.patient_uid
                WHERE a.run_id = ?
                """, runId);
        int sCount = splitCodes.size();
        int[] actualCount = new int[sCount];
        for (Map<String, Object> row : assignedFeatures) {
            int s = splitCodes.indexOf(String.valueOf(row.get("split")));
            if (s >= 0) {
                actualCount[s]++;
            }
        }

        List<Map<String, Object>> quota = new ArrayList<>();
        boolean quotaOk = true;
        JsonNode splitNodes = compiled.path("splits");
        for (int s = 0; s < splitNodes.size(); s++) {
            JsonNode sn = splitNodes.get(s);
            String code = sn.path("code").asText();
            int actual = actualCount[s];
            int target = sn.path("targetPatients").asInt();
            int min = sn.path("minPatients").asInt();
            int max = sn.path("maxPatients").asInt();
            boolean within = actual >= min && actual <= max;
            quotaOk &= within;
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("split", code);
            q.put("actual", actual);
            q.put("target", target);
            q.put("min", min);
            q.put("max", max);
            q.put("deviation", actual - target);
            q.put("withinBounds", within);
            quota.add(q);
        }
        report.put("quota", quota);
        report.put("quotaWithinBounds", quotaOk);

        // 覆盖矩阵
        List<JsonNode> coverageNodes = new ArrayList<>();
        compiled.path("coverage").forEach(coverageNodes::add);
        List<Map<String, Object>> coverageReport = new ArrayList<>();
        List<Map<String, Object>> gaps = new ArrayList<>();
        boolean hardCoverageMet = true;

        for (JsonNode rule : coverageNodes) {
            String dim = rule.path("dimension").asText();
            String value = rule.path("value").asText();
            int rCount = sCount;
            int[] actualCov = new int[rCount];
            for (Map<String, Object> f : assignedFeatures) {
                int s = splitCodes.indexOf(String.valueOf(f.get("split")));
                if (s < 0) {
                    continue;
                }
                if (hasFeature(dim, value, f)) {
                    actualCov[s]++;
                }
            }
            Map<String, Object> cr = new LinkedHashMap<>();
            cr.put("dimension", dim);
            cr.put("value", value);
            cr.put("featurePool", rule.path("featurePool").asInt());
            Map<String, Object> bySplit = new LinkedHashMap<>();
            for (int s = 0; s < sCount; s++) {
                int min = rule.path("min").get(s).asInt();
                int target = rule.path("target").get(s).asInt();
                int gap = Math.max(0, min - actualCov[s]);
                if (gap > 0) {
                    hardCoverageMet = false;
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("dimension", dim);
                    g.put("value", value);
                    g.put("split", splitCodes.get(s));
                    g.put("requiredMin", min);
                    g.put("actual", actualCov[s]);
                    g.put("missing", gap);
                    gaps.add(g);
                }
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("actual", actualCov[s]);
                cell.put("min", min);
                cell.put("target", target);
                cell.put("hardMet", gap == 0);
                cell.put("hardGap", gap);
                cell.put("targetShortfall", Math.max(0, target - actualCov[s]));
                bySplit.put(splitCodes.get(s), cell);
            }
            cr.put("bySplit", bySplit);
            coverageReport.add(cr);
        }
        report.put("coverage", coverageReport);
        report.put("coverageGaps", gaps);
        report.put("hardCoverageMet", hardCoverageMet);

        boolean passed = cross.isEmpty() && wholenessOk && consentBad.isEmpty()
                && quotaOk && hardCoverageMet;
        report.put("passed", passed);
        report.put("hardConstraintsMet", quotaOk && hardCoverageMet);
        report.put("validatedAt", LocalDateTime.now().toString());

        String json = writeJson(report);
        jdbc.update("""
                INSERT INTO cohort_validation(run_id, passed, report_json)
                VALUES (?,?,?::jsonb)
                ON CONFLICT (run_id) DO UPDATE
                  SET passed = EXCLUDED.passed,
                      report_json = EXCLUDED.report_json,
                      validated_at = now()
                """, runId, passed, json);
        jdbc.update("UPDATE cohort_run SET status = ? WHERE id = ?",
                passed ? "OK" : "COVERAGE_GAP", runId);
        return report;
    }

    // ============================================================
    // 候选新增 / 删除（增量演示与测试自清理；仅允许 CP-TMP- 前缀）
    // ============================================================

    @Transactional
    public Map<String, Object> createCandidate(Map<String, Object> body) {
        String uid = body.get("patientUid") == null ? null
                : String.valueOf(body.get("patientUid")).trim();
        if (uid == null || uid.isBlank()) {
            uid = "CP-TMP-" + UUID.randomUUID().toString().substring(0, 10);
        }
        if (!uid.startsWith("CP-TMP-")) {
            throw new IllegalArgumentException("通过该接口新增的演示患者编号必须以 CP-TMP- 开头");
        }
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM cohort_patient WHERE patient_uid=?", Integer.class, uid);
        if (exists != null && exists > 0) {
            throw new IllegalArgumentException("患者编号已存在: " + uid);
        }
        int grade = body.get("diseaseGrade") == null ? 2
                : Integer.parseInt(String.valueOf(body.get("diseaseGrade")));
        String device = body.get("deviceCode") == null ? CohortDataSeeder.DEVICE_A
                : String.valueOf(body.get("deviceCode"));
        @SuppressWarnings("unchecked")
        List<String> protocols = body.get("protocols") instanceof List<?> l
                ? (List<String>) l
                : new ArrayList<>(List.of("T1W", "T2W", "FLAIR"));
        if (Boolean.TRUE.equals(body.get("rare")) && !protocols.contains(CohortDataSeeder.RARE_PROTOCOL)) {
            protocols.add(CohortDataSeeder.RARE_PROTOCOL);
        }
        String consentStatus = body.get("consentStatus") == null ? "ACTIVE"
                : String.valueOf(body.get("consentStatus"));
        String scopeJson = body.get("consentScope") == null ? "[\"TRAIN\",\"VAL\"]"
                : writeJson(body.get("consentScope"));
        int scanCount = body.get("scanCount") == null ? 4
                : Integer.parseInt(String.valueOf(body.get("scanCount")));

        jdbc.update("""
                INSERT INTO cohort_patient
                  (patient_uid, disease_grade, disease_label, primary_device,
                   device_features, protocol_features, scan_count,
                   consent_status, consent_scope, consent_expires_at, source_updated_at)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?,?::jsonb,?, now())
                """,
                uid, grade, CohortDataSeeder.gradeLabels().get(String.valueOf(grade)), device,
                writeJson(List.of(device)), writeJson(protocols), scanCount,
                consentStatus, scopeJson,
                "EXPIRED".equals(consentStatus) ? java.sql.Date.valueOf("2025-12-31")
                        : java.sql.Date.valueOf("2027-12-31"));
        for (int k = 1; k <= scanCount; k++) {
            String protocol = protocols.get((k - 1) % protocols.size());
            jdbc.update("""
                    INSERT INTO cohort_scan
                      (scan_uid, patient_uid, device_code, protocol_code, sequence_name,
                       session_no, is_repeat_rescan, acquired_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,
                    String.format("CS-TMP-%s-%02d", uid.substring(7), k), uid, device, protocol,
                    protocol + " sequence", (k - 1) / 3 + 1, k > protocols.size(),
                    java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(scanCount - k)));
        }
        return Map.of("patientUid", uid, "scanCount", scanCount);
    }

    @Transactional
    public Map<String, Object> deleteCandidate(String uid) {
        if (uid == null || !uid.startsWith("CP-TMP-")) {
            throw new IllegalArgumentException("仅允许删除 CP-TMP- 前缀的临时演示患者");
        }
        jdbc.update("DELETE FROM cohort_scan WHERE patient_uid=?", uid);
        int removed = jdbc.update("DELETE FROM cohort_patient WHERE patient_uid=?", uid);
        if (removed == 0) {
            throw new IllegalArgumentException("候选患者不存在: " + uid);
        }
        return Map.of("deleted", uid);
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private List<PatientGroup> loadEligibleGroups() {
        return jdbc.queryForList("""
                SELECT patient_uid, disease_grade,
                       device_features::text AS device_features,
                       protocol_features::text AS protocol_features,
                       consent_scope::text AS consent_scope, scan_count
                FROM cohort_patient WHERE""" + " " + ELIGIBLE + " ORDER BY patient_uid")
                .stream().map(row -> {
                    try {
                        return new PatientGroup(
                                String.valueOf(row.get("patient_uid")),
                                ((Number) row.get("disease_grade")).intValue(),
                                mapper.readValue(String.valueOf(row.get("device_features")),
                                        new TypeReference<List<String>>() {
                                        }),
                                mapper.readValue(String.valueOf(row.get("protocol_features")),
                                        new TypeReference<List<String>>() {
                                        }),
                                mapper.readValue(String.valueOf(row.get("consent_scope")),
                                        new TypeReference<List<String>>() {
                                        }),
                                ((Number) row.get("scan_count")).intValue());
                    } catch (Exception e) {
                        throw new IllegalStateException("读取候选患者特征失败: " + e.getMessage(), e);
                    }
                }).toList();
    }

    private Map<String, Integer> featurePools(ConstraintSpec spec) {
        Map<String, Integer> pools = new LinkedHashMap<>();
        if (spec.coverage() == null) {
            return pools;
        }
        for (ConstraintSpec.CoverageRuleSpec rule : spec.coverage()) {
            String key = rule.dimension() + "=" + rule.value();
            if (pools.containsKey(key)) {
                continue;
            }
            pools.put(key, countFeaturePatients(rule.dimension(), rule.value(), true));
        }
        return pools;
    }

    private int countFeaturePatients(String dimension, String value, boolean eligibleOnly) {
        String where = (eligibleOnly ? ELIGIBLE + " AND " : "") + switch (dimension) {
            case "DISEASE_GRADE" -> "disease_grade = ?";
            case "DEVICE" -> "jsonb_exists(device_features, ?)";
            case "PROTOCOL" -> "jsonb_exists(protocol_features, ?)";
            default -> throw new IllegalArgumentException("未知覆盖维度: " + dimension);
        };
        Integer v;
        if ("DISEASE_GRADE".equals(dimension)) {
            v = jdbc.queryForObject("SELECT count(*) FROM cohort_patient WHERE " + where,
                    Integer.class, Integer.parseInt(value));
        } else {
            v = jdbc.queryForObject("SELECT count(*) FROM cohort_patient WHERE " + where,
                    Integer.class, value);
        }
        return v == null ? 0 : v;
    }

    private boolean hasFeature(String dimension, String value, Map<String, Object> featureRow) {
        try {
            switch (dimension) {
                case "DISEASE_GRADE":
                    return value.equals(String.valueOf(featureRow.get("grade")));
                case "DEVICE":
                    List<String> dev = mapper.readValue(
                            String.valueOf(featureRow.get("dev")), new TypeReference<List<String>>() {
                            });
                    return dev.contains(value);
                case "PROTOCOL":
                    List<String> proto = mapper.readValue(
                            String.valueOf(featureRow.get("proto")), new TypeReference<List<String>>() {
                            });
                    return proto.contains(value);
                default:
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private ConstraintSpec loadProfileSpec(String code) {
        List<String> json = jdbc.queryForList(
                "SELECT spec_json::text FROM cohort_constraint_profile WHERE code=?",
                String.class, code);
        if (json.isEmpty()) {
            throw new IllegalArgumentException("约束画像不存在: " + code);
        }
        return readJson(json.get(0), ConstraintSpec.class);
    }

    private int count(String sql) {
        Integer v = jdbc.queryForObject(sql, Integer.class);
        return v == null ? 0 : v;
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /** 输入水位：计数 + 最大 source_updated_at + 规范化目录内容 SHA-256。 */
    public Watermark computeWatermark() {
        int patients = count("SELECT count(*) FROM cohort_patient");
        int scans = count("SELECT count(*) FROM cohort_scan");
        String maxTs = jdbc.queryForObject(
                "SELECT coalesce(max(source_updated_at)::text, '') FROM cohort_patient", String.class);
        StringBuilder canonical = new StringBuilder();
        jdbc.queryForList("""
                SELECT patient_uid, disease_grade, primary_device,
                       device_features::text AS df, protocol_features::text AS pf,
                       consent_status, consent_scope::text AS cs,
                       coalesce(consent_expires_at::text,'') AS exp,
                       coalesce(catalog_exclusion_reason,'') AS ex,
                       source_updated_at::text AS uts
                FROM cohort_patient ORDER BY patient_uid
                """).forEach(r -> canonical.append(String.join("|",
                str(r, "patient_uid"), str(r, "disease_grade"), str(r, "primary_device"),
                str(r, "df"), str(r, "pf"), str(r, "consent_status"), str(r, "cs"),
                str(r, "exp"), str(r, "ex"), str(r, "uts"))).append('\n'));
        jdbc.queryForList("""
                SELECT scan_uid, patient_uid, device_code, protocol_code,
                       is_repeat_rescan, acquired_at::text AS ats
                FROM cohort_scan ORDER BY scan_uid
                """).forEach(r -> canonical.append(String.join("|",
                str(r, "scan_uid"), str(r, "patient_uid"), str(r, "device_code"),
                str(r, "protocol_code"), str(r, "is_repeat_rescan"), str(r, "ats"))).append('\n'));

        String hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            hash = HexFormat.of().formatHex(
                    md.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String text = "cohort-wm/v1:p" + patients + ":s" + scans + ":" + hash.substring(0, 16);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("schema", "cohort-wm/v1");
        json.put("patientCount", patients);
        json.put("scanCount", scans);
        json.put("maxSourceUpdatedAt", maxTs);
        json.put("catalogSha256", hash);
        return new Watermark(text, json);
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    public record Watermark(String text, Map<String, Object> json) {
    }
}
