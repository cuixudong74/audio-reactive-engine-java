package com.github.cuixudong74.audioreactiveenginejava.audio.engine;

import com.github.cuixudong74.audioreactiveenginejava.audio.model.AudioEvent;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SpectrumFrame;

/**
 * Atomic unit of engine output: either a complete spectrum frame or a
 * complete event. Never a partial frame or half an event.
 */
public sealed interface EngineOutput {

    record Frame(SpectrumFrame frame) implements EngineOutput {
    }

    record Event(AudioEvent event) implements EngineOutput {
    }
}
