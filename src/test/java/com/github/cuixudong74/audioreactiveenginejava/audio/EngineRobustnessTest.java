package com.github.cuixudong74.audioreactiveenginejava.audio;

import com.github.cuixudong74.audioreactiveenginejava.audio.config.AnalysisParams;
import com.github.cuixudong74.audioreactiveenginejava.audio.config.EngineConfig;
import com.github.cuixudong74.audioreactiveenginejava.audio.config.GapPolicy;
import com.github.cuixudong74.audioreactiveenginejava.audio.config.OverflowPolicy;
import com.github.cuixudong74.audioreactiveenginejava.audio.config.WindowType;
import com.github.cuixudong74.audioreactiveenginejava.audio.engine.AudioEngine;
import com.github.cuixudong74.audioreactiveenginejava.audio.engine.EngineOutput;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AudioChunk;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AudioEvent;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SpectrumFrame;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EngineRobustnessTest {

    private static final int RATE = EngineConsistencyTest.RATE;

    private static List<EngineOutput> drain(AudioEngine engine) throws InterruptedException {
        List<EngineOutput> out = new ArrayList<>();
        EngineOutput item;
        while ((item = engine.take()) != null) {
            out.add(item);
        }
        return out;
    }

    @Test
    void droppedFramesAreZeroFilledAndTimelineStaysContinuous() throws InterruptedException {
        float[] signal = EngineConsistencyTest.synthSignal(RATE, 2.0);
        // Drop frames [1000, 2000) by submitting two disjoint chunks.
        float[] head = new float[1000];
        float[] tail = new float[signal.length - 2000];
        System.arraycopy(signal, 0, head, 0, 1000);
        System.arraycopy(signal, 2000, tail, 0, tail.length);
        AudioEngine engine = new AudioEngine(EngineConsistencyTest.testConfig(),
                EngineConsistencyTest.testParams());
        engine.submit(new AudioChunk(0, 0, RATE, head, 1));
        engine.submit(new AudioChunk(1, 2000, RATE, tail, 1));
        engine.finish();
        List<EngineOutput> out = drain(engine);
        assertTrue(engine.error().isEmpty());
        // Frame timestamps must be strictly increasing and gap-free in index.
        long lastIndex = -1;
        double lastTime = -1;
        for (EngineOutput item : out) {
            if (item instanceof EngineOutput.Frame f) {
                assertEquals(lastIndex + 1, f.frame().frameIndex());
                assertTrue(f.frame().timeSeconds() > lastTime);
                lastIndex = f.frame().frameIndex();
                lastTime = f.frame().timeSeconds();
            }
        }
        assertTrue(lastIndex > 0);
    }

    @Test
    void backwardTimestampJumpNeverRewindsTimeline() throws InterruptedException {
        float[] signal = EngineConsistencyTest.synthSignal(RATE, 1.0);
        AudioEngine engine = new AudioEngine(EngineConsistencyTest.testConfig(),
                EngineConsistencyTest.testParams());
        engine.submit(new AudioChunk(0, 0, RATE, signal, 1));
        // A stale chunk restarting at frame 0 must be ignored entirely.
        engine.submit(new AudioChunk(1, 0, RATE, signal, 1));
        // A chunk fully overlapping emitted frames is dropped entirely.
        float[] overlap = new float[400];
        System.arraycopy(signal, signal.length - 400, overlap, 0, 400);
        engine.submit(new AudioChunk(2, signal.length - 400, RATE, overlap, 1));
        engine.finish();
        List<EngineOutput> out = drain(engine);
        List<EngineOutput> reference = EngineConsistencyTest.run(
                EngineConsistencyTest.testConfig(), EngineConsistencyTest.testParams(),
                List.of(new AudioChunk(0, 0, RATE, signal, 1)));
        EngineConsistencyTest.assertSameOutputs(reference, out);
    }

    @Test
    void hotUpdateKeepsHistoryAndAppliesToFutureFrames() throws InterruptedException {
        float[] signal = EngineConsistencyTest.synthSignal(RATE, 3.0);
        List<AudioChunk> chunks = EngineConsistencyTest.chunkify(signal, 500, RATE);
        AnalysisParams oldParams = EngineConsistencyTest.testParams();
        AnalysisParams newParams = new AnalysisParams(512, WindowType.BLACKMAN, 0.75,
                1.5, 1e-5, 0.05);

        AudioEngine engine = new AudioEngine(EngineConsistencyTest.testConfig(), oldParams);
        // Submit half the stream, then update, then the rest.
        int half = chunks.size() / 2;
        for (int i = 0; i < half; i++) {
            engine.submit(chunks.get(i));
        }
        engine.updateAnalysisParams(newParams);
        for (int i = half; i < chunks.size(); i++) {
            engine.submit(chunks.get(i));
        }
        engine.finish();
        List<EngineOutput> out = drain(engine);
        assertTrue(engine.error().isEmpty());

        // History must be byte-identical to a run without the update.
        List<EngineOutput> reference = EngineConsistencyTest.run(
                EngineConsistencyTest.testConfig(), oldParams,
                EngineConsistencyTest.chunkify(signal, 500, RATE));
        int oldWindow = oldParams.windowSize();
        int newWindow = newParams.windowSize();
        int oldBinCount = 0;
        int newBinCount = 0;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) instanceof EngineOutput.Frame f) {
                int bins = f.frame().magnitudesRef().length;
                if (bins == oldWindow / 2 + 1) {
                    oldBinCount++;
                    // Frames under old params must match the no-update run.
                    assertSameFrame((EngineOutput.Frame) reference.get(i), f, i);
                } else if (bins == newWindow / 2 + 1) {
                    newBinCount++;
                } else {
                    fail("unexpected bin count " + bins);
                }
            }
        }
        assertTrue(oldBinCount > 0, "expected frames under old params");
        assertTrue(newBinCount > 0, "expected frames under new params");
        // Frame indices remain gap-free across the change point.
        long lastIndex = -1;
        for (EngineOutput item : out) {
            if (item instanceof EngineOutput.Frame f) {
                assertEquals(lastIndex + 1, f.frame().frameIndex());
                lastIndex = f.frame().frameIndex();
            }
        }
    }

    private static void assertSameFrame(EngineOutput.Frame expected, EngineOutput.Frame actual,
                                        int i) {
        SpectrumFrame a = expected.frame();
        SpectrumFrame b = actual.frame();
        assertEquals(a.frameIndex(), b.frameIndex(), "frameIndex at " + i);
        assertEquals(a.timeSeconds(), b.timeSeconds(), "time at " + i);
        assertArrayEquals(a.magnitudesRef(), b.magnitudesRef(), "magnitudes at " + i);
    }

    @Test
    void resetStartsACleanReproducibleRun() throws InterruptedException {
        float[] signal = EngineConsistencyTest.synthSignal(RATE, 1.5);
        List<AudioChunk> chunks = EngineConsistencyTest.chunkify(signal, 300, RATE);
        AudioEngine engine = new AudioEngine(EngineConsistencyTest.testConfig(),
                EngineConsistencyTest.testParams());
        for (AudioChunk c : chunks) {
            engine.submit(c);
        }
        engine.finish();
        List<EngineOutput> first = drain(engine);

        engine.reset();
        for (AudioChunk c : chunks) {
            engine.submit(c);
        }
        engine.finish();
        List<EngineOutput> second = drain(engine);
        EngineConsistencyTest.assertSameOutputs(first, second);

        // Event sequences restart from 0: no duplicated or skipped ids.
        long expectedSeq = 0;
        for (EngineOutput item : second) {
            if (item instanceof EngineOutput.Event e) {
                assertEquals(expectedSeq++, e.event().sequence());
            }
        }
    }

    @Test
    void slowConsumerTriggersBoundedBlockingNotUnboundedMemory() throws Exception {
        // Tiny queue, tiny offer timeout: a stalled consumer must surface an
        // error instead of unbounded growth.
        EngineConfig config = new EngineConfig(RATE, 4, OverflowPolicy.BLOCK,
                Duration.ofMillis(50), GapPolicy.ZERO_FILL);
        AudioEngine engine = new AudioEngine(config, EngineConsistencyTest.testParams());
        float[] signal = EngineConsistencyTest.synthSignal(RATE, 2.0);
        for (AudioChunk c : EngineConsistencyTest.chunkify(signal, 400, RATE)) {
            try {
                engine.submit(c);
            } catch (IllegalStateException ignored) {
                break;
            }
            if (engine.error().isPresent()) {
                break;
            }
        }
        assertTrue(engine.error().isPresent(), "expected backpressure timeout error");
        engine.cancel();
    }

    @Test
    void dropOldestPolicyNeverBlocksProducer() throws InterruptedException {
        EngineConfig config = new EngineConfig(RATE, 8, OverflowPolicy.DROP_OLDEST,
                Duration.ofMillis(50), GapPolicy.ZERO_FILL);
        AudioEngine engine = new AudioEngine(config, EngineConsistencyTest.testParams());
        float[] signal = EngineConsistencyTest.synthSignal(RATE, 1.0);
        for (AudioChunk c : EngineConsistencyTest.chunkify(signal, 400, RATE)) {
            engine.submit(c);
        }
        engine.finish();
        // Queue capacity bounds retained output; consumer still terminates.
        List<EngineOutput> out = drain(engine);
        assertFalse(out.isEmpty());
        assertTrue(engine.error().isEmpty());
    }

    @Test
    void cancelStopsCleanlyWithoutPartialOutput() throws InterruptedException {
        AudioEngine engine = new AudioEngine(EngineConsistencyTest.testConfig(),
                EngineConsistencyTest.testParams());
        float[] signal = EngineConsistencyTest.synthSignal(RATE, 2.0);
        List<AudioChunk> chunks = EngineConsistencyTest.chunkify(signal, 200, RATE);
        for (int i = 0; i < chunks.size() / 2; i++) {
            engine.submit(chunks.get(i));
        }
        engine.cancel();
        // After cancel, submitting or finishing is rejected / ignored.
        assertThrows(IllegalStateException.class,
                () -> engine.submit(chunks.get(chunks.size() - 1)));
        engine.finish();
        List<EngineOutput> out = drain(engine);
        // Every emitted item is a complete frame or event; the stream ends.
        for (EngineOutput item : out) {
            assertNotNull(item);
            if (item instanceof EngineOutput.Frame f) {
                assertTrue(f.frame().magnitudesRef().length > 0);
            } else {
                AudioEvent e = ((EngineOutput.Event) item).event();
                assertTrue(e.sequence() >= 0);
            }
        }
        assertTrue(engine.isDrained());
    }

    @Test
    void pollWithTimeoutReturnsNullWhenIdle() throws InterruptedException {
        AudioEngine engine = new AudioEngine(EngineConsistencyTest.testConfig(),
                EngineConsistencyTest.testParams());
        assertNull(engine.poll(20, TimeUnit.MILLISECONDS));
        engine.finish();
        assertNull(engine.poll(1, TimeUnit.SECONDS));
        assertTrue(engine.isDrained());
    }
}
