package com.github.cuixudong74.audioreactiveenginejava.audio.model;

/**
 * Signals a detected discontinuity in the input sample timeline.
 *
 * @param timeSeconds    time at which the gap starts on the output timeline
 * @param missingFrames  number of missing sample frames (at the input sample rate)
 * @param configVersion  config version in effect when the gap was detected
 */
public record GapNotice(double timeSeconds, long missingFrames,
                        long configVersion) implements EngineEvent {
}
