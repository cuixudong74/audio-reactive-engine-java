package com.github.cuixudong74.audioreactiveenginejava.audio.model;

/**
 * One spectrum analysis frame.
 *
 * @param frameIndex    index of the frame on the output timeline (0-based, hopSize apart)
 * @param timeSeconds   time of the first sample of the window, in seconds
 * @param magnitudes    magnitude spectrum, length == windowSize / 2 + 1
 * @param rmsEnergy     RMS energy of the windowed frame
 * @param configVersion config version that produced this frame
 */
public record SpectrumFrame(long frameIndex, double timeSeconds, float[] magnitudes, double rmsEnergy,
                            long configVersion) implements EngineEvent {

    public SpectrumFrame {
        magnitudes = magnitudes.clone();
    }

    @Override
    public float[] magnitudes() {
        return magnitudes.clone();
    }
}
