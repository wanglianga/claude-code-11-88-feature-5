package com.lakegov.cohort;

import java.util.List;

/**
 * 求解器内存模型：蓝图 + 患者紧凑组 + 患者×覆盖规则成员矩阵。
 * 全局路线一次性构建完整模型；流式路线每到达一个患者增量填充。
 */
public final class CohortModel {

    public final Blueprint bp;
    public final int n;
    public final int sCount;
    public final int rCount;

    public final PatientGroup[] groups;
    /** member[i][r] = 患者 i 是否具备规则 r 对应的维度特征。 */
    public final boolean[][] member;

    public CohortModel(Blueprint bp, List<PatientGroup> groupList) {
        this.bp = bp;
        this.n = groupList.size();
        this.sCount = bp.splitCount;
        this.rCount = bp.rules.size();
        this.groups = groupList.toArray(new PatientGroup[0]);
        this.member = new boolean[n][rCount];
        for (int i = 0; i < n; i++) {
            PatientGroup g = groups[i];
            for (int r = 0; r < rCount; r++) {
                Blueprint.Rule rule = bp.rules.get(r);
                member[i][r] = g.hasFeature(rule.dimension, rule.value);
            }
        }
    }

    /** 流式增量构造：使用预分配数组。 */
    public CohortModel(Blueprint bp, int capacity) {
        this.bp = bp;
        this.n = capacity;
        this.sCount = bp.splitCount;
        this.rCount = bp.rules.size();
        this.groups = new PatientGroup[capacity];
        this.member = new boolean[capacity][rCount];
    }

    public void bindGroup(int index, PatientGroup g) {
        groups[index] = g;
        for (int r = 0; r < rCount; r++) {
            Blueprint.Rule rule = bp.rules.get(r);
            member[index][r] = g.hasFeature(rule.dimension, rule.value);
        }
    }

    public int splitIndex(String code) {
        return bp.splitCodes.indexOf(code);
    }

    /** 许可范围硬约束：患者 i 是否允许进入分区 s。 */
    public boolean allows(int i, int s) {
        return groups[i].allowedSplits.contains(bp.splitCodes.get(s));
    }
}
