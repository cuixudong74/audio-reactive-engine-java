package com.github.cuixudong74.audioreactiveenginejava.audio.dsp;

import java.util.Arrays;

/**
 * Deterministic linear-interpolation resampler operating on a continuous
 * mono stream. Because it runs on the reassembled continuous stream (not on
 * individual chunks), its output is independent of how the input was chunked.
 */
public final class Resampler {

    private Resampler() {
    }

    /**
     * Resamples {@code in} from {@code srcRate} to {@code dstRate}.
     * Output length is {@code floor(in.length * dstRate / srcRate)}.
     */
    public static float[] resampleLinear(float[] in, int srcRate, int dstRate) {
        if (srcRate <= 0 || dstRate <= 0) {
            throw new IllegalArgumentException("rates must be > 0");
        }
        if (srcRate == dstRate || in.length == 0) {
            return in.length == 0 ? new float[0] : Arrays.copyOf(in, in.length);
        }
        // Output sample i maps to source position i * srcRate / dstRate.
        // Positions are computed from absolute indices only, so the result
        // does not depend on how the stream was chunked.
        long outLen = (long) in.length * dstRate / srcRate;
        float[] out = new float[(int) outLen];
        for (int i = 0; i < out.length; i++) {
            double pos = (double) i * srcRate / dstRate;
            int idx = (int) pos;
            double frac = pos - idx;
            int next = Math.min(idx + 1, in.length - 1);
            out[i] = (float) (in[idx] * (1.0 - frac) + in[next] * frac);
        }
        return out;
    }
}
