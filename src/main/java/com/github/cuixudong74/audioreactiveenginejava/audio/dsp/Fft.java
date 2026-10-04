package com.github.cuixudong74.audioreactiveenginejava.audio.dsp;

/** Iterative radix-2 FFT, fully deterministic. */
public final class Fft {

    private Fft() {
    }

    /**
     * Computes the magnitude spectrum of a real input signal.
     *
     * @param real input samples, length must be a power of two
     * @return magnitudes of bins 0..N/2 (length N/2 + 1)
     */
    public static float[] magnitudes(float[] real) {
        int n = real.length;
        if (n < 2 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("input length must be a power of two >= 2");
        }
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) {
            re[i] = real[i];
        }
        fftInPlace(re, im);
        float[] out = new float[n / 2 + 1];
        for (int k = 0; k <= n / 2; k++) {
            out[k] = (float) Math.hypot(re[k], im[k]);
        }
        return out;
    }

    /** Iterative radix-2 Cooley-Tukey FFT with bit-reversal permutation. */
    private static void fftInPlace(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wRe = Math.cos(angle);
            double wIm = Math.sin(angle);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0;
                double curIm = 0.0;
                for (int k = 0; k < half; k++) {
                    int a = i + k;
                    int b = a + half;
                    double tRe = re[b] * curRe - im[b] * curIm;
                    double tIm = re[b] * curIm + im[b] * curRe;
                    re[b] = re[a] - tRe;
                    im[b] = im[a] - tIm;
                    re[a] += tRe;
                    im[a] += tIm;
                    double nextRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nextRe;
                }
            }
        }
    }
}
