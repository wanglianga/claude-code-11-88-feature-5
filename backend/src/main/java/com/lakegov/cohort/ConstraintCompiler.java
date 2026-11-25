package com.lakegov.cohort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 约束编译器：把高层抽取规格编译为求解器使用的
 * 硬约束（分区患者数区间、每分区覆盖下限、许可范围、患者不可拆分）
 * 与软目标（配额偏差、覆盖比例目标），并提前给出必不可行预警。
 */
public final class ConstraintCompiler {

    /** 未显式给出容差时，按 eligibleCount 的该比例推导容差。 */
    private static final double DEFAULT_TOLERANCE_RATIO = 0.06;

    private ConstraintCompiler() {
    }

    /**
     * @param spec           原始规格
     * @param eligibleCount  通过纳入规则的可抽取患者数
     * @param featurePools   "DIMENSION=value" → 具备该特征的可抽取患者总数
     */
    public static Blueprint compile(ConstraintSpec spec,
                                     int eligibleCount,
                                     Map<String, Integer> featurePools) {
        if (spec == null || spec.splits() == null || spec.splits().isEmpty()) {
            throw new IllegalArgumentException("约束规格必须至少包含一个分区");
        }
        List<ConstraintSpec.SplitSpec> splitSpecs = spec.splits();
        int sCount = splitSpecs.size();
        List<String> codes = new ArrayList<>();
        double[] ratios = new double[sCount];
        for (int s = 0; s < sCount; s++) {
            ConstraintSpec.SplitSpec sp = splitSpecs.get(s);
            if (sp.code() == null || sp.code().isBlank()) {
                throw new IllegalArgumentException("分区编码不能为空");
            }
            if (codes.contains(sp.code())) {
                throw new IllegalArgumentException("分区编码重复: " + sp.code());
            }
            codes.add(sp.code());
            ratios[s] = sp.targetRatio() == null ? (s == 0 ? 0.8 : 0.2) : sp.targetRatio();
        }

        int[] target = new int[sCount];
        int[] minCnt = new int[sCount];
        int[] maxCnt = new int[sCount];
        for (int s = 0; s < sCount; s++) {
            ConstraintSpec.SplitSpec sp = splitSpecs.get(s);
            if (sp.targetCount() != null) {
                target[s] = sp.targetCount();
            } else {
                target[s] = (int) Math.round(ratios[s] * eligibleCount);
            }
            int tolerance = sp.tolerance() != null
                    ? sp.tolerance()
                    : Math.max(2, (int) Math.round(DEFAULT_TOLERANCE_RATIO * eligibleCount));
            minCnt[s] = sp.minPatients() != null ? sp.minPatients() : target[s] - tolerance;
            maxCnt[s] = sp.maxPatients() != null ? sp.maxPatients() : target[s] + tolerance;
            minCnt[s] = Math.max(0, minCnt[s]);
            maxCnt[s] = Math.max(minCnt[s], maxCnt[s]);
        }

        // 按 dimension+value 聚合规则，split=ALL 展开到全部分区
        Map<String, RuleBuilder> builders = new LinkedHashMap<>();
        if (spec.coverage() != null) {
            for (ConstraintSpec.CoverageRuleSpec cr : spec.coverage()) {
                if (cr.dimension() == null || cr.value() == null) {
                    throw new IllegalArgumentException("覆盖规则必须包含 dimension 与 value");
                }
                String key = cr.dimension() + "=" + cr.value();
                RuleBuilder b = builders.computeIfAbsent(key,
                        k -> new RuleBuilder(cr.dimension(), cr.value(), sCount,
                                cr.weightOrOne()));
                if (Math.abs(b.weight - cr.weightOrOne()) < 1e-12) {
                    b.weight = Math.max(b.weight, cr.weightOrOne());
                }
                boolean all = "ALL".equalsIgnoreCase(cr.splitOrAll());
                for (int s = 0; s < sCount; s++) {
                    if (all || codes.get(s).equalsIgnoreCase(cr.splitOrAll())) {
                        b.min[s] = Math.max(b.min[s], cr.minOrZero());
                        if (cr.target() != null) {
                            b.target[s] = Math.max(b.target[s], cr.target());
                            b.targetExplicit[s] = true;
                        } else if (cr.targetShare() != null) {
                            int pool = poolOf(featurePools, cr.dimension(), cr.value());
                            b.target[s] = Math.max(b.target[s],
                                    (int) Math.round(cr.targetShare() * pool));
                            b.targetExplicit[s] = true;
                        }
                    }
                }
            }
        }

        List<Blueprint.Rule> rules = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (RuleBuilder b : builders.values()) {
            int pool = poolOf(featurePools, b.dimension, b.value);
            // 未显式指定软目标时，按分区目标比例分摊特征池
            for (int s = 0; s < sCount; s++) {
                if (!b.targetExplicit[s]) {
                    b.target[s] = (int) Math.round(ratios[s] * pool);
                }
            }
            rules.add(new Blueprint.Rule(b.dimension, b.value, pool, b.min, b.target, b.weight));
            int minDemand = 0;
            for (int v : b.min) {
                minDemand += v;
            }
            if (minDemand > pool) {
                warnings.add("覆盖硬约束必不可行：" + b.dimension + "=" + b.value
                        + " 各分区最低合计 " + minDemand + " 人，但具备该特征的可抽取患者仅 "
                        + pool + " 人；将报告覆盖缺口，不会拆分患者或跨分区挪扫描。");
            }
        }

        int minSum = 0;
        int maxSum = 0;
        for (int s = 0; s < sCount; s++) {
            minSum += minCnt[s];
            maxSum += maxCnt[s];
        }
        if (minSum > eligibleCount) {
            warnings.add("患者数硬下限合计 " + minSum + " 超过可抽取患者 " + eligibleCount
                    + "，配额无法同时满足。");
        }
        if (maxSum < eligibleCount) {
            warnings.add("患者数硬上限合计 " + maxSum + " 小于可抽取患者 " + eligibleCount
                    + "，无法把全部合格患者分入分区。");
        }

        Blueprint bp = new Blueprint(eligibleCount, List.copyOf(codes), target, minCnt, maxCnt,
                spec.quotaWeightOr(2.0), spec.softCoverageWeightOr(), List.copyOf(rules));
        bp.warnings.clear();
        bp.warnings.addAll(warnings);
        return bp;
    }

    private static int poolOf(Map<String, Integer> pools, String dim, String value) {
        if (pools == null) {
            return 0;
        }
        return pools.getOrDefault(dim + "=" + value, 0);
    }

    private static final class RuleBuilder {
        final String dimension;
        final String value;
        final int[] min;
        final int[] target;
        final boolean[] targetExplicit;
        double weight;

        RuleBuilder(String dimension, String value, int splits, double weight) {
            this.dimension = dimension;
            this.value = value;
            this.min = new int[splits];
            this.target = new int[splits];
            this.targetExplicit = new boolean[splits];
            this.weight = weight;
        }
    }
}
