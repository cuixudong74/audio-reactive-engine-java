package com.github.cuixudong74.audioreactiveenginejava.audio;

import com.github.cuixudong74.audioreactiveenginejava.audio.analysis.OnsetDetector;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AnalysisConfig;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.BeatEvent;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SpectrumFrame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnsetDetectorTest {

    private static final int BINS = 129; // windowSize 256 -> 256/2+1
    private static final double BASE = 1.0;

    private static AnalysisConfig config() {
        return new AnalysisConfig(0, 8000, 256, AnalysisConfig.WindowType.HANN, 0.5,
                1.0e-4, 1.5, 1.0, 2.0, 60, AnalysisConfig.GapPolicy.FILL_SILENCE,
                1 << 16, 64, 256);
    }

    private static SpectrumFrame frame(long index, double magnitude, double rms) {
        float[] mags = new float[BINS];
        java.util.Arrays.fill(mags, (float) magnitude);
        return new SpectrumFrame(index, index * 128 / 8000.0, mags, rms, 0);
    }

    /** Magnitude whose jump from {@code from} yields the given total flux. */
    private static double levelForFlux(double from, double flux) {
        return Math.exp(Math.log1p(from) + flux / BINS) - 1.0;
    }

    /**
     * Establishes an adaptive threshold well above the floor: alternates the
     * level so each up-frame contributes flux ~6.2 (mean ~3.1, threshold
     * ~4.7), then feeds steady frames so any refractory period expires.
     * Returns the index of the next frame to feed.
     */
    private static long buildAdaptiveHistory(OnsetDetector detector) {
        long i = 0;
        for (; i < 40; i++) {
            detector.process(frame(i, (i % 2 == 0) ? BASE : BASE * 1.1, 0.5));
        }
        for (int k = 0; k < 6; k++, i++) {
            detector.process(frame(i, BASE, 0.5));
        }
        return i;
    }

    @Test
    void strongJumpProducesBeatEvent() {
        OnsetDetector detector = new OnsetDetector(config());
        long i = buildAdaptiveHistory(detector);
        List<BeatEvent> events = detector.process(frame(i, levelForFlux(BASE, 70.0), 0.5));
        assertEquals(1, events.size());
        assertEquals(BeatEvent.Kind.BEAT, events.get(0).kind());
        assertTrue(events.get(0).strength() >= 2.0);
        assertEquals(i * 128 / 8000.0, events.get(0).timeSeconds(), 0.0);
    }

    @Test
    void moderateJumpProducesWeakOnset() {
        OnsetDetector detector = new OnsetDetector(config());
        long i = buildAdaptiveHistory(detector);
        // flux 6.0 vs adaptive threshold ~4.7 -> strength ~1.3 -> weak ONSET.
        List<BeatEvent> events = detector.process(frame(i, levelForFlux(BASE, 6.0), 0.5));
        assertEquals(1, events.size());
        assertEquals(BeatEvent.Kind.ONSET, events.get(0).kind());
        assertTrue(events.get(0).strength() >= 1.0 && events.get(0).strength() < 2.0);
    }

    @Test
    void jumpBelowAdaptiveThresholdProducesNoEvent() {
        OnsetDetector detector = new OnsetDetector(config());
        long i = buildAdaptiveHistory(detector);
        // flux 3.0 < threshold ~4.7 (but above the floor 1.0).
        assertTrue(detector.process(frame(i, levelForFlux(BASE, 3.0), 0.5)).isEmpty());
    }

    @Test
    void strongJumpOutOfSilenceFiresViaThresholdFloor() {
        OnsetDetector detector = new OnsetDetector(config());
        for (int i = 0; i < 40; i++) {
            detector.process(frame(i, 0.0, 0.0)); // silence: adaptive term stays 0
        }
        List<BeatEvent> events = detector.process(frame(40, levelForFlux(0.0, 50.0), 0.5));
        assertEquals(1, events.size());
        assertEquals(BeatEvent.Kind.BEAT, events.get(0).kind());
    }

    @Test
    void silenceNeverProducesEvents() {
        OnsetDetector detector = new OnsetDetector(config());
        long i = buildAdaptiveHistory(detector);
        // Huge magnitude jump but RMS below the silence threshold.
        assertTrue(detector.process(frame(i, 1000.0, 1e-6)).isEmpty());
    }

    @Test
    void steadySignalProducesNoEvents() {
        OnsetDetector detector = new OnsetDetector(config());
        for (int i = 0; i < 200; i++) {
            assertTrue(detector.process(frame(i, BASE, 0.5)).isEmpty(),
                    "steady signal must not trigger at frame " + i);
        }
    }

    @Test
    void refractoryPeriodSuppressesImmediateSecondOnset() {
        OnsetDetector detector = new OnsetDetector(config());
        long i = buildAdaptiveHistory(detector);
        assertEquals(1, detector.process(frame(i, levelForFlux(BASE, 70.0), 0.5)).size());
        // Refractory is 4 frames (60 ms at 16 ms hop): i+1 .. i+3 suppressed.
        assertTrue(detector.process(frame(i + 1, levelForFlux(BASE, 70.0), 0.5)).isEmpty());
        assertTrue(detector.process(frame(i + 2, BASE, 0.5)).isEmpty());
        assertTrue(detector.process(frame(i + 3, levelForFlux(BASE, 70.0), 0.5)).isEmpty());
    }

    @Test
    void resetClearsState() {
        OnsetDetector detector = new OnsetDetector(config());
        buildAdaptiveHistory(detector);
        detector.reset();
        // After reset the adaptive term is zero; only the floor (1.0) applies,
        // so a tiny jump (flux ~0.3) cannot trigger.
        assertTrue(detector.process(frame(0, levelForFlux(0.0, 0.3), 0.5)).isEmpty());
    }
}
