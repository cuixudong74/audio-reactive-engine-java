package com.github.cuixudong74.audioreactiveenginejava.audio.analysis;

import com.github.cuixudong74.audioreactiveenginejava.audio.config.AnalysisParams;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AudioEvent;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SpectrumFrame;

import java.util.Arrays;
import java.util.List;

/**
 * Spectral-flux onset detector with an adaptive threshold.
 *
 * <p>An onset is reported when the positive spectral flux exceeds
 * {@code threshold * localMedian} and the absolute {@code noiseFloor},
 * followed by a local maximum. The median makes the threshold robust to
 * sparse strong onsets while staying tight for steady noise, so silence and
 * steady noise produce no events. A refractory period suppresses duplicates,
 * and a short warm-up (8 frames) suppresses startup false positives.</p>
 *
 * <p>The {@code onsetThreshold} parameter is the recall/precision knob:
 * larger values raise the adaptive threshold (fewer false positives, more
 * missed weak onsets), smaller values do the opposite.</p>
 */
public final class OnsetDetector {

    private static final int WARMUP_FRAMES = 8;

    private final AnalysisParams params;
    private final long refractoryFrames;
    private final double[] fluxHistory;
    private int historyCount;
    private int historyPos;

    private float[] prevMagnitudes;
    private double prevFlux;
    private double prevPrevFlux;
    private SpectrumFrame prevFrame;
    private long lastOnsetFrame = -1;

    public OnsetDetector(AnalysisParams params, int sampleRate) {
        this.params = params;
        int hop = params.hopSize();
        this.refractoryFrames =
                (long) Math.ceil(params.minOnsetIntervalSeconds() * sampleRate / hop);
        // ~0.5 s of flux history for the adaptive threshold, at least 8 frames.
        int history = (int) Math.max(8, Math.round(0.5 * sampleRate / hop));
        this.fluxHistory = new double[history];
    }

    /** Feeds one spectrum frame; returns any events it triggers (0 or 1). */
    public List<AudioEvent> onFrame(SpectrumFrame frame) {
        double flux = spectralFlux(frame.magnitudesRef());
        AudioEvent event = null;
        if (prevFrame != null && historyCount >= WARMUP_FRAMES
                && isLocalMax(prevFlux, prevPrevFlux, flux)
                && prevFlux > adaptiveThreshold()
                && refractoryOk(frame.frameIndex() - 1)) {
            event = makeOnset(prevFrame, prevFlux);
            lastOnsetFrame = prevFrame.frameIndex();
        }
        addToHistory(flux);
        prevPrevFlux = prevFlux;
        prevFlux = flux;
        prevFrame = frame;
        prevMagnitudes = frame.magnitudesRef().clone();
        return event == null ? List.of() : List.of(event);
    }

    /** Flushes a pending peak at end of stream. Idempotent. */
    public List<AudioEvent> flush() {
        if (prevFrame != null && historyCount >= WARMUP_FRAMES
                && prevFlux > prevPrevFlux && prevFlux > adaptiveThreshold()
                && refractoryOk(prevFrame.frameIndex())) {
            AudioEvent event = makeOnset(prevFrame, prevFlux);
            prevFlux = 0;
            prevPrevFlux = 0;
            prevFrame = null;
            return List.of(event);
        }
        prevFlux = 0;
        prevPrevFlux = 0;
        prevFrame = null;
        return List.of();
    }

    // ---- internals ----

    private double spectralFlux(float[] magnitudes) {
        if (prevMagnitudes == null) {
            return 0;
        }
        double flux = 0;
        int n = Math.min(magnitudes.length, prevMagnitudes.length);
        for (int k = 0; k < n; k++) {
            double diff = magnitudes[k] - prevMagnitudes[k];
            if (diff > 0) {
                flux += diff;
            }
        }
        return flux / n;
    }

    private boolean refractoryOk(long frameIndex) {
        return lastOnsetFrame < 0 || frameIndex - lastOnsetFrame >= refractoryFrames;
    }

    private boolean isLocalMax(double cur, double prev, double next) {
        return cur > prev && cur >= next;
    }

    /** Adaptive threshold: local median scaled by the configured multiplier,
     *  never below the absolute noise floor. */
    private double adaptiveThreshold() {
        return Math.max(params.noiseFloor(), medianFlux() * params.onsetThreshold());
    }

    /** Maximum flux in the history window; 0 when empty. */
    private double maxFlux() {
        double max = 0;
        for (int i = 0; i < historyCount; i++) {
            max = Math.max(max, fluxHistory[i]);
        }
        return max;
    }

    /** Median of the flux history window; 0 when empty. */
    private double medianFlux() {
        if (historyCount == 0) {
            return 0;
        }
        double[] copy = new double[historyCount];
        for (int i = 0; i < historyCount; i++) {
            copy[i] = fluxHistory[(historyPos + fluxHistory.length - historyCount + i)
                    % fluxHistory.length];
        }
        Arrays.sort(copy);
        int mid = copy.length / 2;
        return copy.length % 2 == 1 ? copy[mid] : (copy[mid - 1] + copy[mid]) / 2.0;
    }

    private void addToHistory(double flux) {
        if (historyCount < fluxHistory.length) {
            historyCount++;
        }
        fluxHistory[historyPos] = flux;
        historyPos = (historyPos + 1) % fluxHistory.length;
    }

    private AudioEvent makeOnset(SpectrumFrame frame, double fluxValue) {
        // Intensity is normalised by the strongest flux seen in the history
        // window, so strong and weak onsets keep their relative salience.
        double maxFlux = maxFlux();
        double intensity = maxFlux <= 0 ? 1.0 : Math.min(1.0, fluxValue / maxFlux);
        AudioEvent.Strength strength =
                intensity >= 0.5 ? AudioEvent.Strength.STRONG : AudioEvent.Strength.WEAK;
        // Sequence is a placeholder; the engine assigns the global sequence.
        return new AudioEvent(AudioEvent.Type.ONSET, strength, frame.timeSeconds(), intensity, -1);
    }
}
