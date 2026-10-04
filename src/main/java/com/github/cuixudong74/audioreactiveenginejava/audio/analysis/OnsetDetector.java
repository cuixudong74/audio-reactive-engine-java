package com.github.cuixudong74.audioreactiveenginejava.audio.analysis;

import com.github.cuixudong74.audioreactiveenginejava.audio.model.AnalysisConfig;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.BeatEvent;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SpectrumFrame;

import java.util.List;

/**
 * Spectral-flux onset detector with an adaptive threshold.
 *
 * <p>Model: for each frame the positive difference of log-magnitudes per bin
 * is summed into a flux value. An onset is declared when the flux exceeds
 * {@code max(onsetSensitivity * mean(recentFlux), onsetFluxFloor)}. The
 * recent-flux history is a fixed-size ring (32 frames) updated with every
 * frame, including silent ones, so the threshold adapts to the local energy
 * dynamics; the absolute floor guarantees that onsets can still fire out of
 * complete silence (where the adaptive term is zero).</p>
 *
 * <p>False-trigger suppression:</p>
 * <ul>
 *   <li>Silence gate: frames whose RMS is below {@code silenceThresholdRms}
 *       never produce events, so pure silence and sub-threshold noise cannot
 *       trigger onsets.</li>
 *   <li>Refractory period: after an onset, no further onset is reported for
 *       {@code minOnsetIntervalMs}.</li>
 *   <li>Log-magnitude compression keeps steady broadband noise from
 *       dominating the flux.</li>
 * </ul>
 *
 * <p>Trade-off knob: {@code onsetSensitivity} — higher values reduce false
 * positives at the cost of more missed weak onsets; lower values do the
 * opposite. Events with {@code strength >= strongBeatRatio} are classified
 * as {@link BeatEvent.Kind#BEAT}, weaker ones as {@link BeatEvent.Kind#ONSET}.</p>
 */
public final class OnsetDetector {

    private static final int HISTORY_SIZE = 32;

    private AnalysisConfig config;

    private float[] previousLogMagnitudes;
    private final double[] fluxHistory = new double[HISTORY_SIZE];
    private int fluxHistoryLength;
    private int fluxHistoryCursor;
    private double fluxHistorySum;
    private long lastOnsetFrameIndex = Long.MIN_VALUE;

    public OnsetDetector(AnalysisConfig config) {
        this.config = config;
    }

    /**
     * Consumes one spectrum frame; returns any beat event triggered by it
     * (at most one per frame).
     */
    public List<BeatEvent> process(SpectrumFrame frame) {
        float[] magnitudes = frame.magnitudes();
        double[] logMag = new double[magnitudes.length];
        for (int i = 0; i < magnitudes.length; i++) {
            logMag[i] = Math.log1p(magnitudes[i]);
        }

        double flux = 0.0;
        if (previousLogMagnitudes != null) {
            int n = Math.min(logMag.length, previousLogMagnitudes.length);
            for (int i = 0; i < n; i++) {
                double diff = logMag[i] - previousLogMagnitudes[i];
                if (diff > 0) {
                    flux += diff;
                }
            }
        }
        float[] prev = new float[logMag.length];
        for (int i = 0; i < logMag.length; i++) {
            prev[i] = (float) logMag[i];
        }
        previousLogMagnitudes = prev;

        double threshold = Math.max(config.onsetSensitivity() * meanFlux(), config.onsetFluxFloor());
        pushFlux(flux);

        boolean silent = frame.rmsEnergy() < config.silenceThresholdRms();
        boolean refractory = lastOnsetFrameIndex != Long.MIN_VALUE
                && frame.frameIndex() - lastOnsetFrameIndex < minIntervalFrames();
        if (silent || refractory || threshold <= 0.0 || flux <= threshold) {
            return List.of();
        }
        lastOnsetFrameIndex = frame.frameIndex();
        double strength = flux / threshold;
        BeatEvent.Kind kind = strength >= config.strongBeatRatio()
                ? BeatEvent.Kind.BEAT : BeatEvent.Kind.ONSET;
        return List.of(new BeatEvent(frame.timeSeconds(), strength, kind, config.version()));
    }

    /**
     * Applies a new config to subsequent frames. The adaptive-threshold flux
     * history is preserved; the per-bin spectral memory is dropped only when
     * the window size (and thus the bin count) changed. Frames already
     * emitted are, of course, never rewritten.
     */
    public void updateConfig(AnalysisConfig newConfig) {
        if (newConfig.windowSize() != config.windowSize()) {
            previousLogMagnitudes = null;
        }
        this.config = newConfig;
    }

    /** Clears detector state (used on engine restart). */
    public void reset() {
        previousLogMagnitudes = null;
        fluxHistoryLength = 0;
        fluxHistoryCursor = 0;
        fluxHistorySum = 0.0;
        lastOnsetFrameIndex = Long.MIN_VALUE;
    }

    private double meanFlux() {
        if (fluxHistoryLength == 0) {
            return 0.0;
        }
        return fluxHistorySum / fluxHistoryLength;
    }

    private void pushFlux(double flux) {
        if (fluxHistoryLength < HISTORY_SIZE) {
            fluxHistory[fluxHistoryCursor] = flux;
            fluxHistorySum += flux;
            fluxHistoryLength++;
        } else {
            fluxHistorySum -= fluxHistory[fluxHistoryCursor];
            fluxHistory[fluxHistoryCursor] = flux;
            fluxHistorySum += flux;
        }
        fluxHistoryCursor = (fluxHistoryCursor + 1) % HISTORY_SIZE;
    }

    private long minIntervalFrames() {
        double framesPerMs = config.targetSampleRate() / (1000.0 * config.hopSize());
        return (long) Math.ceil(config.minOnsetIntervalMs() * framesPerMs);
    }
}
