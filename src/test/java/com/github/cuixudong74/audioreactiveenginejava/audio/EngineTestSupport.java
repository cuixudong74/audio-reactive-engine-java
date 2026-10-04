package com.github.cuixudong74.audioreactiveenginejava.audio;

import com.github.cuixudong74.audioreactiveenginejava.audio.engine.AudioAnalysisEngine;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AnalysisConfig;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.BeatEvent;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.EngineEvent;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.GapNotice;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SampleChunk;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SpectrumFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shared helpers for engine integration tests. */
final class EngineTestSupport {

    private EngineTestSupport() {
    }

    static AnalysisConfig engineConfig() {
        return new AnalysisConfig(0, 8000, 256, AnalysisConfig.WindowType.HANN, 0.5,
                1.0e-4, 1.5, 1.0, 2.0, 60, AnalysisConfig.GapPolicy.FILL_SILENCE,
                1 << 16, 64, 1024);
    }

    /** Mono signal of {@code seconds} at {@code rate} with impulse clicks. */
    static float[] clickTrack(int rate, double seconds, double[] clickTimes, float clickAmp) {
        float[] out = new float[(int) (rate * seconds)];
        for (double t : clickTimes) {
            int idx = (int) (t * rate);
            if (idx < out.length) {
                out[idx] = clickAmp;
            }
        }
        return out;
    }

    /** Splits mono samples into equal-sized chunks with sequential frame indices. */
    static List<SampleChunk> chunksOf(String stream, int rate, float[] mono, int framesPerChunk) {
        List<SampleChunk> out = new ArrayList<>();
        for (int start = 0; start < mono.length; start += framesPerChunk) {
            int n = Math.min(framesPerChunk, mono.length - start);
            float[] piece = new float[n];
            System.arraycopy(mono, start, piece, 0, n);
            out.add(new SampleChunk(stream, start, rate, 1, piece));
        }
        return out;
    }

    /** Splits mono samples into random-sized chunks (deterministic given the seed). */
    static List<SampleChunk> randomChunks(String stream, int rate, float[] mono, long seed) {
        List<SampleChunk> out = new ArrayList<>();
        java.util.Random random = new java.util.Random(seed);
        int pos = 0;
        while (pos < mono.length) {
            int n = Math.min(1 + random.nextInt(700), mono.length - pos);
            float[] piece = new float[n];
            System.arraycopy(mono, pos, piece, 0, n);
            out.add(new SampleChunk(stream, pos, rate, 1, piece));
            pos += n;
        }
        return out;
    }

    /** Feeds all chunks, polling while applying backpressure, then drains until quiet. */
    static List<EngineEvent> runEngine(AnalysisConfig config, List<SampleChunk> chunks)
            throws InterruptedException {
        try (AudioAnalysisEngine engine = new AudioAnalysisEngine(config)) {
            engine.start();
            List<EngineEvent> all = new ArrayList<>();
            for (SampleChunk chunk : chunks) {
                while (!engine.submit(chunk)) {
                    all.addAll(engine.poll(20, TimeUnit.MILLISECONDS));
                }
            }
            // Drain until the engine goes quiet.
            while (true) {
                List<EngineEvent> batch = engine.poll(300, TimeUnit.MILLISECONDS);
                if (batch.isEmpty()) {
                    break;
                }
                all.addAll(batch);
            }
            return all;
        }
    }

    static List<SpectrumFrame> framesOf(List<EngineEvent> events) {
        return events.stream().filter(SpectrumFrame.class::isInstance)
                .map(SpectrumFrame.class::cast).toList();
    }

    static List<BeatEvent> beatsOf(List<EngineEvent> events) {
        return events.stream().filter(BeatEvent.class::isInstance)
                .map(BeatEvent.class::cast).toList();
    }

    static List<GapNotice> gapsOf(List<EngineEvent> events) {
        return events.stream().filter(GapNotice.class::isInstance)
                .map(GapNotice.class::cast).toList();
    }

    static void assertEventsEqual(List<EngineEvent> expected, List<EngineEvent> actual) {
        assertEquals(expected.size(), actual.size(), "event count");
        for (int i = 0; i < expected.size(); i++) {
            EngineEvent e = expected.get(i);
            EngineEvent a = actual.get(i);
            assertEquals(e.getClass(), a.getClass(), "event type at " + i);
            if (e instanceof SpectrumFrame ef && a instanceof SpectrumFrame af) {
                assertEquals(ef.frameIndex(), af.frameIndex(), "frameIndex at " + i);
                assertEquals(ef.timeSeconds(), af.timeSeconds(), 0.0, "time at " + i);
                assertEquals(ef.rmsEnergy(), af.rmsEnergy(), 0.0, "rms at " + i);
                assertEquals(ef.configVersion(), af.configVersion(), "version at " + i);
                assertArrayEquals(ef.magnitudes(), af.magnitudes(), "magnitudes at " + i);
            } else if (e instanceof BeatEvent eb && a instanceof BeatEvent ab) {
                assertEquals(eb.timeSeconds(), ab.timeSeconds(), 0.0, "beat time at " + i);
                assertEquals(eb.strength(), ab.strength(), 0.0, "beat strength at " + i);
                assertEquals(eb.kind(), ab.kind(), "beat kind at " + i);
            } else if (e instanceof GapNotice eg && a instanceof GapNotice ag) {
                assertEquals(eg.timeSeconds(), ag.timeSeconds(), 0.0, "gap time at " + i);
                assertEquals(eg.missingFrames(), ag.missingFrames(), "gap frames at " + i);
            }
        }
    }
}
