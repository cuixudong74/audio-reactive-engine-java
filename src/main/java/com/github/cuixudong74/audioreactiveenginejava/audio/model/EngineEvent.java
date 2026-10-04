package com.github.cuixudong74.audioreactiveenginejava.audio.model;

/**
 * Base type for everything the engine emits. Events are immutable and atomic:
 * cancellation or timeout can never produce half of an event.
 */
public sealed interface EngineEvent permits SpectrumFrame, BeatEvent, GapNotice {

    /** Time of the event on the output timeline, in seconds. Monotonically non-decreasing. */
    double timeSeconds();

    /** Version of the {@link AnalysisConfig} that was in effect when this event was produced. */
    long configVersion();
}
