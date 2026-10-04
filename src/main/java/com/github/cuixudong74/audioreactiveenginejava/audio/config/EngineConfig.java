package com.github.cuixudong74.audioreactiveenginejava.audio.config;

import java.time.Duration;

/**
 * Static engine configuration, fixed for the lifetime of one engine run.
 *
 * @param sampleRate          engine (output) sample rate in Hz; input chunks at
 *                            other rates are deterministically resampled
 * @param outputQueueCapacity capacity of the bounded output queue (backpressure)
 * @param overflowPolicy      policy applied when the output queue is full
 * @param offerTimeout        max time a producer waits for queue room under
 *                            {@link OverflowPolicy#BLOCK} before the engine
 *                            stops with an error
 * @param gapPolicy           how input timeline gaps are handled
 */
public record EngineConfig(
        int sampleRate,
        int outputQueueCapacity,
        OverflowPolicy overflowPolicy,
        Duration offerTimeout,
        GapPolicy gapPolicy) {

    public EngineConfig {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0");
        }
        if (outputQueueCapacity <= 0) {
            throw new IllegalArgumentException("outputQueueCapacity must be > 0");
        }
        if (overflowPolicy == null || gapPolicy == null || offerTimeout == null) {
            throw new IllegalArgumentException("policies and offerTimeout must not be null");
        }
        if (offerTimeout.isNegative()) {
            throw new IllegalArgumentException("offerTimeout must be >= 0");
        }
    }

    public static EngineConfig defaults(int sampleRate) {
        return new EngineConfig(sampleRate, 256, OverflowPolicy.BLOCK,
                Duration.ofSeconds(5), GapPolicy.ZERO_FILL);
    }
}
