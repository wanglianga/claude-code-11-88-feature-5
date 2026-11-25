package com.lakegov.cohort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路线 B：按患者组流式分层抽取 + 末轮整患者交换修复。
 *
 * 数据访问层每读到一名患者的聚合特征就调用一次 {@link Session#accept}，
 * 立即依据当前各层缺口决定分区，不缓存扫描行、也不回看已处理患者，
 * 因此常驻内存只与“患者数 × 覆盖规则数”的紧凑计数/位矩阵有关，
 * 并天然支持从输入水位之后增量追加患者。
 *
 * 末轮与全局路线共用 {@link AssignmentOptimizer} 做配额/覆盖交换修复；
 * 单次在线分层 + 一轮局部修复不保证全局最优（统计中如实标注），
 * 但患者不跨分区与可达到的最低覆盖硬约束同样必须满足。
 */
public final class StreamingStratifiedSolver {

    /** 开启一次流式抽取会话（容量=本次需要处理的患者总数，含预填的增量基线）。 */
    public Session start(Blueprint blueprint, int capacity, long seed) {
        return new Session(blueprint, capacity, seed);
    }

    /**
     * 增量抽取：历史患者沿用父账本分配（不重新决策），仅对新患者做在线分层，
     * 然后执行同一轮末轮交换修复。目录无变化时不会产生分配抖动。
     *
     * @param parentSplitByUid  父账本 patient_uid → 分区编码
     * @param parentPathByUid   父账本 patient_uid → 决策路径（可为空）
     */
    public SolveOutcome solveIncremental(Blueprint blueprint,
                                        List<PatientGroup> groups,
                                        Map<String, String> parentSplitByUid,
                                        Map<String, String> parentPathByUid,
                                        long seed) {
        int n = groups.size();
        CohortModel model = new CohortModel(blueprint, groups);
        int[] assignment = new int[n];
        String[] paths = new String[n];
        int[] patientCount = new int[blueprint.splitCount];
        int[][] coverageCount = new int[blueprint.rules.size()][blueprint.splitCount];
        CohortRng rng = new CohortRng(seed ^ 0x51A7BEEFL);
        java.util.Arrays.fill(assignment, -1);

        int reused = 0;
        List<Integer> fresh = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            PatientGroup g = groups.get(i);
            String parentSplit = parentSplitByUid == null ? null : parentSplitByUid.get(g.uid);
            if (parentSplit != null && blueprint.splitCodes.contains(parentSplit)) {
                int s = blueprint.splitCodes.indexOf(parentSplit);
                assignment[i] = s;
                String p = parentPathByUid == null ? null : parentPathByUid.get(g.uid);
                paths[i] = p == null ? "INCREMENTAL" : p;
                accumulate(model, i, s, patientCount, coverageCount, +1);
                reused++;
            } else {
                fresh.add(i);
            }
        }
        int delta = 0;
        // 新患者按 uid 顺序在线分层（确定性）
        for (int i : fresh) {
            int s = StratifiedPicker.choose(model, i, patientCount, coverageCount, rng, 0.05);
            assignment[i] = s;
            paths[i] = "INCREMENTAL";
            accumulate(model, i, s, patientCount, coverageCount, +1);
            delta++;
        }

        AssignmentOptimizer.Counters counters = new AssignmentOptimizer.Counters();
        AssignmentOptimizer.Eval eval = AssignmentOptimizer.optimize(
                model, assignment, paths, "SWAP_REPAIR", rng, 30, counters);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("route", "STREAM_STRATIFIED");
        stats.put("memoryMode", "INCREMENTAL_COMPACT");
        stats.put("seed", seed);
        stats.put("reusedBaselineGroups", reused);
        stats.put("deltaNewGroups", delta);
        stats.put("groupsResident", n);
        stats.put("scanRowsResident", 0);
        stats.put("repairPasses", counters.passes);
        stats.put("movesApplied", counters.movesApplied);
        stats.put("swapsApplied", counters.swapsApplied);
        stats.put("escapeKicks", counters.kicks);
        stats.put("globalOptimumGuaranteed", false);
        stats.put("note", "增量沿用父账本分配，仅分层新患者并做末轮交换修复；不保证全局最优");
        return new SolveOutcome(assignment, paths, eval, stats);
    }

    private static void accumulate(CohortModel model, int i, int s,
                                   int[] patientCount, int[][] coverageCount, int delta) {
        patientCount[s] += delta;
        for (int r = 0; r < model.rCount; r++) {
            if (model.member[i][r]) {
                coverageCount[r][s] += delta;
            }
        }
    }

    public static final class Session {
        private final CohortModel model;
        private final int[] assignment;
        private final String[] paths;
        private final int[] patientCount;
        private final int[][] coverageCount;
        private final CohortRng rng;
        private final long seed;
        private int seen = 0;
        private int streamed = 0;
        private int prefilled = 0;

        private Session(Blueprint blueprint, int capacity, long seed) {
            this.model = new CohortModel(blueprint, capacity);
            this.assignment = new int[capacity];
            this.paths = new String[capacity];
            this.patientCount = new int[blueprint.splitCount];
            this.coverageCount = new int[blueprint.rules.size()][blueprint.splitCount];
            this.rng = new CohortRng(seed ^ 0x51A7BEEFL);
            this.seed = seed;
            java.util.Arrays.fill(assignment, -1);
        }

        /** 流式到达一名新患者：绑定紧凑特征并立即分层分配。 */
        public void accept(PatientGroup group) {
            int index = seen++;
            streamed++;
            model.bindGroup(index, group);
            int s = StratifiedPicker.choose(model, index, patientCount, coverageCount, rng, 0.05);
            assignment[index] = s;
            paths[index] = "STRATIFIED";
            countIn(index, s, +1);
        }

        /** 增量场景：预填历史抽取已有的患者分配，不重新决策。 */
        public void prefill(PatientGroup group, int splitIndex, String decisionPath) {
            int index = seen++;
            prefilled++;
            model.bindGroup(index, group);
            assignment[index] = splitIndex;
            paths[index] = decisionPath == null ? "INCREMENTAL" : decisionPath;
            countIn(index, splitIndex, +1);
        }

        public int seen() {
            return seen;
        }

        /** 结果数组下标对应的患者组（持久化时取 uid/扫描数）。 */
        public PatientGroup groupAt(int index) {
            return model.groups[index];
        }

        private void countIn(int i, int s, int delta) {
            patientCount[s] += delta;
            for (int r = 0; r < model.rCount; r++) {
                if (model.member[i][r]) {
                    coverageCount[r][s] += delta;
                }
            }
        }

        /** 末轮交换修复并产出结果。 */
        public SolveOutcome finish() {
            AssignmentOptimizer.Counters counters = new AssignmentOptimizer.Counters();
            AssignmentOptimizer.Eval eval = AssignmentOptimizer.optimize(
                    model, assignment, paths, "SWAP_REPAIR", rng, 30, counters);

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("route", "STREAM_STRATIFIED");
            stats.put("memoryMode", "STREAMING_COMPACT");
            stats.put("seed", seed);
            stats.put("streamedNewGroups", streamed);
            stats.put("prefilledBaselineGroups", prefilled);
            stats.put("groupsResident", seen);
            stats.put("scanRowsResident", 0);
            stats.put("repairPasses", counters.passes);
            stats.put("movesApplied", counters.movesApplied);
            stats.put("swapsApplied", counters.swapsApplied);
            stats.put("escapeKicks", counters.kicks);
            stats.put("globalOptimumGuaranteed", false);
            stats.put("note", "在线分层 + 末轮整患者交换修复，低常驻内存、可增量；不保证全局最优");
            return new SolveOutcome(assignment, paths, eval, stats);
        }
    }
}
