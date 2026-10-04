package com.github.cuixudong74.audioreactiveenginejava.audio;

import com.github.cuixudong74.audioreactiveenginejava.audio.pipeline.Resampler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResamplerTest {

    private static float[] signal(int length) {
        float[] out = new float[length];
        for (int i = 0; i < length; i++) {
            out[i] = (float) Math.sin(i * 0.1);
        }
        return out;
    }

    private static float[] concat(List<float[]> parts) {
        int total = 0;
        for (float[] p : parts) {
            total += p.length;
        }
        float[] out = new float[total];
        int pos = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    @Test
    void identityRatePassesSamplesThrough() {
        Resampler r = new Resampler(44100, 44100);
        float[] in = signal(100);
        assertArrayEquals(in, r.process(in));
    }

    @Test
    void chunkingDoesNotChangeOutput_downsample() {
        float[] in = signal(10000);
        float[] oneShot = new Resampler(44100, 8000).process(in);

        Resampler chunked = new Resampler(44100, 8000);
        List<float[]> parts = new ArrayList<>();
        Random random = new Random(1);
        int pos = 0;
        while (pos < in.length) {
            int n = Math.min(1 + random.nextInt(500), in.length - pos);
            float[] piece = new float[n];
            System.arraycopy(in, pos, piece, 0, n);
            parts.add(chunked.process(piece));
            pos += n;
        }
        assertArrayEquals(oneShot, concat(parts));
    }

    @Test
    void chunkingDoesNotChangeOutput_upsample() {
        float[] in = signal(3000);
        float[] oneShot = new Resampler(8000, 44100).process(in);

        Resampler chunked = new Resampler(8000, 44100);
        List<float[]> parts = new ArrayList<>();
        Random random = new Random(2);
        int pos = 0;
        while (pos < in.length) {
            int n = Math.min(1 + random.nextInt(200), in.length - pos);
            float[] piece = new float[n];
            System.arraycopy(in, pos, piece, 0, n);
            parts.add(chunked.process(piece));
            pos += n;
        }
        assertArrayEquals(oneShot, concat(parts));
    }

    @Test
    void outputLengthMatchesRateRatio() {
        float[] in = new float[44100];
        float[] out = new Resampler(44100, 8000).process(in);
        // 8000/44100 of the input, minus at most one interpolation interval.
        assertTrue(Math.abs(out.length - 8000) <= 2, "length was " + out.length);
    }

    @Test
    void resetRestoresInitialState() {
        float[] in = signal(1000);
        float[] first = new Resampler(44100, 16000).process(in);
        Resampler r = new Resampler(44100, 16000);
        r.process(in);
        r.reset();
        assertArrayEquals(first, r.process(in));
    }
}
