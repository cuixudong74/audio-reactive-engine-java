package com.github.cuixudong74.audioreactiveenginejava;

import com.github.cuixudong74.audioreactiveenginejava.audio.engine.AudioAnalysisEngine;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AnalysisConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the analysis engine. The engine itself is plain Java and
 * fully usable without Spring; this class only exposes a preconfigured
 * instance as a bean. All knobs map 1:1 to {@link AnalysisConfig} and can be
 * set via {@code application.properties} (prefix {@code audio.analysis}).
 */
@Configuration
public class EngineConfiguration {

    @Bean(destroyMethod = "close")
    public AudioAnalysisEngine audioAnalysisEngine(
            @Value("${audio.analysis.target-sample-rate:44100}") int targetSampleRate,
            @Value("${audio.analysis.window-size:1024}") int windowSize,
            @Value("${audio.analysis.window-type:HANN}") AnalysisConfig.WindowType windowType,
            @Value("${audio.analysis.overlap-ratio:0.5}") double overlapRatio,
            @Value("${audio.analysis.silence-threshold-rms:1.0e-4}") double silenceThresholdRms,
            @Value("${audio.analysis.onset-sensitivity:1.5}") double onsetSensitivity,
            @Value("${audio.analysis.onset-flux-floor:1.0}") double onsetFluxFloor,
            @Value("${audio.analysis.strong-beat-ratio:2.0}") double strongBeatRatio,
            @Value("${audio.analysis.min-onset-interval-ms:60}") long minOnsetIntervalMs,
            @Value("${audio.analysis.gap-policy:FILL_SILENCE}") AnalysisConfig.GapPolicy gapPolicy,
            @Value("${audio.analysis.max-reorder-frames:1048576}") int maxReorderFrames,
            @Value("${audio.analysis.input-queue-capacity:256}") int inputQueueCapacity,
            @Value("${audio.analysis.output-queue-capacity:1024}") int outputQueueCapacity) {
        AnalysisConfig config = new AnalysisConfig(0, targetSampleRate, windowSize, windowType,
                overlapRatio, silenceThresholdRms, onsetSensitivity, onsetFluxFloor, strongBeatRatio,
                minOnsetIntervalMs, gapPolicy, maxReorderFrames, inputQueueCapacity,
                outputQueueCapacity);
        AudioAnalysisEngine engine = new AudioAnalysisEngine(config);
        engine.start();
        return engine;
    }
}
