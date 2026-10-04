package com.github.cuixudong74.audioreactiveenginejava.audio.config;

/**
 * Backpressure policy applied when the bounded output queue is full.
 */
public enum OverflowPolicy {
    /** Block the producer until the consumer makes room or the offer timeout
     *  elapses; on timeout the engine stops with an error instead of growing
     *  memory without bound. */
    BLOCK,
    /** Drop the oldest queued item to make room for the newest one. */
    DROP_OLDEST
}
