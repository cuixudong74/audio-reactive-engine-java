package com.github.cuixudong74.audioreactiveenginejava.audio.analysis;

import com.github.cuixudong74.audioreactiveenginejava.audio.model.AnalysisConfig;

/**
 * Window functions applied before the FFT. Coefficients are precomputed once
 * per (type, size) pair in double precision, so results are deterministic
 * and identical across runs.
 *
 * <p>All windows use the symmetric form {@code w[n] = f(n / (N - 1))} so the
 * first and last coefficients of non-rectangular windows are exactly zero;
 * samples at the window boundary therefore contribute nothing, which makes
 * the boundary behaviour well defined (see docs).</p>
 */
public final class WindowFunction {

    private final float[] coefficients;

    private WindowFunction(float[] coefficients) {
        this.coefficients = coefficients;
    }

    public static WindowFunction of(AnalysisConfig.WindowType type, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        float[] w = new float[size];
        switch (type) {
            case RECTANGLE -> java.util.Arrays.fill(w, 1.0f);
            case HANN -> {
                for (int n = 0; n < size; n++) {
                    w[n] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / (size - 1.0)));
                }
            }
            case HAMMING -> {
                for (int n = 0; n < size; n++) {
                    w[n] = (float) (0.54 - 0.46 * Math.cos(2.0 * Math.PI * n / (size - 1.0)));
                }
            }
            case BLACKMAN -> {
                for (int n = 0; n < size; n++) {
                    double x = 2.0 * Math.PI * n / (size - 1.0);
                    w[n] = (float) (0.42 - 0.5 * Math.cos(x) + 0.08 * Math.cos(2.0 * x));
                }
            }
            default -> throw new IllegalArgumentException("unknown window type: " + type);
        }
        return new WindowFunction(w);
    }

    public int size() {
        return coefficients.length;
    }

    /** Applies the window in place. Length must equal {@link #size()}. */
    public void applyInPlace(float[] samples) {
        if (samples.length != coefficients.length) {
            throw new IllegalArgumentException("sample length " + samples.length
                    + " != window size " + coefficients.length);
        }
        for (int i = 0; i < samples.length; i++) {
            samples[i] *= coefficients[i];
        }
    }
}
