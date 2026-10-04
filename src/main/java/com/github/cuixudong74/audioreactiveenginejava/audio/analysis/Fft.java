package com.github.cuixudong74.audioreactiveenginejava.audio.analysis;

/**
 * Deterministic iterative radix-2 Cooley-Tukey FFT. Size must be a power of
 * two. All arithmetic is done in a fixed order with double precision, so the
 * same input always produces bit-identical output.
 */
public final class Fft {

    private final int size;
    private final int[] bitReversal;

    public Fft(int size) {
        if (size <= 0 || (size & (size - 1)) != 0) {
            throw new IllegalArgumentException("FFT size must be a positive power of two");
        }
        this.size = size;
        this.bitReversal = new int[size];
        int bits = Integer.numberOfTrailingZeros(size);
        for (int i = 0; i < size; i++) {
            bitReversal[i] = Integer.reverse(i) >>> (32 - bits);
        }
    }

    public int size() {
        return size;
    }

    /**
     * Computes the magnitude spectrum of a real signal.
     *
     * @param samples time-domain samples, length must equal {@link #size()}
     * @return magnitudes of bins 0..size/2 (length size/2 + 1)
     */
    public float[] magnitudeSpectrum(float[] samples) {
        if (samples.length != size) {
            throw new IllegalArgumentException("sample length " + samples.length + " != FFT size " + size);
        }
        double[] re = new double[size];
        double[] im = new double[size];
        for (int i = 0; i < size; i++) {
            re[bitReversal[i]] = samples[i];
        }
        for (int len = 2; len <= size; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wRe = Math.cos(angle);
            double wIm = Math.sin(angle);
            int half = len >> 1;
            for (int start = 0; start < size; start += len) {
                double curRe = 1.0;
                double curIm = 0.0;
                for (int k = 0; k < half; k++) {
                    int even = start + k;
                    int odd = start + k + half;
                    double tRe = re[odd] * curRe - im[odd] * curIm;
                    double tIm = re[odd] * curIm + im[odd] * curRe;
                    re[odd] = re[even] - tRe;
                    im[odd] = im[even] - tIm;
                    re[even] += tRe;
                    im[even] += tIm;
                    double nextRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nextRe;
                }
            }
        }
        float[] magnitudes = new float[size / 2 + 1];
        for (int k = 0; k <= size / 2; k++) {
            magnitudes[k] = (float) Math.hypot(re[k], im[k]);
        }
        return magnitudes;
    }
}
