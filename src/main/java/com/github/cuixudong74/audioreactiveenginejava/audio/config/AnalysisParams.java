package com.github.cuixudong74.audioreactiveenginejava.audio.config;

/**
 * Hot-updatable analysis parameters.
 *
 * <p>A new instance installed via
 * {@code AudioEngine.updateAnalysisParams} applies only to samples that have
 * not yet been framed; frames and events already emitted are never rewritten.
 * The change takes effect at the next frame boundary.</p>
 *
 * @param windowSize        analysis window size in samples (power of two)
 * @param windowType        window function applied before the FFT
 * @param overlap           window overlap ratio in [0, 0.875]; hop =
 *                          {@code windowSize * (1 - overlap)}
 * @param onsetThreshold    adaptive-threshold multiplier for spectral-flux
 *                          onset detection; higher means fewer false positives
 *                          and more missed detections (recall/precision knob)
 * @param noiseFloor        absolute flux floor; flux below this value (silence,
 *                          steady noise) never produces events
 * @param minOnsetIntervalSeconds refractory period between two onsets
 */
public record AnalysisParams(
        int windowSize,
        WindowType windowType,
        double overlap,
        double onsetThreshold,
        double noiseFloor,
        double minOnsetIntervalSeconds) {

    public AnalysisParams {
        if (windowSize < 16 || (windowSize & (windowSize - 1)) != 0) {
            throw new IllegalArgumentException("windowSize must be a power of two >= 16");
        }
        if (windowType == null) {
            throw new IllegalArgumentException("windowType must not be null");
        }
        if (overlap < 0.0 || overlap > 0.875) {
            throw new IllegalArgumentException("overlap must be in [0, 0.875]");
        }
        if (onsetThreshold <= 0.0) {
            throw new IllegalArgumentException("onsetThreshold must be > 0");
        }
        if (noiseFloor < 0.0) {
            throw new IllegalArgumentException("noiseFloor must be >= 0");
        }
        if (minOnsetIntervalSeconds < 0.0) {
            throw new IllegalArgumentException("minOnsetIntervalSeconds must be >= 0");
        }
    }

    /** Hop size in samples; always >= windowSize/8 and >= 1. */
    public int hopSize() {
        return Math.max(1, (int) Math.round(windowSize * (1.0 - overlap)));
    }

    /** Reasonable defaults for 44.1 kHz audio. */
    public static AnalysisParams defaults() {
        return new AnalysisParams(1024, WindowType.HANN, 0.5, 1.5, 1e-4, 0.05);
    }
}
