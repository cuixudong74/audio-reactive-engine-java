package com.github.cuixudong74.audioreactiveenginejava.audio.dsp;

import com.github.cuixudong74.audioreactiveenginejava.audio.config.WindowType;

/** Deterministic window-function coefficient tables. */
public final class WindowFunction {

    private WindowFunction() {
    }

    /**
     * Returns the coefficient table for the given window type and size.
     * Coefficients depend only on (type, size), so results are reproducible.
     */
    public static float[] coefficients(WindowType type, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        float[] w = new float[size];
        if (size == 1) {
            w[0] = 1.0f;
            return w;
        }
        // Symmetric windows, denominator (size - 1): the same table is used
        // for analysis only, so perfect reconstruction is not required and a
        // symmetric window keeps boundary behaviour simple and documented.
        double denom = size - 1.0;
        for (int i = 0; i < size; i++) {
            double phase = 2.0 * Math.PI * i / denom;
            w[i] = (float) switch (type) {
                case RECTANGULAR -> 1.0;
                case HANN -> 0.5 - 0.5 * Math.cos(phase);
                case HAMMING -> 0.54 - 0.46 * Math.cos(phase);
                case BLACKMAN -> 0.42 - 0.5 * Math.cos(phase) + 0.08 * Math.cos(2.0 * phase);
            };
        }
        return w;
    }
}
