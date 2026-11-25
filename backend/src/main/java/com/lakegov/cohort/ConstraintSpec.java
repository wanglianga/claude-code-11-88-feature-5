package com.lakegov.cohort;

import java.util.List;

/**
 * 约束编译器的输入规格（可由前端 JSON 直接反序列化）。
 *
 * @param splits             分区与患者数目标/容差
 * @param coverage           类别（疾病等级）/设备/协议覆盖规则；min 为硬下限，target 为软目标
 * @param quotaWeight        患者数配额偏差在全局目标中的权重
 * @param softCoverageWeight 软覆盖缺口的统一权重倍率
 */
public record ConstraintSpec(
        List<SplitSpec> splits,
        List<CoverageRuleSpec> coverage,
        Double quotaWeight,
        Double softCoverageWeight
) {

    public double quotaWeightOr(double fallback) {
        return quotaWeight == null ? fallback : quotaWeight;
    }

    public double softCoverageWeightOr() {
        return softCoverageWeight == null ? 1.0 : softCoverageWeight;
    }

    /**
     * @param code         分区编码（TRAIN / VAL）
     * @param targetRatio  目标患者占比（与 targetCount 二选一）
     * @param targetCount  目标患者数（优先）
     * @param minPatients  硬下限患者数
     * @param maxPatients  硬上限患者数
     * @param tolerance    未显式给出 min/max 时，围绕目标数的允许偏差
     */
    public record SplitSpec(
            String code,
            Double targetRatio,
            Integer targetCount,
            Integer minPatients,
            Integer maxPatients,
            Integer tolerance
    ) {
    }

    /**
     * 一条覆盖规则。split="ALL" 表示对每个分区分别生效。
     *
     * @param dimension   DISEASE_GRADE / DEVICE / PROTOCOL
     * @param value       维度取值
     * @param split       分区编码或 ALL
     * @param min         每分区硬下限（不满足则校验失败并报告缺口）
     * @param target      每分区软目标（显式患者数）
     * @param targetShare 软目标占该特征可抽取患者池的比例（与 target 二选一）
     * @param weight      软目标权重
     */
    public record CoverageRuleSpec(
            String dimension,
            String value,
            String split,
            Integer min,
            Integer target,
            Double targetShare,
            Double weight
    ) {
        public String splitOrAll() {
            return (split == null || split.isBlank()) ? "ALL" : split;
        }

        public int minOrZero() {
            return min == null ? 0 : Math.max(0, min);
        }

        public double weightOrOne() {
            return weight == null ? 1.0 : Math.max(0.0, weight);
        }
    }
}
