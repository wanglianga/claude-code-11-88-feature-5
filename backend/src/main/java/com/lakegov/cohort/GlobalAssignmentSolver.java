package com.lakegov.cohort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路线 A：一次性带约束整数分配的全局求解。
 *
 * 把全部患者组载入内存，显式构建 0-1 分配模型 x[i,s]：
 *   - 每名患者恰好进入一个许可分区（患者不可拆、许可范围）；
 *   - 分区人数区间、每分区类别/设备/协议下限为硬约束；
 *   - 配额偏差与覆盖目标为加权软目标。
 * 采用“稀缺特征优先的可行构造 + 全邻域最佳交换/移动寻优 + 确定性多起点”，
 * 一次性优化全局覆盖。组合规模随患者数平方级增长、目录变更后需整体重算，
 * 这是该路线相对流式路线的主要成本。
 */
public final class GlobalAssignmentSolver {

    /** 多起点数量（第 0 个为无随机抖动的纯贪心）。 */
    private static final int RESTARTS = 8;

    public SolveOutcome solve(CohortModel model, long seed) {
        CohortRng rng = new CohortRng(seed ^ 0x67A5E8D211L);
        int n = model.n;

        int[] specialistCount = new int[n];
        for (int i = 0; i < n; i++) {
            int k = 0;
            for (int r = 0; r < model.rCount; r++) {
                if (model.member[i][r]) {
                    k++;
                }
            }
            specialistCount[i] = k;
        }

        int[] bestA = null;
        String[] bestPaths = null;
        AssignmentOptimizer.Eval bestEval = null;
        int bestRestart = 0;
        AssignmentOptimizer.Counters total = new AssignmentOptimizer.Counters();

        for (int restart = 0; restart < RESTARTS; restart++) {
            int[] a = construct(model, rng, restart, specialistCount);
            String[] paths = new String[n];
            for (int i = 0; i < n; i++) {
                paths[i] = "GLOBAL_INIT";
            }
            AssignmentOptimizer.Counters c = new AssignmentOptimizer.Counters();
            AssignmentOptimizer.Eval eval = AssignmentOptimizer.optimize(
                    model, a, paths, "GLOBAL_OPTIMIZE", rng, 16, c);
            total.passes += c.passes;
            total.movesApplied += c.movesApplied;
            total.swapsApplied += c.swapsApplied;
            total.kicks += c.kicks;

            if (bestEval == null
                    || eval.hardUnits < bestEval.hardUnits
                    || (eval.hardUnits == bestEval.hardUnits
                        && eval.scalar < bestEval.scalar - 1e-9)) {
                bestEval = eval;
                bestA = a.clone();
                bestPaths = paths.clone();
                bestRestart = restart;
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("route", "GLOBAL_ASSIGN");
        stats.put("memoryMode", "FULL_GROUP_MODEL");
        stats.put("restarts", RESTARTS);
        stats.put("bestRestart", bestRestart);
        stats.put("repairPasses", total.passes);
        stats.put("movesApplied", total.movesApplied);
        stats.put("swapsApplied", total.swapsApplied);
        stats.put("escapeKicks", total.kicks);
        stats.put("groupsResident", n);
        stats.put("scanRowsResident", 0);
        stats.put("neighborhood", "whole-patient moves + cross-split swaps");
        return new SolveOutcome(bestA, bestPaths, bestEval, stats);
    }

    /** 稀缺硬约束优先播种，再按分层缺口填充剩余患者。 */
    private int[] construct(CohortModel m, CohortRng rng, int restart, int[] specialistCount) {
        int n = m.n;
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = -1;
        }
        int[] pc = new int[m.sCount];
        int[][] cc = new int[m.rCount][m.sCount];

        record Req(int rule, int split, int min) {
        }
        List<Req> reqs = new ArrayList<>();
        for (int r = 0; r < m.rCount; r++) {
            Blueprint.Rule rule = m.bp.rules.get(r);
            for (int s = 0; s < m.sCount; s++) {
                if (rule.min[s] > 0) {
                    reqs.add(new Req(r, s, rule.min[s]));
                }
            }
        }
        // 特征池越小、下限越高，越先保证（稀有协议优先）
        reqs.sort(Comparator.comparingInt((Req q) -> m.bp.rules.get(q.rule).featurePool)
                .thenComparing((q1, q2) -> Integer.compare(q2.min, q1.min)));

        double jitter = restart == 0 ? 0.0 : 2.5;
        for (Req req : reqs) {
            int r = req.rule;
            int s = req.split;
            List<Integer> candidates = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (a[i] == -1 && m.member[i][r] && m.allows(i, s)) {
                    candidates.add(i);
                }
            }
            // 排序键必须对同一患者固定：先预计算抖动键，再排序，
            // 不能在 compare 中调用随机器（会破坏比较器传递性契约）
            double[] sortKey = new double[n];
            for (int i : candidates) {
                sortKey[i] = specialistCount[i]
                        + (jitter == 0.0 ? 0.0 : rng.jitter(jitter));
            }
            candidates.sort(Comparator.comparingDouble(i -> sortKey[i]));
            for (int i : candidates) {
                if (cc[r][s] >= req.min) {
                    break;
                }
                a[i] = s;
                pc[s]++;
                for (int rr = 0; rr < m.rCount; rr++) {
                    if (m.member[i][rr]) {
                        cc[rr][s]++;
                    }
                }
            }
        }

        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (a[i] == -1) {
                remaining.add(i);
            }
        }
        if (restart > 0) {
            int[] order = remaining.stream().mapToInt(Integer::intValue).toArray();
            rng.shuffle(order);
            remaining = new ArrayList<>();
            for (int i : order) {
                remaining.add(i);
            }
        }
        double pickJitter = restart == 0 ? 0.0 : 0.08;
        for (int i : remaining) {
            int s = StratifiedPicker.choose(m, i, pc, cc, rng, pickJitter);
            a[i] = s;
            pc[s]++;
            for (int r = 0; r < m.rCount; r++) {
                if (m.member[i][r]) {
                    cc[r][s]++;
                }
            }
        }
        return a;
    }
}
