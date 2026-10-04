package com.github.cuixudong74.audioreactiveenginejava.audio.analysis;

import com.github.cuixudong74.audioreactiveenginejava.audio.model.AnalysisConfig;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SpectrumFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateful framing + spectrum analysis. Consumes a contiguous mono stream at
 * the target sample rate and emits one {@link SpectrumFrame} every
 * {@code hopSize} samples.
 *
 * <p>Frames are cut on absolute stream positions (the next frame always
 * starts at {@link #nextFrameStartSample()}), so the way the input is split
 * into chunks never changes the output. A frame is emitted only when its
 * window is completely filled; a trailing partial window is held back,
 * never emitted half-full.</p>
 *
 * <p>Window boundary behaviour: the first window starts at stream position 0.
 * Non-rectangular windows have zero-valued coefficients at both ends, so
 * boundary samples contribute nothing to the spectrum. The RMS energy is
 * computed on the windowed frame.</p>
 *
 * <p>Hot config updates: a new analyzer can be seeded from an old one via
 * {@link #SpectrumAnalyzer(AnalysisConfig, long, long, float[])}, carrying
 * the frame counter, the absolute sample position and the unconsumed
 * samples. The output timeline therefore never jumps or rewinds across a
 * config change; the first frame under the new config starts exactly where
 * the old config would have cut its next frame.</p>
 */
public final class SpectrumAnalyzer {

    private final AnalysisConfig config;
    private final WindowFunction window;
    private final Fft fft;
    private final int hopSize;

    private float[] buffer;
    private int bufferLength;
    private long frameIndex;
    private long frameStartSample;

    /** Creates a fresh analyzer starting at stream position zero. */
    public SpectrumAnalyzer(AnalysisConfig config) {
        this(config, 0, 0, new float[0]);
    }

    /**
     * Creates an analyzer seeded with carried-over state (used for hot
     * config updates).
     *
     * @param startFrameIndex     index the next emitted frame will carry
     * @param startSamplePosition absolute target-rate sample position of the next frame
     * @param initialSamples      unconsumed samples from the previous analyzer
     */
    public SpectrumAnalyzer(AnalysisConfig config, long startFrameIndex, long startSamplePosition,
                            float[] initialSamples) {
        this.config = config;
        this.window = WindowFunction.of(config.windowType(), config.windowSize());
        this.fft = new Fft(config.windowSize());
        this.hopSize = config.hopSize();
        this.buffer = new float[Math.max(config.windowSize() * 2, initialSamples.length * 2)];
        append(initialSamples);
        this.frameIndex = startFrameIndex;
        this.frameStartSample = startSamplePosition;
    }

    /** Index the next emitted frame will carry. */
    public long nextFrameIndex() {
        return frameIndex;
    }

    /** Absolute target-rate sample position at which the next frame starts. */
    public long nextFrameStartSample() {
        return frameStartSample;
    }

    /** Number of buffered samples not yet consumed by a completed frame. */
    public int bufferedSamples() {
        return bufferLength;
    }

    /** Copy of the buffered, not yet consumed samples. */
    public float[] unconsumedSamples() {
        float[] out = new float[bufferLength];
        System.arraycopy(buffer, 0, out, 0, bufferLength);
        return out;
    }

    /**
     * Feeds contiguous mono samples; returns all frames completed by this call,
     * in increasing frame-index order.
     */
    public List<SpectrumFrame> process(float[] monoSamples) {
        append(monoSamples);
        List<SpectrumFrame> frames = new ArrayList<>();
        float[] windowed = new float[config.windowSize()];
        while (bufferLength >= config.windowSize()) {
            System.arraycopy(buffer, 0, windowed, 0, config.windowSize());
            window.applyInPlace(windowed);
            double rms = rms(windowed);
            float[] magnitudes = fft.magnitudeSpectrum(windowed);
            double timeSeconds = frameStartSample / (double) config.targetSampleRate();
            frames.add(new SpectrumFrame(frameIndex, timeSeconds, magnitudes, rms, config.version()));
            frameIndex++;
            frameStartSample += hopSize;
            consume(hopSize);
        }
        return frames;
    }

    /** Clears the frame buffer and resets the timeline (used on engine restart). */
    public void reset() {
        bufferLength = 0;
        frameIndex = 0;
        frameStartSample = 0;
    }

    private void append(float[] samples) {
        if (samples.length == 0) {
            return;
        }
        ensureCapacity(bufferLength + samples.length);
        System.arraycopy(samples, 0, buffer, bufferLength, samples.length);
        bufferLength += samples.length;
    }

    private void consume(int n) {
        bufferLength -= n;
        System.arraycopy(buffer, n, buffer, 0, bufferLength);
    }

    private void ensureCapacity(int needed) {
        if (needed > buffer.length) {
            float[] grown = new float[Math.max(needed, buffer.length * 2)];
            System.arraycopy(buffer, 0, grown, 0, bufferLength);
            buffer = grown;
        }
    }

    private static double rms(float[] samples) {
        double sum = 0.0;
        for (float s : samples) {
            sum += (double) s * s;
        }
        return Math.sqrt(sum / samples.length);
    }
}
