package com.github.cuixudong74.audioreactiveenginejava.audio.model;

/**
 * One immutable spectrum analysis result for a single window.
 *
 * <p>Frames are the atomic output unit of the engine: a frame is either
 * emitted completely or not at all, never partially.</p>
 *
 * @param frameIndex  monotonically increasing index of this analysis frame,
 *                    starting at 0 for each engine run
 * @param timeSeconds position of the window start on the output timeline, in
 *                    seconds; guaranteed non-decreasing across frames
 * @param magnitudes  magnitude spectrum, length {@code windowSize / 2 + 1}
 * @param energy      total energy (sum of squared windowed samples)
 */
public record SpectrumFrame(long frameIndex, double timeSeconds, float[] magnitudes, double energy) {

    public SpectrumFrame {
        magnitudes = magnitudes.clone();
    }

    @Override
    public float[] magnitudes() {
        return magnitudes.clone();
    }

    /** Internal accessor that does not copy; for engine use only. */
    public float[] magnitudesRef() {
        return magnitudes;
    }
}
