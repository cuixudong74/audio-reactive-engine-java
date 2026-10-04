package com.github.cuixudong74.audioreactiveenginejava.audio;

import com.github.cuixudong74.audioreactiveenginejava.audio.config.WindowType;
import com.github.cuixudong74.audioreactiveenginejava.audio.dsp.Fft;
import com.github.cuixudong74.audioreactiveenginejava.audio.dsp.Resampler;
import com.github.cuixudong74.audioreactiveenginejava.audio.dsp.StreamResampler;
import com.github.cuixudong74.audioreactiveenginejava.audio.dsp.WindowFunction;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DspTest {

    @Test
    void windowCoefficientsAreDeterministicAndSymmetric() {
        for (WindowType type : WindowType.values()) {
            float[] a = WindowFunction.coefficients(type, 256);
            float[] b = WindowFunction.coefficients(type, 256);
            assertArrayEquals(a, b);
            for (int i = 0; i < 128; i++) {
                assertEquals(a[i], a[255 - i], 1e-7, "symmetry at " + i);
            }
        }
        float[] hann = WindowFunction.coefficients(WindowType.HANN, 256);
        assertEquals(0.0, hann[0], 1e-7);
        assertEquals(1.0, hann[128], 1e-2);
    }

    @Test
    void fftFindsSinePeakAtExpectedBin() {
        int n = 1024;
        int bin = 40;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = (float) Math.sin(2 * Math.PI * bin * i / n);
        }
        float[] mag = Fft.magnitudes(x);
        assertEquals(n / 2 + 1, mag.length);
        int argmax = 0;
        for (int k = 1; k < mag.length; k++) {
            if (mag[k] > mag[argmax]) {
                argmax = k;
            }
        }
        assertEquals(bin, argmax);
        assertEquals(n / 2.0, mag[bin], n * 0.01);
    }

    @Test
    void fftOfImpulseIsFlat() {
        float[] x = new float[64];
        x[0] = 1.0f;
        float[] mag = Fft.magnitudes(x);
        for (float m : mag) {
            assertEquals(1.0, m, 1e-5);
        }
    }

    @Test
    void resamplerRejectsInvalidRates() {
        assertThrows(IllegalArgumentException.class,
                () -> Resampler.resampleLinear(new float[4], 0, 100));
    }

    @Test
    void streamResamplerMatchesWholeStreamResamplerForAnyChunking() {
        Random rnd = new Random(42);
        float[] src = new float[5000];
        for (int i = 0; i < src.length; i++) {
            src[i] = rnd.nextFloat() * 2 - 1;
        }
        int[][] ratePairs = {{48000, 44100}, {44100, 48000}, {22050, 44100}, {44100, 44100}};
        int[] chunkSizes = {1, 7, 100, 999, 5000};
        for (int[] rates : ratePairs) {
            float[] expected = Resampler.resampleLinear(src, rates[0], rates[1]);
            for (int chunk : chunkSizes) {
                StreamResampler sr = new StreamResampler(rates[0], rates[1]);
                float[] actual = new float[expected.length];
                int pos = 0;
                int outPos = 0;
                while (pos < src.length) {
                    int n = Math.min(chunk, src.length - pos);
                    float[] out = sr.feed(src, pos, n, false);
                    System.arraycopy(out, 0, actual, outPos, out.length);
                    outPos += out.length;
                    pos += n;
                }
                float[] tail = sr.feed(src, 0, 0, true);
                System.arraycopy(tail, 0, actual, outPos, tail.length);
                outPos += tail.length;
                assertEquals(expected.length, outPos,
                        "rates " + rates[0] + "->" + rates[1] + " chunk " + chunk);
                assertArrayEquals(expected, actual, 1e-6f,
                        "rates " + rates[0] + "->" + rates[1] + " chunk " + chunk);
            }
        }
    }
}
