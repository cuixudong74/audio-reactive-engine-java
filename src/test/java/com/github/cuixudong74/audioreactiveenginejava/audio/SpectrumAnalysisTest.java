package com.github.cuixudong74.audioreactiveenginejava.audio;

import com.github.cuixudong74.audioreactiveenginejava.audio.analysis.Fft;
import com.github.cuixudong74.audioreactiveenginejava.audio.analysis.SpectrumAnalyzer;
import com.github.cuixudong74.audioreactiveenginejava.audio.analysis.WindowFunction;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AnalysisConfig;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SpectrumFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectrumAnalysisTest {

    private static AnalysisConfig config(int windowSize, double overlap) {
        return new AnalysisConfig(0, 8000, windowSize, AnalysisConfig.WindowType.HANN, overlap,
                1.0e-6, 1.5, 1.0, 2.0, 60, AnalysisConfig.GapPolicy.FILL_SILENCE,
                1 << 16, 64, 256);
    }

    @Test
    void fftFindsSinePeakAtExpectedBin() {
        int size = 256;
        int bin = 8; // exact bin frequency: no spectral leakage
        float[] samples = new float[size];
        for (int i = 0; i < size; i++) {
            samples[i] = (float) Math.sin(2.0 * Math.PI * bin * i / size);
        }
        float[] mags = new Fft(size).magnitudeSpectrum(samples);
        assertEquals(size / 2 + 1, mags.length);
        int peak = 0;
        for (int i = 1; i < mags.length; i++) {
            if (mags[i] > mags[peak]) {
                peak = i;
            }
        }
        assertEquals(bin, peak);
        assertEquals(size / 2.0, mags[bin], size * 0.01);
    }

    @Test
    void fftIsDeterministic() {
        int size = 128;
        float[] samples = new float[size];
        Random random = new Random(3);
        for (int i = 0; i < size; i++) {
            samples[i] = random.nextFloat() * 2 - 1;
        }
        Fft fft = new Fft(size);
        assertArrayEquals(fft.magnitudeSpectrum(samples), fft.magnitudeSpectrum(samples));
    }

    @Test
    void windowBoundaryCoefficientsAreDeterministic() {
        // Hann and Blackman go exactly to zero at both ends; Hamming ends at
        // 0.08 by definition. All are symmetric.
        for (AnalysisConfig.WindowType type : List.of(AnalysisConfig.WindowType.HANN,
                AnalysisConfig.WindowType.HAMMING, AnalysisConfig.WindowType.BLACKMAN)) {
            WindowFunction w = WindowFunction.of(type, 256);
            float[] ones = new float[256];
            java.util.Arrays.fill(ones, 1.0f);
            w.applyInPlace(ones);
            double expectedEdge = type == AnalysisConfig.WindowType.HAMMING ? 0.08 : 0.0;
            assertEquals(expectedEdge, ones[0], 1e-6, "first coefficient of " + type);
            assertEquals(expectedEdge, ones[255], 1e-6, "last coefficient of " + type);
            for (int i = 0; i < 128; i++) {
                assertEquals(ones[i], ones[255 - i], 1e-6, "symmetry of " + type);
            }
        }
    }

    @Test
    void frameTimesAndIndicesFollowHopGrid() {
        AnalysisConfig cfg = config(256, 0.5); // hop = 128
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(cfg);
        float[] samples = new float[256 * 4];
        java.util.Arrays.fill(samples, 0.5f);
        List<SpectrumFrame> frames = analyzer.process(samples);
        assertEquals(7, frames.size()); // floor((1024-256)/128)+1
        for (int i = 0; i < frames.size(); i++) {
            assertEquals(i, frames.get(i).frameIndex());
            assertEquals(i * 128 / 8000.0, frames.get(i).timeSeconds(), 1e-12);
        }
    }

    @Test
    void chunkingDoesNotChangeFrames() {
        AnalysisConfig cfg = config(256, 0.5);
        float[] signal = new float[5000];
        Random random = new Random(9);
        for (int i = 0; i < signal.length; i++) {
            signal[i] = (float) (Math.sin(i * 0.05) + 0.1 * random.nextGaussian());
        }
        List<SpectrumFrame> oneShot = new SpectrumAnalyzer(cfg).process(signal);

        SpectrumAnalyzer chunked = new SpectrumAnalyzer(cfg);
        List<SpectrumFrame> all = new ArrayList<>();
        int pos = 0;
        Random split = new Random(4);
        while (pos < signal.length) {
            int n = Math.min(1 + split.nextInt(700), signal.length - pos);
            float[] piece = new float[n];
            System.arraycopy(signal, pos, piece, 0, n);
            all.addAll(chunked.process(piece));
            pos += n;
        }
        assertEquals(oneShot.size(), all.size());
        for (int i = 0; i < oneShot.size(); i++) {
            assertEquals(oneShot.get(i).frameIndex(), all.get(i).frameIndex());
            assertEquals(oneShot.get(i).timeSeconds(), all.get(i).timeSeconds(), 0.0);
            assertArrayEquals(oneShot.get(i).magnitudes(), all.get(i).magnitudes());
            assertEquals(oneShot.get(i).rmsEnergy(), all.get(i).rmsEnergy(), 0.0);
        }
    }

    @Test
    void partialTrailingWindowIsHeldBack() {
        AnalysisConfig cfg = config(256, 0.5);
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(cfg);
        assertTrue(analyzer.process(new float[255]).isEmpty());
        assertEquals(255, analyzer.bufferedSamples());
        assertEquals(1, analyzer.process(new float[1]).size());
    }

    @Test
    void carriedStateKeepsTimelineContinuous() {
        AnalysisConfig cfg256 = config(256, 0.5);
        AnalysisConfig cfg512 = config(512, 0.5);
        float[] signal = new float[4000];
        for (int i = 0; i < signal.length; i++) {
            signal[i] = (float) Math.sin(i * 0.03);
        }
        SpectrumAnalyzer first = new SpectrumAnalyzer(cfg256);
        List<SpectrumFrame> framesA = new ArrayList<>(first.process(new float[]{}));
        framesA.addAll(first.process(java.util.Arrays.copyOfRange(signal, 0, 1000)));
        // Hot update: carry state into a new analyzer with a different window size.
        SpectrumAnalyzer second = new SpectrumAnalyzer(cfg512, first.nextFrameIndex(),
                first.nextFrameStartSample(), first.unconsumedSamples());
        List<SpectrumFrame> framesB = new ArrayList<>();
        framesB.addAll(second.process(java.util.Arrays.copyOfRange(signal, 1000, signal.length)));

        long lastIndex = -1;
        double lastTime = -1;
        for (SpectrumFrame f : concat(framesA, framesB)) {
            assertEquals(lastIndex + 1, f.frameIndex());
            assertTrue(f.timeSeconds() > lastTime, "time must strictly increase");
            lastIndex = f.frameIndex();
            lastTime = f.timeSeconds();
        }
        // First frame after the switch starts exactly where the old analyzer stopped.
        assertEquals(framesA.get(framesA.size() - 1).frameIndex() + 1, framesB.get(0).frameIndex());
    }

    private static List<SpectrumFrame> concat(List<SpectrumFrame> a, List<SpectrumFrame> b) {
        List<SpectrumFrame> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
