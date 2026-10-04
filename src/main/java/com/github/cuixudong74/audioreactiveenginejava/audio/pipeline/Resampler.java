package com.github.cuixudong74.audioreactiveenginejava.audio.pipeline;

/**
 * Stateful streaming resampler using deterministic linear interpolation.
 *
 * <p>Output sample k is taken at source position {@code k * sourceRate / targetRate}
 * (linear interpolation between the two neighbouring source samples). The
 * position is computed from integer counters ({@code outputCount * step})
 * rather than accumulated by repeated addition, so the result is bit-identical
 * no matter how the input is split into chunks.</p>
 */
public final class Resampler {

    private final int sourceRate;
    private final int targetRate;
    private final double step;

    /** Number of output samples produced so far. */
    private long outputCount;
    /** Number of input samples consumed so far (before the current block). */
    private long inputConsumed;
    /** Last sample of the previous input block (source position -1 of the current block). */
    private float previous;

    public Resampler(int sourceRate, int targetRate) {
        if (sourceRate <= 0 || targetRate <= 0) {
            throw new IllegalArgumentException("sample rates must be > 0");
        }
        this.sourceRate = sourceRate;
        this.targetRate = targetRate;
        this.step = (double) sourceRate / (double) targetRate;
        reset();
    }

    public int sourceRate() {
        return sourceRate;
    }

    public int targetRate() {
        return targetRate;
    }

    /**
     * Resamples a contiguous block of mono samples.
     *
     * @param input mono samples at {@link #sourceRate()}
     * @return resampled mono samples at {@link #targetRate()}
     */
    public float[] process(float[] input) {
        int n = input.length;
        if (n == 0) {
            return new float[0];
        }
        if (sourceRate == targetRate) {
            outputCount += n;
            inputConsumed += n;
            previous = input[n - 1];
            return input.clone();
        }
        float[] out = new float[Math.max(1, (int) (n / step) + 2)];
        int count = 0;
        while (true) {
            double position = outputCount * step - inputConsumed;
            if (Math.floor(position) >= n - 1) {
                break;
            }
            int i = (int) Math.floor(position);
            double t = position - i;
            float a = (i < 0) ? previous : input[i];
            float b = input[i + 1];
            if (count == out.length) {
                float[] grown = new float[out.length * 2];
                System.arraycopy(out, 0, grown, 0, out.length);
                out = grown;
            }
            out[count++] = (float) (a + t * (b - a));
            outputCount++;
        }
        previous = input[n - 1];
        inputConsumed += n;
        float[] result = new float[count];
        System.arraycopy(out, 0, result, 0, count);
        return result;
    }

    /** Resets the internal streaming state (used on engine restart). */
    public void reset() {
        outputCount = 0;
        inputConsumed = 0;
        previous = 0.0f;
    }
}
