package com.github.cuixudong74.audioreactiveenginejava.audio.analysis;

import com.github.cuixudong74.audioreactiveenginejava.audio.config.AnalysisParams;
import com.github.cuixudong74.audioreactiveenginejava.audio.dsp.Fft;
import com.github.cuixudong74.audioreactiveenginejava.audio.dsp.WindowFunction;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SpectrumFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * Frames a continuous mono stream into overlapping windows and computes the
 * magnitude spectrum and energy of each window.
 *
 * <p>Boundary behaviour (deterministic): a frame is emitted only when its
 * window is fully covered by pushed samples, except at {@link #flush()} where
 * a final partially-filled window is zero-padded and emitted exactly once.</p>
 */
public final class SpectrumAnalyzer {

    private final AnalysisParams params;
    private final int sampleRate;
    private final int hop;
    private final float[] window;

    /** Absolute sample index of the start of the next window. */
    private long windowStart;

    /** Index of the next frame to emit. */
    private long frameIndex;

    /** Ring-free simple buffer: samples from windowStart onward. */
    private float[] buf = new float[0];
    private int bufLen;

    private boolean flushed;

    public SpectrumAnalyzer(AnalysisParams params, int sampleRate, long startSample, long startFrameIndex) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0");
        }
        this.params = params;
        this.sampleRate = sampleRate;
        this.hop = params.hopSize();
        this.window = WindowFunction.coefficients(params.windowType(), params.windowSize());
        this.windowStart = startSample;
        this.frameIndex = startFrameIndex;
    }

    /** Appends mono samples to the internal buffer. */
    public void push(float[] samples, int offset, int length) {
        if (flushed) {
            throw new IllegalStateException("analyzer already flushed");
        }
        if (length == 0) {
            return;
        }
        ensureCapacity(bufLen + length);
        System.arraycopy(samples, offset, buf, bufLen, length);
        bufLen += length;
    }

    /** Returns the next complete frame, or {@code null} if not enough samples. */
    public SpectrumFrame next() {
        if (flushed || bufLen < params.windowSize()) {
            return null;
        }
        SpectrumFrame frame = emitFrame(false);
        // Advance by one hop, discarding consumed samples.
        int remaining = bufLen - hop;
        System.arraycopy(buf, hop, buf, 0, remaining);
        bufLen = remaining;
        windowStart += hop;
        return frame;
    }

    /**
     * Emits the final zero-padded frame if unframed samples remain.
     * Idempotent: subsequent calls return an empty list.
     */
    public List<SpectrumFrame> flush() {
        if (flushed) {
            return List.of();
        }
        flushed = true;
        if (bufLen == 0) {
            return List.of();
        }
        // Zero-pad the tail window; buf beyond bufLen is already zero only if
        // we clear it, so emit from a zero-padded copy.
        SpectrumFrame frame = emitFrame(true);
        bufLen = 0;
        return List.of(frame);
    }

    /** Absolute sample index at which the next window starts. */
    public long currentWindowStart() {
        return windowStart;
    }

    /** Index the next emitted frame will carry. */
    public long currentFrameIndex() {
        return frameIndex;
    }

    /**
     * Removes and returns the buffered samples not yet emitted as a frame
     * (from {@link #currentWindowStart()} onward). Used to hand unframed
     * samples over to a successor analyzer when parameters change.
     */
    public float[] drainUnframed() {
        float[] out = new float[bufLen];
        System.arraycopy(buf, 0, out, 0, bufLen);
        bufLen = 0;
        return out;
    }

    private SpectrumFrame emitFrame(boolean zeroPadTail) {
        int size = params.windowSize();
        float[] windowed = new float[size];
        int copy = zeroPadTail ? Math.min(bufLen, size) : size;
        for (int i = 0; i < copy; i++) {
            windowed[i] = buf[i] * window[i];
        }
        float[] magnitudes = Fft.magnitudes(windowed);
        double energy = 0;
        for (float v : windowed) {
            energy += (double) v * v;
        }
        double time = (double) windowStart / sampleRate;
        return new SpectrumFrame(frameIndex++, time, magnitudes, energy);
    }

    private void ensureCapacity(int needed) {
        if (buf.length < needed) {
            int newCap = Math.max(needed, Math.max(2 * buf.length, params.windowSize() * 2));
            float[] next = new float[newCap];
            System.arraycopy(buf, 0, next, 0, bufLen);
            buf = next;
        }
    }
}
