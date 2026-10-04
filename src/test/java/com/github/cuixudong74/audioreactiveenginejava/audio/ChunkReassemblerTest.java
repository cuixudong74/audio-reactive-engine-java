package com.github.cuixudong74.audioreactiveenginejava.audio;

import com.github.cuixudong74.audioreactiveenginejava.audio.config.GapPolicy;
import com.github.cuixudong74.audioreactiveenginejava.audio.ingest.ChunkReassembler;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AudioChunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkReassemblerTest {

    private static AudioChunk monoChunk(long seq, long start, float... samples) {
        return new AudioChunk(seq, start, 44100, samples, 1);
    }

    private static float[] range(int from, int toExclusive) {
        float[] out = new float[toExclusive - from];
        for (int i = 0; i < out.length; i++) {
            out[i] = from + i;
        }
        return out;
    }

    private static float[] drainAll(ChunkReassembler r) {
        float[] out = new float[r.available()];
        int n = r.read(out, 0, out.length);
        assertEquals(out.length, n);
        return out;
    }

    @Test
    void inOrderChunksConcatenate() {
        ChunkReassembler r = new ChunkReassembler(GapPolicy.ZERO_FILL);
        r.accept(monoChunk(0, 0, range(0, 4)));
        r.accept(monoChunk(1, 4, range(4, 8)));
        assertArrayEquals(range(0, 8), drainAll(r));
        assertEquals(8, r.nextFrame());
    }

    @Test
    void outOfOrderChunksReassembleToSameStream() {
        ChunkReassembler r = new ChunkReassembler(GapPolicy.ZERO_FILL);
        r.accept(monoChunk(1, 4, range(4, 8)));
        assertEquals(0, r.available(), "head not contiguous yet");
        r.accept(monoChunk(0, 0, range(0, 4)));
        assertArrayEquals(range(0, 8), drainAll(r));
    }

    @Test
    void duplicateChunksAreEmittedOnce() {
        ChunkReassembler r = new ChunkReassembler(GapPolicy.ZERO_FILL);
        AudioChunk c = monoChunk(0, 0, range(0, 4));
        r.accept(c);
        r.accept(c);
        r.accept(monoChunk(2, 0, range(0, 4)));
        r.accept(monoChunk(3, 2, range(2, 6)));
        assertArrayEquals(range(0, 6), drainAll(r));
        assertEquals(0, r.available());
    }

    @Test
    void chunkOverlappingEmittedFramesIsTrimmed() {
        ChunkReassembler r = new ChunkReassembler(GapPolicy.ZERO_FILL);
        r.accept(monoChunk(0, 0, range(0, 4)));
        assertArrayEquals(range(0, 4), drainAll(r));
        // Late duplicate overlapping already-emitted frames must not rewind.
        r.accept(monoChunk(1, 2, range(2, 6)));
        assertEquals(4, r.nextFrame());
        assertArrayEquals(range(4, 6), drainAll(r));
    }

    @Test
    void zeroFillGapPolicyInsertsSilenceAtFinish() {
        ChunkReassembler r = new ChunkReassembler(GapPolicy.ZERO_FILL);
        r.accept(monoChunk(0, 0, range(0, 2)));
        r.accept(monoChunk(1, 5, range(5, 7)));
        assertArrayEquals(range(0, 2), drainAll(r));
        r.finish();
        assertArrayEquals(new float[]{0, 0, 0, 5, 6}, drainAll(r));
    }

    @Test
    void skipGapPolicyCompactsTimelineAtFinish() {
        ChunkReassembler r = new ChunkReassembler(GapPolicy.SKIP);
        r.accept(monoChunk(0, 0, range(0, 2)));
        r.accept(monoChunk(1, 5, range(5, 7)));
        assertArrayEquals(range(0, 2), drainAll(r));
        r.finish();
        assertArrayEquals(new float[]{5, 6}, drainAll(r));
    }

    @Test
    void gapFillingIsIndependentOfArrivalOrder() {
        for (GapPolicy policy : GapPolicy.values()) {
            ChunkReassembler a = new ChunkReassembler(policy);
            a.accept(monoChunk(0, 0, range(0, 2)));
            a.accept(monoChunk(1, 5, range(5, 7)));
            a.finish();
            ChunkReassembler b = new ChunkReassembler(policy);
            b.accept(monoChunk(1, 5, range(5, 7)));
            b.accept(monoChunk(0, 0, range(0, 2)));
            b.finish();
            float[] fa = drainAll(a);
            float[] fb = drainAll(b);
            assertArrayEquals(fa, fb, policy.name());
        }
    }

    @Test
    void multiChannelMixIsArithmeticMean() {
        ChunkReassembler r = new ChunkReassembler(GapPolicy.ZERO_FILL);
        // 2 frames, 2 channels: (1,3) and (2,8)
        r.accept(new AudioChunk(0, 0, 44100, new float[]{1, 3, 2, 8}, 2));
        assertArrayEquals(new float[]{2, 5}, drainAll(r));
    }

    @Test
    void chunksAfterFinishAreDropped() {
        ChunkReassembler r = new ChunkReassembler(GapPolicy.ZERO_FILL);
        r.accept(monoChunk(0, 0, range(0, 2)));
        r.finish();
        r.accept(monoChunk(1, 2, range(2, 4)));
        // Only the pre-finish data is emitted; the late chunk is dropped.
        assertArrayEquals(range(0, 2), drainAll(r));
    }
}
