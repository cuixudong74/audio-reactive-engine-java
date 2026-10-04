package com.github.cuixudong74.audioreactiveenginejava.audio.model;

import java.util.Objects;

/**
 * A chunk of interleaved PCM samples arriving from the input pipe.
 *
 * <p>Chunks are identified by their absolute start frame index within a stream,
 * which makes delivery order and duplication irrelevant: the same logical
 * segment always carries the same {@code startFrameIndex}.</p>
 *
 * @param streamId        identifier of the logical audio stream
 * @param startFrameIndex absolute index (in sample frames) of the first frame in this chunk
 * @param sampleRate      sample rate of the chunk in Hz
 * @param channels        number of interleaved channels
 * @param samples         interleaved samples, length == frames * channels
 */
public record SampleChunk(String streamId, long startFrameIndex, int sampleRate, int channels,
                          float[] samples) {

    public SampleChunk {
        Objects.requireNonNull(streamId, "streamId");
        if (startFrameIndex < 0) {
            throw new IllegalArgumentException("startFrameIndex must be >= 0");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0");
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be > 0");
        }
        Objects.requireNonNull(samples, "samples");
        if (samples.length % channels != 0) {
            throw new IllegalArgumentException("samples length must be a multiple of channels");
        }
        samples = samples.clone();
    }

    /** Number of sample frames (per channel) carried by this chunk. */
    public int frameCount() {
        return samples.length / channels;
    }

    /** Absolute frame index just past the last frame of this chunk. */
    public long endFrameIndex() {
        return startFrameIndex + frameCount();
    }

    @Override
    public float[] samples() {
        return samples.clone();
    }
}
