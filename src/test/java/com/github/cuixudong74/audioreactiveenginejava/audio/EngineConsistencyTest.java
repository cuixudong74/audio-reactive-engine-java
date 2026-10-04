package com.github.cuixudong74.audioreactiveenginejava.audio;

import com.github.cuixudong74.audioreactiveenginejava.audio.config.AnalysisParams;
import com.github.cuixudong74.audioreactiveenginejava.audio.config.EngineConfig;
import com.github.cuixudong74.audioreactiveenginejava.audio.config.GapPolicy;
import com.github.cuixudong74.audioreactiveenginejava.audio.config.OverflowPolicy;
import com.github.cuixudong74.audioreactiveenginejava.audio.engine.AudioEngine;
import com.github.cuixudong74.audioreactiveenginejava.audio.engine.EngineOutput;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AudioChunk;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AudioEvent;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SpectrumFrame;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EngineConsistencyTest {

    static final int RATE = 8000;

    /** Deterministic test signal: sine bed plus a click train every 0.25 s. */
    static float[] synthSignal(int sampleRate, double seconds) {
        int n = (int) (sampleRate * seconds);
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = (float) (0.05 * Math.sin(2 * Math.PI * 440 * i / (double) sampleRate));
        }
        for (int c = 0; c < seconds * 4; c++) {
            int at = c * sampleRate / 4;
            for (int k = 0; k < 24 && at + k < n; k++) {
                x[at + k] += (float) (0.8 * Math.exp(-k / 4.0));
            }
        }
        return x;
    }

    static EngineConfig testConfig() {
        return new EngineConfig(RATE, 4096, OverflowPolicy.BLOCK,
                Duration.ofSeconds(5), GapPolicy.ZERO_FILL);
    }

    static AnalysisParams testParams() {
        return new AnalysisParams(256, com.github.cuixudong74.audioreactiveenginejava
                .audio.config.WindowType.HANN, 0.5, 1.5, 1e-5, 0.05);
    }

    /** Splits mono samples into chunks of the given frame count. */
    static List<AudioChunk> chunkify(float[] mono, int framesPerChunk, int sampleRate) {
        List<AudioChunk> chunks = new ArrayList<>();
        long seq = 0;
        for (int pos = 0; pos < mono.length; pos += framesPerChunk) {
            int n = Math.min(framesPerChunk, mono.length - pos);
            float[] part = new float[n];
            System.arraycopy(mono, pos, part, 0, n);
            chunks.add(new AudioChunk(seq++, pos, sampleRate, part, 1));
        }
        return chunks;
    }

    static List<EngineOutput> run(EngineConfig config, AnalysisParams params,
                                  List<AudioChunk> chunks) throws InterruptedException {
        AudioEngine engine = new AudioEngine(config, params);
        for (AudioChunk c : chunks) {
            engine.submit(c);
        }
        engine.finish();
        List<EngineOutput> out = new ArrayList<>();
        EngineOutput item;
        while ((item = engine.take()) != null) {
            out.add(item);
        }
        assertTrue(engine.error().isEmpty(), "engine error: " + engine.error());
        return out;
    }

    static void assertSameOutputs(List<EngineOutput> a, List<EngineOutput> b) {
        assertEquals(a.size(), b.size(), "output count");
        for (int i = 0; i < a.size(); i++) {
            EngineOutput x = a.get(i);
            EngineOutput y = b.get(i);
            assertEquals(x.getClass(), y.getClass(), "item type at " + i);
            if (x instanceof EngineOutput.Frame fx) {
                SpectrumFrame sx = fx.frame();
                SpectrumFrame sy = ((EngineOutput.Frame) y).frame();
                assertEquals(sx.frameIndex(), sy.frameIndex(), "frameIndex at " + i);
                assertEquals(sx.timeSeconds(), sy.timeSeconds(), "time at " + i);
                assertEquals(sx.energy(), sy.energy(), "energy at " + i);
                assertArrayEquals(sx.magnitudesRef(), sy.magnitudesRef(), "magnitudes at " + i);
            } else {
                AudioEvent ex = ((EngineOutput.Event) x).event();
                AudioEvent ey = ((EngineOutput.Event) y).event();
                assertEquals(ex, ey, "event at " + i);
            }
        }
    }

    @Test
    void chunkingDoesNotAffectOutput() throws InterruptedException {
        float[] signal = synthSignal(RATE, 2.0);
        List<EngineOutput> reference = run(testConfig(), testParams(),
                chunkify(signal, signal.length, RATE));
        for (int chunkSize : new int[]{1, 13, 100, 257, 1000}) {
            List<EngineOutput> actual = run(testConfig(), testParams(),
                    chunkify(signal, chunkSize, RATE));
            assertSameOutputs(reference, actual);
        }
    }

    @Test
    void outOfOrderAndDuplicateDeliveryMatchesOneShot() throws InterruptedException {
        float[] signal = synthSignal(RATE, 2.0);
        List<EngineOutput> reference = run(testConfig(), testParams(),
                chunkify(signal, signal.length, RATE));

        List<AudioChunk> chunks = chunkify(signal, 173, RATE);
        // Duplicate every chunk and shuffle with a fixed seed.
        List<AudioChunk> messy = new ArrayList<>();
        long seq = 1000;
        for (AudioChunk c : chunks) {
            messy.add(c);
            messy.add(new AudioChunk(seq++, c.startFrame(), c.sampleRate(),
                    c.samples(), c.channels()));
        }
        Collections.shuffle(messy, new Random(7));
        List<EngineOutput> actual = run(testConfig(), testParams(), messy);
        assertSameOutputs(reference, actual);
    }

    @Test
    void repeatedRunsAreReproducible() throws InterruptedException {
        float[] signal = synthSignal(RATE, 1.5);
        List<AudioChunk> chunks = chunkify(signal, 200, RATE);
        List<EngineOutput> first = run(testConfig(), testParams(), chunks);
        List<EngineOutput> second = run(testConfig(), testParams(), chunks);
        assertSameOutputs(first, second);
        assertFalse(first.isEmpty());
    }

    @Test
    void resampledInputIsChunkingInvariant() throws InterruptedException {
        int srcRate = 4000;
        float[] signal = synthSignal(srcRate, 2.0);
        List<EngineOutput> reference = run(testConfig(), testParams(),
                chunkify(signal, signal.length, srcRate));
        List<EngineOutput> chunked = run(testConfig(), testParams(),
                chunkify(signal, 77, srcRate));
        assertSameOutputs(reference, chunked);
    }

    @Test
    void stereoInputIsChunkingInvariant() throws InterruptedException {
        float[] mono = synthSignal(RATE, 1.5);
        float[] stereo = new float[mono.length * 2];
        for (int i = 0; i < mono.length; i++) {
            stereo[2 * i] = mono[i];
            stereo[2 * i + 1] = mono[i] * 0.5f;
        }
        List<AudioChunk> oneShot = List.of(
                new AudioChunk(0, 0, RATE, stereo, 2));
        List<EngineOutput> reference = run(testConfig(), testParams(), oneShot);

        List<AudioChunk> chunks = new ArrayList<>();
        long seq = 0;
        for (int pos = 0; pos < mono.length; pos += 111) {
            int n = Math.min(111, mono.length - pos);
            float[] part = new float[n * 2];
            System.arraycopy(stereo, pos * 2, part, 0, n * 2);
            chunks.add(new AudioChunk(seq++, pos, RATE, part, 2));
        }
        List<EngineOutput> actual = run(testConfig(), testParams(), chunks);
        assertSameOutputs(reference, actual);
    }

    @Test
    void frameTimestampsAreMonotonic() throws InterruptedException {
        float[] signal = synthSignal(RATE, 2.0);
        List<EngineOutput> out = run(testConfig(), testParams(),
                chunkify(signal, 300, RATE));
        double lastTime = Double.NEGATIVE_INFINITY;
        long lastFrame = -1;
        long lastEventSeq = -1;
        int frames = 0;
        for (EngineOutput item : out) {
            if (item instanceof EngineOutput.Frame f) {
                assertTrue(f.frame().timeSeconds() >= lastTime, "timeline must not rewind");
                assertEquals(lastFrame + 1, f.frame().frameIndex());
                lastTime = f.frame().timeSeconds();
                lastFrame = f.frame().frameIndex();
                frames++;
            } else {
                AudioEvent e = ((EngineOutput.Event) item).event();
                assertTrue(e.timeSeconds() >= 0);
                assertEquals(lastEventSeq + 1, e.sequence());
                lastEventSeq = e.sequence();
            }
        }
        assertTrue(frames > 0);
    }
}
