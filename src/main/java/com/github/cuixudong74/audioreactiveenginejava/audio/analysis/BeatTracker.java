package com.github.cuixudong74.audioreactiveenginejava.audio.analysis;

import com.github.cuixudong74.audioreactiveenginejava.audio.model.AudioEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Derives beat events from the onset stream.
 *
 * <p>Tempo is estimated from the median inter-onset interval (clamped to
 * [0.25, 2.0] s, i.e. 30-240 BPM) once at least 4 onsets have been observed.
 * Onsets landing within 15% of a beat period of the predicted grid are
 * reported as beats; a beat is STRONG when its supporting onset intensity is
 * at least the configured threshold, WEAK otherwise. Before tempo lock no
 * beats are emitted, so noise cannot fabricate a beat grid. All decisions
 * depend only on the onset stream, so results are reproducible.</p>
 */
public final class BeatTracker {

    private static final double MIN_PERIOD = 0.25;
    private static final double MAX_PERIOD = 2.0;
    private static final int LOCK_ONSETS = 4;
    private static final double GRID_TOLERANCE_RATIO = 0.15;

    private final double strongIntensityThreshold;
    private final List<Double> onsetTimes = new ArrayList<>();
    private boolean locked;

    public BeatTracker(double strongIntensityThreshold) {
        if (strongIntensityThreshold <= 0 || strongIntensityThreshold > 1) {
            throw new IllegalArgumentException("strongIntensityThreshold must be in (0, 1]");
        }
        this.strongIntensityThreshold = strongIntensityThreshold;
    }

    /** Feeds one onset event; returns any beat events it confirms. */
    public List<AudioEvent> onOnset(AudioEvent onset) {
        onsetTimes.add(onset.timeSeconds());
        if (!locked) {
            if (onsetTimes.size() >= LOCK_ONSETS) {
                locked = true;
            } else {
                return List.of();
            }
        }
        double period = estimatePeriod();
        // Local alignment: the interval from the previous onset must be close
        // to a multiple of the beat period. This avoids cumulative grid drift
        // and keeps the decision deterministic.
        double ioi = onset.timeSeconds()
                - onsetTimes.get(onsetTimes.size() - 2);
        double ratio = ioi / period;
        double distance = Math.abs(ratio - Math.round(ratio)) * period;
        if (distance > GRID_TOLERANCE_RATIO * period) {
            return List.of();
        }
        AudioEvent.Strength strength = onset.intensity() >= strongIntensityThreshold
                ? AudioEvent.Strength.STRONG
                : AudioEvent.Strength.WEAK;
        // Sequence is a placeholder; the engine assigns the global sequence.
        return List.of(new AudioEvent(AudioEvent.Type.BEAT, strength,
                onset.timeSeconds(), onset.intensity(), -1));
    }

    /** Median of clamped inter-onset intervals; deterministic. */
    private double estimatePeriod() {
        double[] iois = new double[onsetTimes.size() - 1];
        for (int i = 1; i < onsetTimes.size(); i++) {
            double ioi = onsetTimes.get(i) - onsetTimes.get(i - 1);
            iois[i - 1] = Math.min(MAX_PERIOD, Math.max(MIN_PERIOD, ioi));
        }
        Arrays.sort(iois);
        int mid = iois.length / 2;
        return iois.length % 2 == 1 ? iois[mid] : (iois[mid - 1] + iois[mid]) / 2.0;
    }
}
