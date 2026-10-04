package com.github.cuixudong74.audioreactiveenginejava.audio;

import com.github.cuixudong74.audioreactiveenginejava.audio.config.AnalysisParams;
import com.github.cuixudong74.audioreactiveenginejava.audio.config.WindowType;
import com.github.cuixudong74.audioreactiveenginejava.audio.engine.EngineOutput;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AudioEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EngineEventsTest {

    private static List<AudioEvent> eventsOf(List<EngineOutput> out) {
        List<AudioEvent> events = new ArrayList<>();
        for (EngineOutput item : out) {
            if (item instanceof EngineOutput.Event e) {
                events.add(e.event());
            }
        }
        return events;
    }

    private static List<AudioEvent> runForEvents(float[] signal, AnalysisParams params)
            throws InterruptedException {
        return eventsOf(EngineConsistencyTest.run(EngineConsistencyTest.testConfig(),
                params, EngineConsistencyTest.chunkify(signal, 200, EngineConsistencyTest.RATE)));
    }

    /** Click train: short bursts at a fixed period, alternating amplitudes. */
    private static float[] clickTrain(int rate, double periodSeconds, int count,
                                      double strongAmp, double weakAmp) {
        int n = (int) (rate * periodSeconds * count) + rate / 2;
        float[] x = new float[n];
        for (int c = 0; c < count; c++) {
            int at = (int) (c * periodSeconds * rate) + rate / 10;
            double amp = c % 2 == 0 ? strongAmp : weakAmp;
            for (int k = 0; k < 32 && at + k < n; k++) {
                x[at + k] += (float) (amp * Math.exp(-k / 3.0));
            }
        }
        return x;
    }

    @Test
    void clickTrainProducesOnsetsAndBeats() throws InterruptedException {
        float[] signal = clickTrain(EngineConsistencyTest.RATE, 0.25, 12, 0.9, 0.9);
        List<AudioEvent> events = runForEvents(signal, EngineConsistencyTest.testParams());
        long onsets = events.stream().filter(e -> e.type() == AudioEvent.Type.ONSET).count();
        long beats = events.stream().filter(e -> e.type() == AudioEvent.Type.BEAT).count();
        assertTrue(onsets >= 10, "expected most clicks detected, got " + onsets);
        assertTrue(beats >= 6, "expected beats after tempo lock, got " + beats);
        for (AudioEvent e : events) {
            assertTrue(e.intensity() > 0 && e.intensity() <= 1.0);
            assertTrue(e.timeSeconds() >= 0);
        }
    }

    @Test
    void strongAndWeakEventsAreDistinguished() throws InterruptedException {
        float[] signal = clickTrain(EngineConsistencyTest.RATE, 0.25, 12, 0.95, 0.15);
        List<AudioEvent> events = runForEvents(signal, EngineConsistencyTest.testParams());
        boolean hasStrong = events.stream()
                .anyMatch(e -> e.strength() == AudioEvent.Strength.STRONG);
        boolean hasWeak = events.stream()
                .anyMatch(e -> e.strength() == AudioEvent.Strength.WEAK);
        assertTrue(hasStrong, "expected STRONG events");
        assertTrue(hasWeak, "expected WEAK events");
        double minStrong = events.stream()
                .filter(e -> e.strength() == AudioEvent.Strength.STRONG)
                .mapToDouble(AudioEvent::intensity).min().orElse(1);
        double maxWeak = events.stream()
                .filter(e -> e.strength() == AudioEvent.Strength.WEAK)
                .mapToDouble(AudioEvent::intensity).max().orElse(0);
        assertTrue(minStrong >= maxWeak,
                "strong " + minStrong + " vs weak " + maxWeak);
    }

    @Test
    void silenceProducesNoEvents() throws InterruptedException {
        float[] silence = new float[EngineConsistencyTest.RATE * 2];
        List<AudioEvent> events = runForEvents(silence, EngineConsistencyTest.testParams());
        assertTrue(events.isEmpty(), "silence must not produce events: " + events);
    }

    @Test
    void steadyNoiseProducesNoEvents() throws InterruptedException {
        Random rnd = new Random(1);
        float[] noise = new float[EngineConsistencyTest.RATE * 2];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (rnd.nextFloat() * 2 - 1) * 0.001f;
        }
        List<AudioEvent> events = runForEvents(noise, EngineConsistencyTest.testParams());
        assertTrue(events.isEmpty(),
                "steady low noise must not produce events: " + events.size());
    }

    @Test
    void thresholdTradesRecallForPrecision() throws InterruptedException {
        int rate = EngineConsistencyTest.RATE;
        float[] signal = EngineConsistencyTest.synthSignal(rate, 3.0);
        AnalysisParams sensitive =
                new AnalysisParams(256, WindowType.HANN, 0.5, 1.1, 1e-6, 0.03);
        AnalysisParams strict =
                new AnalysisParams(256, WindowType.HANN, 0.5, 3.0, 1e-3, 0.08);
        long sensitiveCount = runForEvents(signal, sensitive).stream()
                .filter(e -> e.type() == AudioEvent.Type.ONSET).count();
        long strictCount = runForEvents(signal, strict).stream()
                .filter(e -> e.type() == AudioEvent.Type.ONSET).count();
        assertTrue(sensitiveCount >= strictCount,
                "sensitive " + sensitiveCount + " should be >= strict " + strictCount);
        assertTrue(sensitiveCount > 0);
    }
}
