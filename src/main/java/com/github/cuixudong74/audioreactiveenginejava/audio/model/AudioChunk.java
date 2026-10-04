package com.github.cuixudong74.audioreactiveenginejava.audio.model;

import java.util.Arrays;

/**
 * A chunk of multi-channel PCM samples arriving from the input pipeline.
 *
 * <p>Chunks may arrive out of order and may be delivered more than once.
 * Identity of a sample is defined solely by its absolute frame index, not by
 * arrival order, so reassembly is deterministic.</p>
 *
 * @param sequence   monotonically increasing delivery sequence number assigned
 *                   by the producer; used only for diagnostics, never for ordering
 * @param startFrame absolute index of the first frame of this chunk, in source
 *                   sample-rate frames
 * @param sampleRate source sample rate of this chunk in Hz (must be &gt; 0)
 * @param samples    interleaved samples, layout {@code [frame][channel]},
 *                   length must be a multiple of {@code channels}
 * @param channels   number of interleaved channels (must be &gt;= 1)
 */
public record AudioChunk(long sequence, long startFrame, int sampleRate, float[] samples, int channels) {

    public AudioChunk {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0");
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be >= 1");
        }
        if (startFrame < 0) {
            throw new IllegalArgumentException("startFrame must be >= 0");
        }
        if (samples == null || samples.length == 0 || samples.length % channels != 0) {
            throw new IllegalArgumentException("samples length must be a positive multiple of channels");
        }
        samples = Arrays.copyOf(samples, samples.length);
    }

    /** Number of frames (per channel) carried by this chunk. */
    public int frameCount() {
        return samples.length / channels;
    }

    /** Absolute index (exclusive) of the first frame after this chunk. */
    public long endFrame() {
        return startFrame + frameCount();
    }

    @Override
    public float[] samples() {
        return Arrays.copyOf(samples, samples.length);
    }

    /** Internal accessor that does not copy; for engine use only. */
    public float[] samplesRef() {
        return samples;
    }
}
