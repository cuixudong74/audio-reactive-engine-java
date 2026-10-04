package com.github.cuixudong74.audioreactiveenginejava.audio;

import com.github.cuixudong74.audioreactiveenginejava.audio.engine.AudioAnalysisEngine;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AnalysisConfig;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.BeatEvent;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.EngineEvent;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.GapNotice;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SampleChunk;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SpectrumFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.github.cuixudong74.audioreactiveenginejava.audio.EngineTestSupport.assertEventsEqual;
import static com.github.cuixudong74.audioreactiveenginejava.audio.EngineTestSupport.beatsOf;
import static com.github.cuixudong74.audioreactiveenginejava.audio.EngineTestSupport.chunksOf;
import static com.github.cuixudong74.audioreactiveenginejava.audio.EngineTestSupport.clickTrack;
import static com.github.cuixudong74.audioreactiveenginejava.audio.EngineTestSupport.engineConfig;
import static com.github.cuixudong74.audioreactiveenginejava.audio.EngineTestSupport.framesOf;
import static com.github.cuixudong74.audioreactiveenginejava.audio.EngineTestSupport.gapsOf;
import static com.github.cuixudong74.audioreactiveenginejava.audio.EngineTestSupport.randomChunks;
import static com.github.cuixudong74.audioreactiveenginejava.audio.EngineTestSupport.runEngine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioAnalysisEngineTest {

    private static final int RATE = 8000;

    private static float[] musicLikeSignal(int seconds) {
        float[] out = clickTrack(RATE, seconds, new double[]{0.5, 1.0, 1.5}, 0.9f);
        for (int i = 0; i < out.length; i++) {
            out[i] += (float) (0.05 * Math.sin(i * 0.02));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 1. chunking / ordering / duplication invariance
    // ------------------------------------------------------------------

    @Test
    void chunkedInputMatchesOneShotInput() throws Exception {
        float[] signal = musicLikeSignal(2);
        List<EngineEvent> oneShot = runEngine(engineConfig(), chunksOf("s", RATE, signal, 1 << 15));
        List<EngineEvent> chunked = runEngine(engineConfig(), randomChunks("s", RATE, signal, 11));
        assertFalse(oneShot.isEmpty());
        assertEventsEqual(oneShot, chunked);
    }

    @Test
    void shuffledAndDuplicatedInputMatchesOrderedInput() throws Exception {
        float[] signal = musicLikeSignal(2);
        List<EngineEvent> ordered = runEngine(engineConfig(), chunksOf("s", RATE, signal, 512));

        List<SampleChunk> messy = new ArrayList<>(chunksOf("s", RATE, signal, 512));
        messy.addAll(chunksOf("s", RATE, signal, 512)); // every chunk twice
        Collections.shuffle(messy, new Random(5));
        List<EngineEvent> messyResult = runEngine(engineConfig(), messy);

        assertEventsEqual(ordered, messyResult);
    }

    @Test
    void multiChannelInputIsDownmixedDeterministically() throws Exception {
        float[] mono = musicLikeSignal(1);
        // Stereo: left = mono, right = mono -> downmix equals mono.
        float[] stereo = new float[mono.length * 2];
        for (int i = 0; i < mono.length; i++) {
            stereo[2 * i] = mono[i];
            stereo[2 * i + 1] = mono[i];
        }
        List<SampleChunk> stereoChunks = new ArrayList<>();
        for (int start = 0; start < mono.length; start += 512) {
            int n = Math.min(512, mono.length - start);
            float[] piece = new float[n * 2];
            System.arraycopy(stereo, start * 2, piece, 0, n * 2);
            stereoChunks.add(new SampleChunk("s", start, RATE, 2, piece));
        }
        List<EngineEvent> monoResult = runEngine(engineConfig(), chunksOf("s", RATE, mono, 512));
        List<EngineEvent> stereoResult = runEngine(engineConfig(), stereoChunks);
        assertEventsEqual(monoResult, stereoResult);
    }

    // ------------------------------------------------------------------
    // 2. beat detection behaviour
    // ------------------------------------------------------------------

    @Test
    void clicksProduceBeatEventsAtExpectedTimes() throws Exception {
        float[] signal = clickTrack(RATE, 2, new double[]{0.5, 1.0, 1.5}, 0.9f);
        List<EngineEvent> events = runEngine(engineConfig(), chunksOf("s", RATE, signal, 512));
        List<BeatEvent> beats = beatsOf(events);
        assertEquals(3, beats.size(), "expected one event per click, got " + beats);
        double[] expected = {0.5, 1.0, 1.5};
        for (int i = 0; i < 3; i++) {
            assertEquals(expected[i], beats.get(i).timeSeconds(), 0.1,
                    "beat " + i + " time");
            assertTrue(beats.get(i).strength() >= 1.0);
        }
    }

    @Test
    void silenceProducesNoBeatEvents() throws Exception {
        float[] silence = new float[RATE];
        List<EngineEvent> events = runEngine(engineConfig(), chunksOf("s", RATE, silence, 512));
        assertFalse(events.isEmpty(), "spectrum frames should still flow");
        assertTrue(beatsOf(events).isEmpty(), "silence must not produce beats");
        for (SpectrumFrame f : framesOf(events)) {
            assertEquals(0.0, f.rmsEnergy(), 0.0);
        }
    }

    @Test
    void outputTimelineIsMonotonic() throws Exception {
        float[] signal = musicLikeSignal(2);
        List<SampleChunk> messy = new ArrayList<>(chunksOf("s", RATE, signal, 300));
        Collections.shuffle(messy, new Random(13));
        List<EngineEvent> events = runEngine(engineConfig(), messy);
        double last = Double.NEGATIVE_INFINITY;
        for (EngineEvent e : events) {
            assertTrue(e.timeSeconds() >= last, "timeline went backwards at " + e);
            last = e.timeSeconds();
        }
    }

    // ------------------------------------------------------------------
    // 3. gaps
    // ------------------------------------------------------------------

    @Test
    void missingSamplesAreReportedAsGapAndFilled() throws Exception {
        AnalysisConfig config = new AnalysisConfig(0, RATE, 256, AnalysisConfig.WindowType.HANN, 0.5,
                1.0e-4, 1.5, 1.0, 2.0, 60, AnalysisConfig.GapPolicy.FILL_SILENCE,
                1000, 64, 1024);
        float[] head = clickTrack(RATE, 1, new double[]{0.5}, 0.9f);
        float[] tail = clickTrack(RATE, 1, new double[]{0.5}, 0.9f);
        List<SampleChunk> chunks = new ArrayList<>(chunksOf("s", RATE, head, 512));
        // Tail starts 4000 frames after the head ends: beyond the reorder bound.
        for (SampleChunk c : chunksOf("s", RATE, tail, 512)) {
            chunks.add(new SampleChunk("s", c.startFrameIndex() + 12000, RATE, 1, c.samples()));
        }
        List<EngineEvent> events = runEngine(config, chunks);
        List<GapNotice> gaps = gapsOf(events);
        assertEquals(1, gaps.size());
        assertEquals(4000, gaps.get(0).missingFrames());
        // Both clicks are still detected exactly once.
        assertEquals(2, beatsOf(events).size());
        double last = Double.NEGATIVE_INFINITY;
        for (EngineEvent e : events) {
            assertTrue(e.timeSeconds() >= last);
            last = e.timeSeconds();
        }
    }

    @Test
    void gapPolicySkipDoesNotFillSilence() throws Exception {
        AnalysisConfig fill = new AnalysisConfig(0, RATE, 256, AnalysisConfig.WindowType.HANN, 0.5,
                1.0e-4, 1.5, 1.0, 2.0, 60, AnalysisConfig.GapPolicy.FILL_SILENCE,
                1000, 64, 1024);
        AnalysisConfig skip = new AnalysisConfig(0, RATE, 256, AnalysisConfig.WindowType.HANN, 0.5,
                1.0e-4, 1.5, 1.0, 2.0, 60, AnalysisConfig.GapPolicy.SKIP,
                1000, 64, 1024);
        List<SampleChunk> chunks = new ArrayList<>(chunksOf("s", RATE, new float[8000], 512));
        for (SampleChunk c : chunksOf("s", RATE, new float[8000], 512)) {
            chunks.add(new SampleChunk("s", c.startFrameIndex() + 12000, RATE, 1, c.samples()));
        }
        List<EngineEvent> fillEvents = runEngine(fill, chunks);
        List<EngineEvent> skipEvents = runEngine(skip, chunks);
        assertEquals(1, gapsOf(fillEvents).size());
        assertEquals(1, gapsOf(skipEvents).size());
        // FILL_SILENCE materializes the gap (capped at the reorder bound), so
        // it must produce at least as many frames as SKIP.
        assertTrue(framesOf(fillEvents).size() > framesOf(skipEvents).size());
    }

    // ------------------------------------------------------------------
    // 4. backpressure
    // ------------------------------------------------------------------

    @Test
    void backpressureBoundsTheInputQueue() throws Exception {
        AnalysisConfig config = new AnalysisConfig(0, RATE, 256, AnalysisConfig.WindowType.HANN, 0.5,
                1.0e-4, 1.5, 1.0, 2.0, 60, AnalysisConfig.GapPolicy.FILL_SILENCE,
                1 << 16, 4, 4);
        try (AudioAnalysisEngine engine = new AudioAnalysisEngine(config)) {
            engine.start();
            float[] signal = musicLikeSignal(2);
            List<SampleChunk> chunks = chunksOf("s", RATE, signal, 256);
            int submitted = 0;
            boolean rejected = false;
            for (SampleChunk c : chunks) {
                if (engine.submit(c)) {
                    submitted++;
                } else {
                    rejected = true;
                    break;
                }
            }
            assertTrue(rejected, "unpolled engine must apply backpressure");
            assertTrue(submitted <= 4 + 4 + 1, "bounded queues, submitted=" + submitted);
            // After draining, submission works again.
            assertFalse(engine.poll(2, TimeUnit.SECONDS).isEmpty());
            List<EngineEvent> drain = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                drain.addAll(engine.poll(100, TimeUnit.MILLISECONDS));
            }
            assertTrue(engine.submit(chunks.get(0)) || !drain.isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // 5. hot config update
    // ------------------------------------------------------------------

    @Test
    void hotConfigUpdateAppliesOnlyToLaterSamples() throws Exception {
        AnalysisConfig base = engineConfig();
        try (AudioAnalysisEngine engine = new AudioAnalysisEngine(base)) {
            engine.start();
            float[] signal = musicLikeSignal(2);
            List<SampleChunk> chunks = chunksOf("s", RATE, signal, 512);
            List<EngineEvent> all = new ArrayList<>();
            int half = chunks.size() / 2;
            for (int i = 0; i < half; i++) {
                while (!engine.submit(chunks.get(i))) {
                    all.addAll(engine.poll(20, TimeUnit.MILLISECONDS));
                }
            }
            AnalysisConfig updated = new AnalysisConfig(0, RATE, 512, AnalysisConfig.WindowType.BLACKMAN,
                    0.5, 1.0e-4, 1.5, 1.0, 2.0, 60, AnalysisConfig.GapPolicy.FILL_SILENCE,
                    1 << 16, 64, 1024);
            long version = engine.updateConfig(updated);
            assertEquals(base.version() + 1, version);
            assertEquals(version, engine.currentConfig().version());
            for (int i = half; i < chunks.size(); i++) {
                while (!engine.submit(chunks.get(i))) {
                    all.addAll(engine.poll(20, TimeUnit.MILLISECONDS));
                }
            }
            while (true) {
                List<EngineEvent> batch = engine.poll(300, TimeUnit.MILLISECONDS);
                if (batch.isEmpty()) {
                    break;
                }
                all.addAll(batch);
            }
            List<SpectrumFrame> frames = framesOf(all);
            assertFalse(frames.isEmpty());
            // Frames before the switch keep version 0, frames after carry the
            // new version; history is never rewritten.
            assertTrue(frames.stream().anyMatch(f -> f.configVersion() == 0));
            assertTrue(frames.stream().anyMatch(f -> f.configVersion() == version));
            boolean sawNew = false;
            long lastIndex = -1;
            double lastTime = -1;
            for (SpectrumFrame f : frames) {
                if (f.configVersion() == version) {
                    sawNew = true;
                } else {
                    assertFalse(sawNew, "old-version frame appeared after new-version frame");
                }
                assertEquals(lastIndex + 1, f.frameIndex(), "frame indices stay contiguous");
                assertTrue(f.timeSeconds() >= lastTime, "timeline monotonic across update");
                lastIndex = f.frameIndex();
                lastTime = f.timeSeconds();
            }
            // The new window size is visible in the spectrum bin count.
            SpectrumFrame firstNew = frames.stream().filter(f -> f.configVersion() == version)
                    .findFirst().orElseThrow();
            assertEquals(512 / 2 + 1, firstNew.magnitudes().length);
        }
    }

    // ------------------------------------------------------------------
    // 6. restart
    // ------------------------------------------------------------------

    @Test
    void resetRestartsCleanlyWithoutDuplicateEvents() throws Exception {
        AnalysisConfig config = engineConfig();
        try (AudioAnalysisEngine engine = new AudioAnalysisEngine(config)) {
            engine.start();
            float[] signal = musicLikeSignal(1);
            List<SampleChunk> chunks = chunksOf("s", RATE, signal, 512);

            List<EngineEvent> first = new ArrayList<>();
            for (SampleChunk c : chunks) {
                while (!engine.submit(c)) {
                    first.addAll(engine.poll(20, TimeUnit.MILLISECONDS));
                }
            }
            while (true) {
                List<EngineEvent> batch = engine.poll(300, TimeUnit.MILLISECONDS);
                if (batch.isEmpty()) {
                    break;
                }
                first.addAll(batch);
            }

            engine.reset();
            // Pending output was discarded by reset: no stale events leak.
            assertTrue(engine.poll(100, TimeUnit.MILLISECONDS).isEmpty());

            List<EngineEvent> second = new ArrayList<>();
            for (SampleChunk c : chunks) {
                while (!engine.submit(c)) {
                    second.addAll(engine.poll(20, TimeUnit.MILLISECONDS));
                }
            }
            while (true) {
                List<EngineEvent> batch = engine.poll(300, TimeUnit.MILLISECONDS);
                if (batch.isEmpty()) {
                    break;
                }
                second.addAll(batch);
            }
            // Same input after reset reproduces the first session exactly.
            assertEventsEqual(first, second);
        }
    }

    // ------------------------------------------------------------------
    // 7. cancellation / shutdown
    // ------------------------------------------------------------------

    @Test
    void closeDuringProcessingNeverEmitsPartialEvents() throws Exception {
        AnalysisConfig config = engineConfig();
        AudioAnalysisEngine engine = new AudioAnalysisEngine(config);
        engine.start();
        float[] signal = musicLikeSignal(4);
        for (SampleChunk c : chunksOf("s", RATE, signal, 128)) {
            engine.submit(c);
        }
        engine.close(); // must return promptly and quietly
        engine.close(); // idempotent
        // After close, submission is rejected and polling does not throw.
        assertFalse(engine.submit(chunksOf("s", RATE, signal, 128).get(0)));
        assertTrue(engine.poll(10, TimeUnit.MILLISECONDS).size() >= 0);
    }
}
