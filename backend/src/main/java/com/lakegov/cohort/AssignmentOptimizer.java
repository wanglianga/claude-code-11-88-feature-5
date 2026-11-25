package com.lakegov.cohort;

import java.util.Arrays;

/**
 * 分配评估与末轮交换修复内核（两条路线共用）。
 *
 * 目标按字典序优化：
 *   1) hardUnits：未满足的硬约束单位数（分区人数区间 + 覆盖下限缺口），必须为 0；
 *   2) scalar：1e6×硬缺口平方 + 配额偏差平方 + 加权软覆盖缺口平方。
 * 邻域只包含“整患者移动 / 两名整患者跨分区交换”，因此永远不会拆分患者组。
 * 严格改进才被接受，保证相同状态下修复路径确定、增量重算不会产生无意义抖动。
 */
public final class AssignmentOptimizer {

    private static final double HARD_PENALTY = 1_000_000.0;
    private static final double EPS = 1e-9;

    private AssignmentOptimizer() {
    }

    public static final class Eval {
        public long hardUnits;
        public double scalar;
        public final int[] patientCount;
        public final int[][] coverageCount;

        Eval(int splitCount, int ruleCount) {
            this.patientCount = new int[splitCount];
            this.coverageCount = new int[ruleCount][splitCount];
        }

        public double softScore;
        public double hardSquare;
    }

    public static final class Counters {
        public int passes;
        public int movesApplied;
        public int swapsApplied;
        public int kicks;
    }

    /** 从当前分配重新统计并评分。a[i] = 分区下标；所有患者必须已分配（>=0）。 */
    public static Eval evaluate(CohortModel m, int[] a) {
        Eval e = new Eval(m.sCount, m.rCount);
        for (int i = 0; i < m.n; i++) {
            int s = a[i];
            if (s < 0) {
                continue;
            }
            e.patientCount[s]++;
            for (int r = 0; r < m.rCount; r++) {
                if (m.member[i][r]) {
                    e.coverageCount[r][s]++;
                }
            }
        }
        score(m, e);
        return e;
    }

    private static void score(CohortModel m, Eval e) {
        long units = 0;
        double hardSq = 0.0;
        for (int s = 0; s < m.sCount; s++) {
            int pc = e.patientCount[s];
            int lo = m.bp.minCount[s];
            int hi = m.bp.maxCount[s];
            int d = 0;
            if (pc < lo) {
                d = lo - pc;
            } else if (pc > hi) {
                d = pc - hi;
            }
            units += d;
            hardSq += (double) d * d;
        }
        for (int r = 0; r < m.rCount; r++) {
            Blueprint.Rule rule = m.bp.rules.get(r);
            for (int s = 0; s < m.sCount; s++) {
                int d = rule.min[s] - e.coverageCount[r][s];
                if (d > 0) {
                    units += d;
                    hardSq += (double) d * d;
                }
            }
        }
        double soft = 0.0;
        for (int s = 0; s < m.sCount; s++) {
            double d = e.patientCount[s] - m.bp.targetCount[s];
            soft += m.bp.quotaWeight * d * d;
        }
        for (int r = 0; r < m.rCount; r++) {
            Blueprint.Rule rule = m.bp.rules.get(r);
            for (int s = 0; s < m.sCount; s++) {
                int shortfall = rule.target[s] - e.coverageCount[r][s];
                if (shortfall > 0) {
                    soft += m.bp.softCoverageWeight * rule.weight
                            * shortfall * shortfall;
                }
            }
        }
        e.hardUnits = units;
        e.hardSquare = hardSq;
        e.softScore = soft;
        e.scalar = soft + HARD_PENALTY * hardSq;
    }

    /** 把患者 i 的贡献从 from 分区转移到 to 分区（试算/回滚共用）。 */
    private static void transfer(CohortModel m, Eval e, int i, int from, int to) {
        e.patientCount[from]--;
        e.patientCount[to]++;
        for (int r = 0; r < m.rCount; r++) {
            if (m.member[i][r]) {
                e.coverageCount[r][from]--;
                e.coverageCount[r][to]++;
            }
        }
    }

    private static boolean better(long units, double scalar, long baseUnits, double baseScalar) {
        if (units != baseUnits) {
            return units < baseUnits;
        }
        return scalar < baseScalar - EPS;
    }

