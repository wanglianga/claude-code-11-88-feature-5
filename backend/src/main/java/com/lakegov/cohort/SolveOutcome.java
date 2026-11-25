package com.lakegov.cohort;

import java.util.LinkedHashMap;
import java.util.Map;

/** 求解器输出：分区下标分配、决策路径、最终评分与统计。 */
public final class SolveOutcome {

    public final int[] assignment;
    public final String[] paths;
    public final AssignmentOptimizer.Eval eval;
    public final Map<String, Object> stats;

    public SolveOutcome(int[] assignment, String[] paths,
                        AssignmentOptimizer.Eval eval, Map<String, Object> stats) {
        this.assignment = assignment;
        this.paths = paths;
        this.eval = eval;
        this.stats = stats == null ? new LinkedHashMap<>() : stats;
    }
}
