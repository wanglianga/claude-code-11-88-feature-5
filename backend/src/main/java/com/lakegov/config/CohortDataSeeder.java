package com.lakegov.config;

import com.lakegov.cohort.CohortRng;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 研究队列样例种子（幂等）。
 *
 * 固定生成种子 {@link #GEN_SEED}，任何环境首次启动都得到同一套候选目录：
 * 120 名患者、恰好 1600 次扫描、2 种设备、仅 8 名患者携带罕见协议
 * RARE_PERF_FMRI（其中 1 人许可已过期 → 可抽取的罕见协议患者为 7 人），
 * 另有目录 QC 排除与 VAL 许可范围受限患者，用于核验排除/许可范围规则。
 */
@Component
public class CohortDataSeeder implements CommandLineRunner {

    public static final long GEN_SEED = 20261125L;
    public static final String RARE_PROTOCOL = "RARE_PERF_FMRI";
    public static final String DEVICE_A = "SKYRA_3T";
    public static final String DEVICE_B = "DISCOVERY_MR750";

    private static final Logger log = LoggerFactory.getLogger(CohortDataSeeder.class);

    private final JdbcTemplate jdbc;

    public CohortDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        Integer patientRows = jdbc.queryForObject("SELECT count(*) FROM cohort_patient", Integer.class);
        if (patientRows == null || patientRows == 0) {
            seedCatalog();
        }
        upsertProfiles();
    }

    private void seedCatalog() {
        CohortRng rng = new CohortRng(GEN_SEED);
        final int totalPatients = 120;

        // 疾病等级精确分布：0=24, 1=36, 2=36, 3=24
        List<Integer> grades = new ArrayList<>();
        for (int g = 0; g <= 3; g++) {
            int count = (g == 1 || g == 2) ? 36 : 24;
            for (int k = 0; k < count; k++) {
                grades.add(g);
            }
        }
        int[] gradeOrder = new int[grades.size()];
        for (int i = 0; i < gradeOrder.length; i++) {
            gradeOrder[i] = i;
        }
        rng.shuffle(gradeOrder);

        // 两种设备各 60 名主设备
        List<String> devicePool = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            devicePool.add(DEVICE_A);
            devicePool.add(DEVICE_B);
        }
        int[] deviceShuffle = new int[120];
        for (int i = 0; i < 120; i++) {
            deviceShuffle[i] = i;
        }
        rng.shuffle(deviceShuffle);
        String[] primaryDevice = new String[120];
        for (int i = 0; i < 120; i++) {
            primaryDevice[i] = devicePool.get(deviceShuffle[i]);
        }

        // 特殊集合（0 基下标）
        java.util.Set<Integer> rareIndices = setOf(6, 18, 30, 43, 57, 72, 87, 101);
        java.util.Set<Integer> consentExpired = setOf(43, 12, 60, 96, 114); // 43 同时是罕见协议
        java.util.Set<Integer> qcExcluded = setOf(25, 82);
        java.util.Set<Integer> valOnly = setOf(4, 51, 108);
        java.util.Set<Integer> bothDevice = setOf(3, 27, 55, 79, 100, 116);

        String[] gradeLabels = {"G0-无显著异常", "G1-轻度", "G2-中度", "G3-重度"};
        String[] seqNames = {
                "T1 MPRAGE 3D", "T2 TSE", "T2 FLAIR", "DWI b=1000",
                "T1 MPRAGE+C", "Rare BOLD perfusion/fMRI"
        };
        Map<String, String> sequenceByProtocol = Map.of(
                "T1W", seqNames[0], "T2W", seqNames[1], "FLAIR", seqNames[2],
                "DWI", seqNames[3], "T1C", seqNames[4], RARE_PROTOCOL, seqNames[5]);

        // 先决定每名患者的协议集合与扫描数，再把总扫描数精确调整到 1600
        String[][] protocols = new String[120][];
        int[] scanCounts = new int[120];
        int totalScans = 0;
        for (int i = 0; i < 120; i++) {
            int grade = grades.get(gradeOrder[i]);
            List<String> protos = new ArrayList<>(List.of("T1W", "T2W"));
            if (grade >= 1) {
                protos.add("FLAIR");
            }
            if (grade >= 2) {
                protos.add("DWI");
            }
            if ((i * 7 + grade * 3) % 3 == 0) {
                protos.add("T1C");
            }
            if (rareIndices.contains(i)) {
                protos.add(RARE_PROTOCOL);
            }
            protocols[i] = protos.toArray(new String[0]);
            scanCounts[i] = 9 + rng.nextInt(9); // 9..17，期望约 1560
            totalScans += scanCounts[i];
        }
        final int targetScans = 1600;
        int cursor = 0;
        while (totalScans < targetScans) {
            if (scanCounts[cursor % 120] < 22) {
                scanCounts[cursor % 120]++;
                totalScans++;
            }
            cursor++;
        }
        while (totalScans > targetScans) {
            if (scanCounts[cursor % 120] > protocols[cursor % 120].length + 1) {
                scanCounts[cursor % 120]--;
                totalScans--;
            }
            cursor++;
        }

        List<Object[]> patientBatch = new ArrayList<>();
        List<Object[]> scanBatch = new ArrayList<>();
        LocalDateTime catalogBase = LocalDateTime.of(2026, 9, 1, 9, 0);
        LocalDateTime scanBase = LocalDateTime.of(2024, 3, 1, 8, 0);

        for (int i = 0; i < totalPatients; i++) {
            String patientUid = String.format("CP-%03d", i + 1);
            int grade = grades.get(gradeOrder[i]);

            List<String> devices = new ArrayList<>();
            devices.add(primaryDevice[i]);
            if (bothDevice.contains(i)) {
                String other = DEVICE_A.equals(primaryDevice[i]) ? DEVICE_B : DEVICE_A;
                devices.add(other);
            }

            boolean expired = consentExpired.contains(i);
            boolean qcBad = qcExcluded.contains(i);
            String consentStatus = expired ? "EXPIRED" : "ACTIVE";
            String scopeJson = valOnly.contains(i) ? "[\"VAL\"]" : "[\"TRAIN\",\"VAL\"]";
            var expiresAt = expired ? java.sql.Date.valueOf("2025-12-31")
                    : java.sql.Date.valueOf(i % 2 == 0 ? "2027-12-31" : "2028-06-30");
            String exclusionReason = qcBad ? "QC_MOTION" : null;
            String exclusionDetail = qcBad ? "运动伪影/覆盖不全，入库 QC 判定不合格，禁止入组" : null;
            Timestamp sourceUpdated = Timestamp.valueOf(catalogBase.plusHours(i * 3L));

            patientBatch.add(new Object[]{
                    patientUid, grade, gradeLabels[grade], primaryDevice[i],
                    toJson(devices), toJson(List.of(protocols[i])), scanCounts[i],
                    consentStatus, scopeJson, expiresAt,
                    exclusionReason, exclusionDetail, sourceUpdated
            });

            // 生成恰好 scanCounts[i] 次扫描：先保证每个协议至少 1 次，其余为序列复扫
            List<String> protoList = List.of(protocols[i]);
            int scanNo = 0;
            for (String p : protoList) {
                scanNo++;
                scanBatch.add(scanRow(i, patientUid, scanNo, p, sequenceByProtocol.get(p),
                        devices, rng, scanBase, false));
            }
            for (int k = protoList.size(); k < scanCounts[i]; k++) {
                scanNo++;
                String p = protoList.get(rng.nextInt(protoList.size()));
                scanBatch.add(scanRow(i, patientUid, scanNo, p, sequenceByProtocol.get(p),
                        devices, rng, scanBase, true));
            }
        }

        jdbc.batchUpdate("""
                INSERT INTO cohort_patient
                  (patient_uid, disease_grade, disease_label, primary_device,
                   device_features, protocol_features, scan_count,
                   consent_status, consent_scope, consent_expires_at,
                   catalog_exclusion_reason, exclusion_detail, source_updated_at)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?,?::jsonb,?::date,?,?,?)
                """, patientBatch);

        jdbc.batchUpdate("""
                INSERT INTO cohort_scan
                  (scan_uid, patient_uid, device_code, protocol_code, sequence_name,
                   session_no, is_repeat_rescan, acquired_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, scanBatch);

        log.info("Cohort seed ready: {} patients, {} scans, rare protocol {} on {} patients",
                totalPatients, scanBatch.size(), RARE_PROTOCOL, rareIndices.size());
    }

    private Object[] scanRow(int patientIndex, String patientUid, int scanNo, String protocol,
                             String sequenceName, List<String> devices, CohortRng rng,
                             LocalDateTime scanBase, boolean repeat) {
        String scanUid = String.format("CS-%03d-%02d", patientIndex + 1, scanNo);
        String device = devices.size() > 1 && rng.nextInt(100) < 30
                ? (DEVICE_A.equals(devices.get(0)) ? DEVICE_B : DEVICE_A)
                : devices.get(0);
        int sessionNo = ((scanNo - 1) / 3) + 1;
        Timestamp acquired = Timestamp.valueOf(
                scanBase.plusDays((long) patientIndex * 5 + (long) scanNo * 28 + rng.nextInt(12)));
        return new Object[]{scanUid, patientUid, device, protocol, sequenceName,
                sessionNo, repeat, acquired};
    }

    private void upsertProfiles() {
        jdbc.update("""
                INSERT INTO cohort_constraint_profile(code, name, spec_json, builtin)
                VALUES ('DEFAULT_80_20', '默认 80/20 队列（预设最低覆盖，双路线均须满足）',
                        ?::jsonb, true)
                ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name,
                                                 spec_json = EXCLUDED.spec_json
                """, defaultSpec(false));
        jdbc.update("""
                INSERT INTO cohort_constraint_profile(code, name, spec_json, builtin)
                VALUES ('RARE_COVERAGE_GAP', '罕见协议极限约束（每分区至少4人，全局仅7名合格）',
                        ?::jsonb, true)
                ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name,
                                                 spec_json = EXCLUDED.spec_json
                """, defaultSpec(true));
    }

    /** 默认规格；gap=true 时把罕见协议硬下限提高到每分区 4（合计 8 > 合格池 7）。 */
    public static String defaultSpec(boolean gap) {
        int rareMin = gap ? 4 : 2;
        return """
                {
                  "splits": [
                    {"code": "TRAIN", "targetRatio": 0.80, "tolerance": 5},
                    {"code": "VAL",   "targetRatio": 0.20, "tolerance": 4}
                  ],
                  "quotaWeight": 2.0,
                  "coverage": [
                    {"dimension": "DISEASE_GRADE", "value": "0", "split": "ALL", "min": 4, "weight": 1.5},
                    {"dimension": "DISEASE_GRADE", "value": "1", "split": "ALL", "min": 4, "weight": 1.2},
                    {"dimension": "DISEASE_GRADE", "value": "2", "split": "ALL", "min": 4, "weight": 1.2},
                    {"dimension": "DISEASE_GRADE", "value": "3", "split": "ALL", "min": 4, "weight": 1.5},
                    {"dimension": "DEVICE", "value": "%s", "split": "ALL", "min": 5, "weight": 1.0},
                    {"dimension": "DEVICE", "value": "%s", "split": "ALL", "min": 5, "weight": 1.0},
                    {"dimension": "PROTOCOL", "value": "%s", "split": "ALL", "min": %d, "target": 4, "weight": 3.0},
                    {"dimension": "PROTOCOL", "value": "T1W",   "split": "ALL", "weight": 0.4},
                    {"dimension": "PROTOCOL", "value": "T2W",   "split": "ALL", "weight": 0.4},
                    {"dimension": "PROTOCOL", "value": "FLAIR", "split": "ALL", "weight": 0.6},
                    {"dimension": "PROTOCOL", "value": "DWI",   "split": "ALL", "weight": 0.6},
                    {"dimension": "PROTOCOL", "value": "T1C",   "split": "ALL", "weight": 0.8}
                  ]
                }
                """.formatted(DEVICE_A, DEVICE_B, RARE_PROTOCOL, rareMin);
    }

    private static java.util.Set<Integer> setOf(Integer... values) {
        java.util.Set<Integer> s = new java.util.HashSet<>();
        Collections.addAll(s, values);
        return s;
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 供候选目录/总览复用的分布描述（保持插入顺序）。 */
    public static Map<String, String> gradeLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("0", "G0-无显著异常");
        m.put("1", "G1-轻度");
        m.put("2", "G2-中度");
        m.put("3", "G3-重度");
        return m;
    }
}
