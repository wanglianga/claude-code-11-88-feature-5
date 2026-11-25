package com.lakegov.cohort;

/**
 * 抽取请求。spec 为空时使用 profileCode 指向的约束画像；
 * incrementalFromRunId 仅允许与 STREAM_STRATIFIED 路线一起使用。
 */
public record RunRequest(
        String strategy,
        Long seed,
        String profileCode,
        ConstraintSpec spec,
        Long incrementalFromRunId,
        String runName
) {
}
