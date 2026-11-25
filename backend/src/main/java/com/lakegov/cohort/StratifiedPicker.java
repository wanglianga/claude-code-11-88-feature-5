package com.lakegov.cohort;

/**
 * 按当前分层缺口为单个患者组选择分区（流式路线的核心决策，
 * 也被全局路线的贪心构造复用）。
 *
 * 优先级：硬覆盖缺口 urgency ≫ 患者数配额缺口 ≫ 软覆盖目标缺口。
 * 选择只依赖已累计的紧凑计数，因此流式场景无需回看已处理患者。
 */
final class StratifiedPicker {

    private StratifiedPicker() {
    }

    static int choose(CohortModel m, int i, int[] patientCount, int[][] coverageCount,
                      CohortRng rng, double jitterAmplitude) {
        int bestSplit = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int s = 0; s < m.sCount; s++) {
            if (!m.allows(i, s)) {
                continue;
            }
            double hardNeed = 0.0;
            double softNeed = 0.0;
            for (int r = 0; r < m.rCount; r++) {
                if (!m.member[i][r]) {
                    continue;
                }
                Blueprint.Rule rule = m.bp.rules.get(r);
                int hardGap = rule.min[s] - coverageCount[r][s];
                if (hardGap > 0) {
                    hardNeed += hardGap;
                }
                int softGap = rule.target[s] - coverageCount[r][s];
                if (softGap > 0) {
                    softNeed += rule.weight * softGap / Math.max(1.0, rule.target[s]);
                }
            }
            int target = Math.max(1, m.bp.targetCount[s]);
            double quotaNeed = (m.bp.targetCount[s] - patientCount[s]) / (double) target;

            double score = 1000.0 * hardNeed + 10.0 * quotaNeed + softNeed;
            if (patientCount[s] >= m.bp.maxCount[s]) {
                // 硬上限优先不破坏；除非所有许可分区都已满，否则不选
                score -= 1.0e6;
            }
            if (patientCount[s] < m.bp.minCount[s]) {
                score += 50.0;
            }
            if (rng != null && jitterAmplitude > 0) {
                score += rng.jitter(jitterAmplitude);
            }
            if (score > bestScore) {
                bestScore = score;
                bestSplit = s;
            }
        }
        if (bestSplit < 0) {
            // 许可范围异常时回退到第一个分区（校验器会报告许可问题之外的错误）
            bestSplit = 0;
        }
        return bestSplit;
    }
}