    /**
     * 确定性最佳邻域爬山 + 受限扰动逃逸。
     * 结束时 a/paths 被改写为搜索到的最佳合法分配。
     *
     * @param kickCycles 陷入硬约束局部最优时的扰动轮数（0 表示纯爬山）
     */
    public static Eval optimize(CohortModel m, int[] a, String[] paths, String repairPath,
                                CohortRng rng, int kickCycles, Counters counters) {
        Counters c = counters == null ? new Counters() : counters;
        Eval current = climb(m, a, paths, repairPath, c);

        int[] bestA = a.clone();
        String[] bestP = paths.clone();
        long bestUnits = current.hardUnits;
        double bestScalar = current.scalar;

        for (int kick = 0; kick < kickCycles && current.hardUnits > 0; kick++) {
            deterministicKick(m, a, paths, repairPath, rng, c);
            current = climb(m, a, paths, repairPath, c);
            if (better(current.hardUnits, current.scalar, bestUnits, bestScalar)) {
                bestUnits = current.hardUnits;
                bestScalar = current.scalar;
                bestA = a.clone();
                bestP = paths.clone();
            }
        }

        if (current.hardUnits != bestUnits
                || Math.abs(current.scalar - bestScalar) > EPS) {
            System.arraycopy(bestA, 0, a, 0, a.length);
            System.arraycopy(bestP, 0, paths, 0, paths.length);
            current = evaluate(m, a);
        }
        return current;
    }

    /** 严格最佳改进爬山，直到没有更优的整患者移动/交换。 */
    public static Eval climb(CohortModel m, int[] a, String[] paths, String repairPath,
                              Counters counters) {
        Eval e = evaluate(m, a);
        final int maxPasses = 400;
        for (int pass = 0; pass < maxPasses; pass++) {
            counters.passes++;
            long curUnits = e.hardUnits;
            double curScalar = e.scalar;

            int bestType = 0; // 1=move, 2=swap
            int bi = -1, bj = -1, bs = -1;
            long bestUnits = curUnits;
            double bestScalar = curScalar;

            // ---- 整患者移动 ----
            for (int i = 0; i < m.n; i++) {
                int from = a[i];
                for (int to = 0; to < m.sCount; to++) {
                    if (to == from || !m.allows(i, to)) {
                        continue;
                    }
                    transfer(m, e, i, from, to);
                    score(m, e);
                    boolean improves = better(e.hardUnits, e.scalar, bestUnits, bestScalar);
                    if (improves) {
                        bestUnits = e.hardUnits;
                        bestScalar = e.scalar;
                        bestType = 1;
                        bi = i;
                        bs = to;
                        bj = -1;
                    }
                    transfer(m, e, i, to, from);
                    score(m, e);
                }
            }

            // ---- 两名整患者跨分区交换（患者组不拆分） ----
            for (int i = 0; i < m.n; i++) {
                int si = a[i];
                for (int j = i + 1; j < m.n; j++) {
                    int sj = a[j];
                    if (si == sj) {
                        continue;
                    }
                    if (!m.allows(i, sj) || !m.allows(j, si)) {
                        continue;
                    }
                    transfer(m, e, i, si, sj);
                    transfer(m, e, j, sj, si);
                    score(m, e);
                    if (better(e.hardUnits, e.scalar, bestUnits, bestScalar)) {
                        bestUnits = e.hardUnits;
                        bestScalar = e.scalar;
                        bestType = 2;
                        bi = i;
                        bj = j;
                        bs = -1;
                    }
                    // 精确回滚
                    transfer(m, e, j, si, sj);
                    transfer(m, e, i, sj, si);
                    score(m, e);
                }
            }

            if (bestType == 0) {
                return e;
            }
            if (bestType == 1) {
                int from = a[bi];
                a[bi] = bs;
                paths[bi] = repairPath;
                counters.movesApplied++;
            } else {
                int si = a[bi];
                int sj = a[bj];
                a[bi] = sj;
                a[bj] = si;
                paths[bi] = repairPath;
                paths[bj] = repairPath;
                counters.swapsApplied++;
            }
            e = evaluate(m, a);
        }
        return e;
    }

    /** 确定性扰动：随机做 2~4 次许可合法的跨分区整患者交换。 */
    private static void deterministicKick(CohortModel m, int[] a, String[] paths,
                                          String repairPath, CohortRng rng, Counters c) {
        int swaps = 2 + rng.nextInt(3);
        for (int k = 0; k < swaps; k++) {
            boolean done = false;
            for (int attempt = 0; attempt < 24 && !done; attempt++) {
                int i = rng.nextInt(m.n);
                int j = rng.nextInt(m.n);
                if (i == j) {
                    continue;
                }
                int si = a[i];
                int sj = a[j];
                if (si == sj || !m.allows(i, sj) || !m.allows(j, si)) {
                    continue;
                }
                a[i] = sj;
                a[j] = si;
                paths[i] = repairPath;
                paths[j] = repairPath;
                c.kicks++;
                done = true;
            }
        }
    }

    /** 供测试/调试使用的数组字符串。 */
    public static String distribution(int[] a, int splits) {
        int[] cnt = new int[splits];
        for (int s : a) {
            if (s >= 0) {
                cnt[s]++;
            }
        }
        return Arrays.toString(cnt);
    }
}
