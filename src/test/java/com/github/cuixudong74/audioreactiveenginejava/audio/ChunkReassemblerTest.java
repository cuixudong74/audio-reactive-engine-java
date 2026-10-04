package com.github.cuixudong74.audioreactiveenginejava.audio;

import com.github.cuixudong74.audioreactiveenginejava.audio.pipeline.ChunkReassembler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkReassemblerTest {

    private static float[] seq(long start, int length) {
        float[] out = new float[length];
        for (int i = 0; i < length; i++) {
            out[i] = start + i;
        }
        return out;
    }

    private static float[] drain(ChunkReassembler r, List<long[]> chunks) {
        List<Float> all = new ArrayList<>();
        for (long[] c : chunks) {
            float[] ready = r.accept(c[0], seq(c[0], (int) c[1])).ready();
            for (float f : ready) {
                all.add(f);
            }
        }
        float[] out = new float[all.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = all.get(i);
        }
        return out;
    }

    @Test
    void inOrderDeliveryYieldsContiguousStream() {
        ChunkReassembler r = new ChunkReassembler(1024);
        float[] out = drain(r, List.of(new long[]{0, 10}, new long[]{10, 10}, new long[]{20, 10}));
        assertArrayEquals(seq(0, 30), out);
    }

    @Test
    void outOfOrderDeliveryYieldsSameStreamAsInOrder() {
        List<long[]> chunks = new ArrayList<>();
        for (long start = 0; start < 1000; start += 10) {
            chunks.add(new long[]{start, 10});
        }
        List<long[]> shuffled = new ArrayList<>(chunks);
        Collections.shuffle(shuffled, new Random(42));

        float[] expected = drain(new ChunkReassembler(1 << 20), chunks);
        float[] actual = drain(new ChunkReassembler(1 << 20), shuffled);
        assertArrayEquals(expected, actual);
        assertEquals(1000, actual.length);
    }

    @Test
    void duplicatesAreIgnored() {
        ChunkReassembler r = new ChunkReassembler(1024);
        long duplicated = 0;
        // [0,10) accepted.
        duplicated += r.accept(0, seq(0, 10)).duplicatedFrames();
        // Exact redelivery of [0,10): fully duplicate.
        duplicated += r.accept(0, seq(0, 10)).duplicatedFrames();
        // [5,15): head [5,10) is duplicate, tail [10,15) is new and emitted.
        ChunkReassembler.Result overlap = r.accept(5, seq(5, 10));
        duplicated += overlap.duplicatedFrames();
        assertArrayEquals(seq(10, 5), overlap.ready());
        // [10,15) again: fully duplicate now.
        ChunkReassembler.Result last = r.accept(10, seq(10, 5));
        duplicated += last.duplicatedFrames();
        assertEquals(20, duplicated);
        assertEquals(0, last.ready().length);
        assertEquals(15, r.nextExpectedFrame());
    }

    @Test
    void overlappingBufferedChunksAreTrimmed() {
        ChunkReassembler r = new ChunkReassembler(1024);
        // Buffer a future chunk, then a duplicate overlapping it, then fill the hole.
        r.accept(20, seq(20, 10));
        // [25,35): [25,30) duplicates the buffered entry, [30,35) is new.
        r.accept(25, seq(25, 10));
        float[] ready = r.accept(0, seq(0, 20)).ready();
        assertArrayEquals(seq(0, 35), ready);
        assertEquals(35, r.nextExpectedFrame());
    }

    @Test
    void gapIsDeclaredWhenReorderBoundExceeded() {
        ChunkReassembler r = new ChunkReassembler(100);
        r.accept(0, seq(0, 10));
        ChunkReassembler.Result result = r.accept(500, seq(500, 10));
        assertEquals(10, result.gapStartFrame());
        assertEquals(490, result.gapFrames());
        assertArrayEquals(seq(500, 10), result.ready());
        assertEquals(510, r.nextExpectedFrame());
    }

    @Test
    void lateChunkInsideDeclaredGapIsDiscardedAsDuplicate() {
        ChunkReassembler r = new ChunkReassembler(100);
        r.accept(0, seq(0, 10));
        r.accept(500, seq(500, 10));
        ChunkReassembler.Result late = r.accept(50, seq(50, 10));
        assertEquals(10, late.duplicatedFrames());
        assertEquals(0, late.ready().length);
    }

    @Test
    void resetRestartsTimeline() {
        ChunkReassembler r = new ChunkReassembler(1024);
        r.accept(0, seq(0, 10));
        r.reset();
        assertEquals(0, r.nextExpectedFrame());
        float[] ready = r.accept(0, seq(0, 5)).ready();
        assertArrayEquals(seq(0, 5), ready);
    }

    @Test
    void duplicatedThenShuffledEqualsInOrder() {
        // Every chunk delivered twice, at random positions, fully shuffled.
        List<long[]> chunks = new ArrayList<>();
        for (long start = 0; start < 500; start += 13) {
            chunks.add(new long[]{start, 13});
        }
        List<long[]> messy = new ArrayList<>(chunks);
        messy.addAll(chunks);
        Collections.shuffle(messy, new Random(7));

        float[] expected = drain(new ChunkReassembler(1 << 20), chunks);
        float[] actual = drain(new ChunkReassembler(1 << 20), messy);
        assertArrayEquals(expected, actual);
    }
}
