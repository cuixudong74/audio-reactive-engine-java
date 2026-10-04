package com.github.cuixudong74.audioreactiveenginejava.audio.pipeline;

import java.util.Map;
import java.util.TreeMap;

/**
 * Reassembles a contiguous mono sample stream from chunks that may arrive
 * out of order, duplicated, or partially overlapping.
 *
 * <p>Chunks are keyed on their absolute frame index, so the emitted stream
 * depends only on the logical sample content, never on delivery order or
 * duplication: re-delivering the whole stream in any order and with any
 * number of duplicates yields exactly the same output as one in-order pass.</p>
 *
 * <p>Buffering is bounded by {@code maxReorderFrames}: when the distance
 * between the next expected frame and the earliest buffered frame exceeds
 * the bound, the missing range is declared a gap (reported via
 * {@link Result#gapStartFrame()}/{@link Result#gapFrames()}) and assembly
 * continues from the earliest buffered frame. This keeps memory bounded and
 * gives the pipeline a bounded wait for late chunks.</p>
 */
public final class ChunkReassembler {

    /**
     * Outcome of one {@link #accept} call.
     *
     * @param ready            contiguous mono samples ready for downstream processing (may be empty)
     * @param gapStartFrame    absolute frame index where a declared gap starts, or -1
     * @param gapFrames        number of missing frames in the declared gap (0 when gapStartFrame == -1)
     * @param duplicatedFrames frames discarded because they were already seen (duplicates/overlaps)
     */
    public record Result(float[] ready, long gapStartFrame, long gapFrames, long duplicatedFrames) {
        static final Result NONE = new Result(new float[0], -1, 0, 0);
    }

    private final long maxReorderFrames;
    /** Absolute index of the next frame the downstream expects. */
    private long nextExpected;
    /** Out-of-order buffer: absolute start frame index -> mono frames. Entries never overlap. */
    private final TreeMap<Long, float[]> pending = new TreeMap<>();

    public ChunkReassembler(long maxReorderFrames) {
        if (maxReorderFrames <= 0) {
            throw new IllegalArgumentException("maxReorderFrames must be > 0");
        }
        this.maxReorderFrames = maxReorderFrames;
    }

    /** Absolute index of the next frame the downstream expects. */
    public long nextExpectedFrame() {
        return nextExpected;
    }

    /**
     * Accepts one chunk of mono frames starting at {@code startFrame}.
     * Never throws on duplicate, overlapping or out-of-order input.
     */
    public Result accept(long startFrame, float[] monoFrames) {
        if (monoFrames.length == 0) {
            return Result.NONE;
        }
        long duplicated = 0;
        long start = startFrame;
        float[] samples = monoFrames;

        // 1. Trim everything that is already in the past (duplicates / overlaps).
        if (start < nextExpected) {
            long trim = Math.min(nextExpected - start, samples.length);
            duplicated += trim;
            start += trim;
            samples = slice(samples, (int) trim, samples.length);
            if (samples.length == 0) {
                return new Result(new float[0], -1, 0, duplicated);
            }
        }

        long gapStart = -1;
        long gapFrames = 0;
        float[] ready;

        if (start == nextExpected) {
            // 2a. Contiguous: emit directly, then drain whatever is now reachable.
            nextExpected = start + samples.length;
            ready = drain(samples);
        } else {
            // 2b. Future chunk: buffer it, clipping against already buffered entries.
            duplicated += insertBuffered(start, samples);
            // 3. Bounded wait: if the hole in front of the buffer is too large, declare a gap.
            long earliest = pending.firstKey();
            if (earliest - nextExpected > maxReorderFrames) {
                gapStart = nextExpected;
                gapFrames = earliest - nextExpected;
                nextExpected = earliest;
                ready = drain(new float[0]);
            } else {
                ready = new float[0];
            }
        }
        return new Result(ready, gapStart, gapFrames, duplicated);
    }

    /**
     * Inserts {@code samples} (starting at absolute frame {@code start}) into the
     * pending buffer without overlapping existing entries. Returns the number of
     * frames discarded as duplicates.
     */
    private long insertBuffered(long start, float[] samples) {
        long duplicated = 0;
        long end = start + samples.length;
        float[] current = samples;
        while (current.length > 0) {
            Map.Entry<Long, float[]> floor = pending.floorEntry(start);
            if (floor != null && floor.getKey() + floor.getValue().length > start) {
                // Head overlaps an earlier buffered entry: trim the head.
                long trimTo = floor.getKey() + floor.getValue().length;
                long trim = Math.min(trimTo - start, current.length);
                duplicated += trim;
                start += trim;
                current = slice(current, (int) trim, current.length);
                continue;
            }
            Map.Entry<Long, float[]> ceiling = pending.ceilingEntry(start);
            if (ceiling != null && ceiling.getKey() < start + current.length) {
                // Tail runs into a later buffered entry: buffer only up to it, then continue.
                int pieceLen = (int) (ceiling.getKey() - start);
                if (pieceLen > 0) {
                    pending.put(start, slice(current, 0, pieceLen));
                }
                long consumed = pieceLen;
                start += consumed;
                current = slice(current, (int) consumed, current.length);
                continue;
            }
            pending.put(start, current);
            break;
        }
        return duplicated;
    }

    /**
     * Emits {@code head} followed by all buffered entries that are now contiguous
     * with {@link #nextExpected}, advancing it past them.
     */
    private float[] drain(float[] head) {
        int total = head.length;
        for (Map.Entry<Long, float[]> e : pending.entrySet()) {
            if (e.getKey() != nextExpected + (total - head.length)) {
                break;
            }
            total += e.getValue().length;
        }
        float[] out = new float[total];
        System.arraycopy(head, 0, out, 0, head.length);
        int offset = head.length;
        long cursor = nextExpected - 0; // nextExpected already advanced past head
        while (!pending.isEmpty() && pending.firstKey() == cursor) {
            float[] seg = pending.pollFirstEntry().getValue();
            System.arraycopy(seg, 0, out, offset, seg.length);
            offset += seg.length;
            cursor += seg.length;
        }
        nextExpected = cursor;
        return out;
    }

    /** Clears all buffered state (used on engine restart). */
    public void reset() {
        pending.clear();
        nextExpected = 0;
    }

    private static float[] slice(float[] a, int from, int to) {
        float[] out = new float[to - from];
        System.arraycopy(a, from, out, 0, to - from);
        return out;
    }
}
