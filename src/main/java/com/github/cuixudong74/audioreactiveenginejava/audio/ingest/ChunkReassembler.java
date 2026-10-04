package com.github.cuixudong74.audioreactiveenginejava.audio.ingest;

import com.github.cuixudong74.audioreactiveenginejava.audio.config.GapPolicy;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AudioChunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reassembles a continuous mono sample stream from chunks that may arrive
 * out of order, duplicated, or with gaps.
 *
 * <p>Determinism rules:</p>
 * <ul>
 *   <li>A sample is identified by its absolute frame index; duplicates of the
 *       same frame are written once (first write wins) and never emitted twice.</li>
 *   <li>Chunks overlapping already-emitted frames are trimmed; the output
 *       timeline never moves backward.</li>
 *   <li>Channels are mixed by arithmetic mean (deterministic).</li>
 *   <li>Gaps are materialised only at {@link #finish()} according to the
 *       configured {@link GapPolicy}, so mid-stream reordering never changes
 *       the result.</li>
 * </ul>
 */
public final class ChunkReassembler {

    private final GapPolicy gapPolicy;

    /** Buffered mono segments keyed by absolute start frame; non-overlapping. */
    private final TreeMap<Long, float[]> pending = new TreeMap<>();

    /** Contiguous materialised tail after {@link #finish()}. */
    private final Deque<float[]> tail = new ArrayDeque<>();

    /** Absolute source-frame index of the next sample to emit. */
    private long nextFrame;

    /** Read cursor inside the head segment of {@link #tail}. */
    private int tailPos;

    private boolean finished;

    public ChunkReassembler(GapPolicy gapPolicy) {
        if (gapPolicy == null) {
            throw new IllegalArgumentException("gapPolicy must not be null");
        }
        this.gapPolicy = gapPolicy;
    }

    /** Buffers a chunk; out-of-order and duplicate deliveries are safe. */
    public void accept(AudioChunk chunk) {
        if (finished) {
            // Late delivery after end-of-stream: dropped so the emitted
            // timeline and results stay stable.
            return;
        }
        float[] mono = mixToMono(chunk);
        long start = chunk.startFrame();
        long end = chunk.endFrame();
        if (end <= nextFrame) {
            // Entirely already emitted: pure duplicate, ignore.
            return;
        }
        int trim = (int) Math.max(0, nextFrame - start);
        if (trim > 0) {
            // Overlaps already-emitted frames: trim so the timeline never
            // moves backward.
            float[] trimmed = new float[mono.length - trim];
            System.arraycopy(mono, trim, trimmed, 0, trimmed.length);
            mono = trimmed;
            start = nextFrame;
        }
        insertFirstWriteWins(start, mono);
    }

    /** Number of contiguous mono samples ready to be read from the head. */
    public int available() {
        if (!finished) {
            Long headLen = headContiguousLength();
            return headLen == null ? 0 : (int) (headLen - nextFrame);
        }
        int total = 0;
        for (float[] seg : tail) {
            total += seg.length;
        }
        return total - tailPos;
    }

    /** Copies up to {@code len} contiguous mono samples into {@code dst}. */
    public int read(float[] dst, int offset, int len) {
        int read = 0;
        while (read < len) {
            float[] head = headSegment();
            if (head == null) {
                break;
            }
            int headOff = finished ? tailPos : 0;
            int n = Math.min(len - read, head.length - headOff);
            System.arraycopy(head, headOff, dst, offset + read, n);
            read += n;
            nextFrame += n;
            if (finished) {
                tailPos += n;
                if (tailPos == head.length) {
                    tail.pollFirst();
                    tailPos = 0;
                }
            } else {
                if (n == head.length) {
                    pending.pollFirstEntry();
                } else {
                    // Partial consume of a pending segment: keep the remainder.
                    float[] rest = new float[head.length - n];
                    System.arraycopy(head, n, rest, 0, rest.length);
                    pending.pollFirstEntry();
                    pending.put(nextFrame, rest);
                }
            }
        }
        return read;
    }

    /**
     * Signals end of input. Resolves any remaining gaps per the gap policy
     * and makes the tail available via {@link #read}. Idempotent.
     */
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        for (Map.Entry<Long, float[]> e : pending.entrySet()) {
            long segStart = e.getKey();
            float[] seg = e.getValue();
            if (gapPolicy == GapPolicy.ZERO_FILL && segStart > nextFrame) {
                long gap = segStart - nextFrame;
                if (gap > Integer.MAX_VALUE) {
                    throw new IllegalStateException("gap too large to zero-fill: " + gap);
                }
                tail.addLast(new float[(int) gap]);
                nextFrame = segStart;
            }
            // SKIP: segments are concatenated, dropping the missing frames.
            tail.addLast(seg);
            nextFrame = segStart + seg.length;
        }
        pending.clear();
        // Rewind the emit cursor to the head of the materialised tail.
        long remaining = 0;
        for (float[] seg : tail) {
            remaining += seg.length;
        }
        nextFrame -= remaining;
        tailPos = 0;
    }

    /** Absolute source-frame index that will be produced next. */
    public long nextFrame() {
        return nextFrame;
    }

    // ---- internals ----

    private float[] headSegment() {
        if (finished) {
            return tail.peekFirst();
        }
        Long len = headContiguousLength();
        if (len == null) {
            return null;
        }
        return pending.firstEntry().getValue();
    }

    /** Length (end frame) of the pending head segment, or null if not contiguous. */
    private Long headContiguousLength() {
        if (pending.isEmpty() || pending.firstKey() != nextFrame) {
            return null;
        }
        return pending.firstKey() + pending.firstEntry().getValue().length;
    }

    /** Deterministic channel mix: arithmetic mean across channels. */
    private static float[] mixToMono(AudioChunk chunk) {
        float[] s = chunk.samplesRef();
        int channels = chunk.channels();
        int frames = chunk.frameCount();
        float[] mono = new float[frames];
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            int base = f * channels;
            for (int c = 0; c < channels; c++) {
                sum += s[base + c];
            }
            mono[f] = (float) (sum / channels);
        }
        return mono;
    }

    /**
     * Inserts {@code [start, start+data.length)} into the pending map using
     * first-write-wins per frame, then merges adjacent segments.
     */
    private void insertFirstWriteWins(long start, float[] data) {
        long end = start + data.length;
        long cursor = start;
        int offset = 0;
        while (cursor < end) {
            Map.Entry<Long, float[]> floor = pending.floorEntry(cursor);
            if (floor != null && floor.getKey() + floor.getValue().length > cursor) {
                // Covered by an existing segment: skip the covered prefix.
                long coveredEnd = floor.getKey() + floor.getValue().length;
                int skip = (int) (Math.min(coveredEnd, end) - cursor);
                cursor += skip;
                offset += skip;
                continue;
            }
            Map.Entry<Long, float[]> ceiling = pending.ceilingEntry(cursor);
            long limit = ceiling == null ? end : Math.min(ceiling.getKey(), end);
            int len = (int) (limit - cursor);
            float[] piece = new float[len];
            System.arraycopy(data, offset, piece, 0, len);
            pending.put(cursor, piece);
            cursor += len;
            offset += len;
        }
        mergeAdjacent(start, end);
    }

    /** Merges segments that touch each other inside the affected range. */
    private void mergeAdjacent(long start, long end) {
        // Start from a predecessor segment that ends exactly at {@code start},
        // otherwise from the first segment at or after {@code start}.
        Map.Entry<Long, float[]> entry = pending.lowerEntry(start);
        if (entry == null || entry.getKey() + entry.getValue().length < start) {
            entry = pending.ceilingEntry(start);
        }
        while (entry != null && entry.getKey() <= end) {
            Map.Entry<Long, float[]> next = pending.higherEntry(entry.getKey());
            if (next != null && entry.getKey() + entry.getValue().length == next.getKey()) {
                float[] merged = new float[entry.getValue().length + next.getValue().length];
                System.arraycopy(entry.getValue(), 0, merged, 0, entry.getValue().length);
                System.arraycopy(next.getValue(), 0, merged, entry.getValue().length,
                        next.getValue().length);
                pending.remove(next.getKey());
                pending.put(entry.getKey(), merged);
                entry = pending.floorEntry(entry.getKey());
            } else {
                entry = next;
            }
        }
    }
}
