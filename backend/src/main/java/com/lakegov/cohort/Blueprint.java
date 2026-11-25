package com.lakegov.cohort;

import java.util.ArrayList;
import java.util.List;

/**
 * 编译后的约束蓝图：分区患者数边界 + 覆盖规则的按分区 min/target 数组。
 * 同时携带编译器给出的必不可行预警（硬下限总需求超过可抽取池）。
 */
public final class Blueprint {

    public final int eligibleCount;
    public final List<String> splitCodes;
    public final int splitCount;

    public final int[] targetCount;
    public final int[] minCount;
    public final int[] maxCount;
    public final double quotaWeight;
    public final double softCoverageWeight;

    public final List<Rule> rules;
    public final List<String> warnings = new ArrayList<>();

    public Blueprint(int eligibleCount,
                     List<String> splitCodes,
                     int[] targetCount,
                     int[] minCount,
                     int[] maxCount,
                     double quotaWeight,
                     double softCoverageWeight,
                     List<Rule> rules) {
        this.eligibleCount = eligibleCount;
        this.splitCodes = splitCodes;
        this.splitCount = splitCodes.size();
        this.targetCount = targetCount;
        this.minCount = minCount;
        this.maxCount = maxCount;
        this.quotaWeight = quotaWeight;
        this.softCoverageWeight = softCoverageWeight;
        this.rules = rules;
    }

    /** 一条维度覆盖规则；min/target 下标对应 splitCodes。 */
    public static final class Rule {
        public final String dimension;
        public final String value;
        public final int featurePool;
        public final int[] min;
        public final int[] target;
        public final double weight;

        public Rule(String dimension, String value, int featurePool,
                    int[] min, int[] target, double weight) {
            this.dimension = dimension;
            this.value = value;
            this.featurePool = featurePool;
            this.min = min;
            this.target = target;
            this.weight = weight;
        }

        public String key() {
            return dimension + "=" + value;
        }
    }

    /** 面向前端/账本的可序列化编译结果。 */
    public CompiledView toView() {
        List<SplitBound> splitViews = new ArrayList<>();
        for (int s = 0; s < splitCount; s++) {
            double ratio = eligibleCount == 0 ? 0 : (double) targetCount[s] / eligibleCount;
            splitViews.add(new SplitBound(splitCodes.get(s), targetCount[s],
                    minCount[s], maxCount[s], Math.round(ratio * 1000.0) / 1000.0));
        }
        List<CoverageBound> ruleViews = new ArrayList<>();
        for (Rule r : rules) {
            List<Integer> mins = new ArrayList<>();
            List<Integer> targets = new ArrayList<>();
            for (int s = 0; s < splitCount; s++) {
                mins.add(r.min[s]);
                targets.add(r.target[s]);
            }
            ruleViews.add(new CoverageBound(r.dimension, r.value, r.featurePool,
                    mins, targets, r.weight));
        }
        return new CompiledView(eligibleCount, splitCodes, splitViews, ruleViews,
                List.copyOf(warnings), quotaWeight);
    }

    public record CompiledView(
            int eligiblePatients,
            List<String> splitCodes,
            List<SplitBound> splits,
            List<CoverageBound> coverage,
            List<String> feasibilityWarnings,
            double quotaWeight
    ) {
    }

    public record SplitBound(String code, int targetPatients, int minPatients,
                             int maxPatients, double targetRatio) {
    }

    public record CoverageBound(String dimension, String value, int featurePool,
                                List<Integer> min, List<Integer> target, double weight) {
    }
}
