package com.github.cuixudong74.audioreactiveenginejava.audio.dsp;

/**
 * Incremental linear-interpolation resampler for a contiguous mono stream.
 *
 * <p>Output sample {@code i} always maps to absolute source position
 * {@code i * srcRate / dstRate}, independent of how the input is split into
 * feeds. Feeding the same stream in different chunkings therefore yields
 * exactly the same output as resampling the whole stream at once with
 * {@link Resampler#resampleLinear}.</p>
 */
public final class StreamResampler {

    private final int srcRate;
    private final int dstRate;

    /** Next output sample index to produce. */
    private long nextOut;

    /** Absolute source index of {@code buf[0]}. */
    private long base;

    /** Total number of source samples fed so far. */
    private long totalFed;

    private float[] buf = new float[1024];
    private int bufLen;

    public StreamResampler(int srcRate, int dstRate) {
        if (srcRate <= 0 || dstRate <= 0) {
            throw new IllegalArgumentException("rates must be > 0");
        }
        this.srcRate = srcRate;
        this.dstRate = dstRate;
    }

    /**
     * Feeds the next contiguous source samples and returns all output samples
     * that can now be produced.
     *
     * @param finish when true, the tail is flushed using the same clamped
     *               interpolation as the whole-stream resampler
     */
    public float[] feed(float[] src, int offset, int length, boolean finish) {
        append(src, offset, length);
        totalFed += length;
        long available = totalFed;
        long maxOut;
        if (finish) {
            maxOut = available * dstRate / srcRate;
        } else {
            // Emit only outputs whose interpolation neighbourhood is fully
            // buffered: floor(i * src/dst) + 1 <= available - 1.
            maxOut = nextOut;
            while (sourceIndex(maxOut) + 1 <= available - 1) {
                maxOut++;
            }
        }
        int count = (int) (maxOut - nextOut);
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            long idx = sourceIndex(nextOut);
            double pos = (double) nextOut * srcRate / dstRate;
            double frac = pos - idx;
            float a = sampleAt(idx, available);
            float b = sampleAt(Math.min(idx + 1, available - 1), available);
            out[i] = (float) (a * (1.0 - frac) + b * frac);
            nextOut++;
        }
        discardConsumed();
        return out;
    }

    private long sourceIndex(long outIndex) {
        return outIndex * srcRate / dstRate;
    }

    private float sampleAt(long absoluteIndex, long available) {
        if (absoluteIndex >= available) {
            absoluteIndex = available - 1;
        }
        int i = (int) (absoluteIndex - base);
        return buf[i];
    }

    private void append(float[] src, int offset, int length) {
        if (length == 0) {
            return;
        }
        ensureCapacity(bufLen + length);
        System.arraycopy(src, offset, buf, bufLen, length);
        bufLen += length;
    }

    /** Drops buffered source samples that no future output can reference. */
    private void discardConsumed() {
        long needed = sourceIndex(nextOut);
        int drop = (int) Math.min(needed - base, bufLen);
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop);
            bufLen -= drop;
            base += drop;
        }
    }

    private void ensureCapacity(int needed) {
        if (buf.length < needed) {
            int newCap = Math.max(needed, buf.length * 2);
            float[] next = new float[newCap];
            System.arraycopy(buf, 0, next, 0, bufLen);
            buf = next;
        }
    }
}
