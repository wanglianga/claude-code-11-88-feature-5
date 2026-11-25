package com.lakegov.cohort;

/**
 * 确定性伪随机源（SplitMix64）。
 * 固定种子后，任意 JVM / 任意次运行得到完全相同的序列，保证抽取可复现。
 */
public final class CohortRng {

    private long state;

    public CohortRng(long seed) {
        this.state = seed;
    }

    public long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** [0, bound) 内的确定整数 */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return (int) Math.floorMod(nextLong(), bound);
    }

    /** [0,1) */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** [-amplitude, amplitude] 的确定性抖动 */
    public double jitter(double amplitude) {
        return (nextDouble() * 2.0 - 1.0) * amplitude;
    }

    /** Fisher–Yates 洗牌（确定性） */
    public void shuffle(int[] indices) {
        for (int i = indices.length - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            int tmp = indices[i];
            indices[i] = indices[j];
            indices[j] = tmp;
        }
    }
}
