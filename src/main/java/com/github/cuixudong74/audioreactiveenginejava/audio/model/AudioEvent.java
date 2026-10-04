package com.github.cuixudong74.audioreactiveenginejava.audio.model;

/**
 * A discrete musical event emitted by onset / beat detection.
 *
 * @param type        ONSET for detected note attacks, BEAT for tracked beats
 * @param strength    STRONG or WEAK; strong beats align with the tracked meter
 *                    and carry high onset support
 * @param timeSeconds event position on the output timeline in seconds;
 *                    non-decreasing across the event stream
 * @param intensity   normalised intensity in (0, 1]; higher means a more
 *                    salient event
 * @param sequence    monotonically increasing event id within one engine run
 */
public record AudioEvent(Type type, Strength strength, double timeSeconds, double intensity, long sequence) {

    public enum Type {
        ONSET,
        BEAT
    }

    public enum Strength {
        STRONG,
        WEAK
    }
}
