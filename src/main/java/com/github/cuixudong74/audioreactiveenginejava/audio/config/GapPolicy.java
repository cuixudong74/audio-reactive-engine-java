package com.github.cuixudong74.audioreactiveenginejava.audio.config;

/**
 * How the engine handles gaps in the input frame timeline.
 *
 * <p>A gap is detected when the next expected absolute frame index is lower
 * than the start frame of the next available chunk.</p>
 */
public enum GapPolicy {
    /** Fill the gap with silence (zeros); the output timeline stays continuous. */
    ZERO_FILL,
    /** Skip the gap: drop missing frames and shift later audio forward so the
     *  output timeline stays continuous and compact. */
    SKIP
}
