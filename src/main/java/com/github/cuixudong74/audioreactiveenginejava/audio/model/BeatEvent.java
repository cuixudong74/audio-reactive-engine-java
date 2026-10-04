package com.github.cuixudong74.audioreactiveenginejava.audio.model;

/**
 * An onset or strong beat.
 *
 * @param timeSeconds   time of the onset on the output timeline
 * @param strength      normalized strength (flux / adaptive threshold), always >= 1
 * @param kind          ONSET for weak onsets, BEAT for strong beats
 * @param configVersion config version that produced this event
 */
public record BeatEvent(double timeSeconds, double strength, Kind kind,
                        long configVersion) implements EngineEvent {

    public enum Kind { ONSET, BEAT }
}
