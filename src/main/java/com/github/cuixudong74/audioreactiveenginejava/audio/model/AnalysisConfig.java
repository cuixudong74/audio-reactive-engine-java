package com.github.cuixudong74.audioreactiveenginejava.audio.model;

/**
 * Immutable analysis configuration. Hot updates replace the whole object;
 * the engine tags every emitted event with the {@link #version()} that
 * produced it, so the effective range of each config is explainable.
 *
 * @param version              monotonically increasing config version assigned by the engine
 * @param targetSampleRate     analysis sample rate; input is deterministically resampled to it
 * @param windowSize           FFT window size in samples (must be a power of two)
 * @param windowType           window function applied before the FFT
 * @param overlapRatio         fraction of overlap between consecutive windows, [0, 0.9]
 * @param silenceThresholdRms  RMS below which the signal is treated as silence (no events)
 * @param onsetSensitivity     adaptive-threshold multiplier for spectral-flux onsets; higher = fewer false positives, more misses
 * @param onsetFluxFloor       absolute floor for the onset threshold, so onsets can fire out of silence
 * @param strongBeatRatio      flux/threshold ratio above which an onset is reported as a strong beat
 * @param minOnsetIntervalMs   refractory period between two onsets
 * @param gapPolicy            how sample gaps in the input timeline are handled
 * @param maxReorderFrames     bound on out-of-order buffering, in sample frames (backpressure)
 * @param inputQueueCapacity   bound on the engine input queue (backpressure)
 * @param outputQueueCapacity  bound on the engine output queue (backpressure)
 */
public record AnalysisConfig(
        long version,
        int targetSampleRate,
        int windowSize,
        WindowType windowType,
        double overlapRatio,
        double silenceThresholdRms,
        double onsetSensitivity,
        double onsetFluxFloor,
        double strongBeatRatio,
        long minOnsetIntervalMs,
        GapPolicy gapPolicy,
        int maxReorderFrames,
        int inputQueueCapacity,
        int outputQueueCapacity) {

    public AnalysisConfig {
        if (windowSize <= 0 || (windowSize & (windowSize - 1)) != 0) {
            throw new IllegalArgumentException("windowSize must be a positive power of two");
        }
        if (targetSampleRate <= 0) {
            throw new IllegalArgumentException("targetSampleRate must be > 0");
        }
        if (overlapRatio < 0.0 || overlapRatio > 0.9) {
            throw new IllegalArgumentException("overlapRatio must be in [0, 0.9]");
        }
        if (silenceThresholdRms < 0) {
            throw new IllegalArgumentException("silenceThresholdRms must be >= 0");
        }
        if (onsetSensitivity <= 0) {
            throw new IllegalArgumentException("onsetSensitivity must be > 0");
        }
        if (onsetFluxFloor < 0) {
            throw new IllegalArgumentException("onsetFluxFloor must be >= 0");
        }
        if (strongBeatRatio <= 1.0) {
            throw new IllegalArgumentException("strongBeatRatio must be > 1.0");
        }
        if (minOnsetIntervalMs < 0) {
            throw new IllegalArgumentException("minOnsetIntervalMs must be >= 0");
        }
        if (maxReorderFrames <= 0 || inputQueueCapacity <= 0 || outputQueueCapacity <= 0) {
            throw new IllegalArgumentException("queue/buffer capacities must be > 0");
        }
    }

    /** Hop size in samples derived from window size and overlap ratio. Always >= 1. */
    public int hopSize() {
        return Math.max(1, (int) Math.round(windowSize * (1.0 - overlapRatio)));
    }

    /** Default configuration, version 0. */
    public static AnalysisConfig defaults() {
        return new AnalysisConfig(0, 44100, 1024, WindowType.HANN, 0.5,
                1.0e-4, 1.5, 1.0, 2.0, 60, GapPolicy.FILL_SILENCE,
                1 << 20, 256, 1024);
    }

    /** Returns a copy with the given version. */
    public AnalysisConfig withVersion(long newVersion) {
        return new AnalysisConfig(newVersion, targetSampleRate, windowSize, windowType, overlapRatio,
                silenceThresholdRms, onsetSensitivity, onsetFluxFloor, strongBeatRatio, minOnsetIntervalMs,
                gapPolicy, maxReorderFrames, inputQueueCapacity, outputQueueCapacity);
    }

    public enum WindowType { RECTANGLE, HANN, HAMMING, BLACKMAN }

    public enum GapPolicy {
        /** Missing samples are replaced by zeros; the output timeline stays continuous. */
        FILL_SILENCE,
        /** Missing samples are skipped; a {@link GapNotice} is emitted and time keeps advancing by received samples only. */
        SKIP
    }
}
