package com.github.cuixudong74.audioreactiveenginejava.audio.engine;

import com.github.cuixudong74.audioreactiveenginejava.audio.analysis.BeatTracker;
import com.github.cuixudong74.audioreactiveenginejava.audio.analysis.OnsetDetector;
import com.github.cuixudong74.audioreactiveenginejava.audio.analysis.SpectrumAnalyzer;
import com.github.cuixudong74.audioreactiveenginejava.audio.config.AnalysisParams;
import com.github.cuixudong74.audioreactiveenginejava.audio.config.EngineConfig;
import com.github.cuixudong74.audioreactiveenginejava.audio.config.OverflowPolicy;
import com.github.cuixudong74.audioreactiveenginejava.audio.dsp.StreamResampler;
import com.github.cuixudong74.audioreactiveenginejava.audio.ingest.ChunkReassembler;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AudioChunk;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AudioEvent;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SpectrumFrame;

import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Streaming audio analysis and reaction engine.
 *
 * <p>Pipeline: chunk reassembly (dedup / reorder / gap handling) -&gt;
 * deterministic channel mix &amp; resample -&gt; windowed spectrum analysis
 * -&gt; onset &amp; beat detection -&gt; bounded output queue.</p>
 *
 * <p>Processing happens on the submitting thread, so a slow consumer applies
 * natural backpressure to the producer through the bounded output queue;
 * memory usage is bounded by configuration. All output items are atomic:
 * cancellation never emits half a frame or half an event.</p>
 *
 * <p>Not safe for concurrent {@link #submit} calls from multiple threads;
 * use a single producer thread or external synchronisation.</p>
 */
public final class AudioEngine {

    /** Strong/weak boundary for beat classification. */
    private static final double STRONG_BEAT_INTENSITY = 0.6;

    private static final EngineOutput END = new EngineOutput.Frame(
            new SpectrumFrame(-1, -1, new float[0], 0));

    private final EngineConfig config;
    private final Object lock = new Object();

    private BlockingQueue<EngineOutput> queue;
    private ChunkReassembler reassembler;
    private SpectrumAnalyzer analyzer;
    private OnsetDetector onsetDetector;
    private BeatTracker beatTracker;
    private StreamResampler resampler;

    private volatile AnalysisParams params;
    private AnalysisParams pendingParams;

    private int srcRate = -1;
    private long eventSeq;
    private boolean finished;
    private boolean cancelled;
    private boolean endSeen;
    private Throwable error;

    public AudioEngine(EngineConfig config, AnalysisParams params) {
        this.config = config;
        this.params = params;
        initRun(params);
    }

    private void initRun(AnalysisParams p) {
        this.queue = new ArrayBlockingQueue<>(config.outputQueueCapacity());
        this.reassembler = new ChunkReassembler(config.gapPolicy());
        this.analyzer = new SpectrumAnalyzer(p, config.sampleRate(), 0, 0);
        this.onsetDetector = new OnsetDetector(p, config.sampleRate());
        this.beatTracker = new BeatTracker(STRONG_BEAT_INTENSITY);
        this.resampler = null;
        this.srcRate = -1;
        this.eventSeq = 0;
        this.finished = false;
        this.cancelled = false;
        this.endSeen = false;
        this.error = null;
        this.pendingParams = null;
    }

    /**
     * Resets the engine to its initial state, starting a new run. A new run
     * over the same input produces exactly the same output; no timestamps or
     * events leak across runs.
     */
    public void reset() {
        synchronized (lock) {
            initRun(params);
        }
    }

    /**
     * Submits one chunk of audio. May block when the output queue is full
     * (backpressure). Out-of-order and duplicate chunks are safe.
     */
    public void submit(AudioChunk chunk) {
        synchronized (lock) {
            checkRunning();
            if (srcRate == -1) {
                srcRate = chunk.sampleRate();
                resampler = new StreamResampler(srcRate, config.sampleRate());
            } else if (chunk.sampleRate() != srcRate) {
                throw new IllegalArgumentException(
                        "all chunks of a run must share one source sample rate");
            }
            reassembler.accept(chunk);
            pump(false);
        }
    }

    /**
     * Hot-updates analysis parameters. Already emitted frames and events are
     * never rewritten; the new parameters take effect at the next frame
     * boundary: samples already buffered but not yet framed are re-framed
     * under the new parameters, detector adaptive state restarts, and the
     * beat tracker's onset memory is retained.
     */
    public void updateAnalysisParams(AnalysisParams params) {
        synchronized (lock) {
            if (finished || cancelled) {
                throw new IllegalStateException("engine not running");
            }
            this.pendingParams = params;
            this.params = params;
        }
    }

    /** Signals end of input and flushes all pending results. Idempotent. */
    public void finish() {
        synchronized (lock) {
            if (finished) {
                return;
            }
            if (cancelled) {
                return;
            }
            finished = true;
            reassembler.finish();
            pump(true);
            if (!cancelled) {
                for (SpectrumFrame f : analyzer.flush()) {
                    emitFrame(f);
                }
                if (!cancelled) {
                    for (AudioEvent e : onsetDetector.flush()) {
                        emitEvent(e);
                    }
                }
            }
            // The END marker is always offered, even when a backpressure
            // timeout cancelled the engine mid-flush, so consumers can
            // always observe termination.
            offerEnd();
        }
    }

    /**
     * Cancels processing immediately. No further output is produced; the
     * analysis state is discarded as a whole, never left mid-frame. Items
     * already queued remain available to the consumer.
     */
    public void cancel() {
        synchronized (lock) {
            if (cancelled || finished) {
                return;
            }
            cancelled = true;
            analyzer = null;
            onsetDetector = null;
            offerEnd();
        }
    }

    /**
     * Takes the next output item, blocking until available. Returns
     * {@code null} once the stream has ended (finish or cancel) and the
     * queue has been drained.
     */
    public EngineOutput take() throws InterruptedException {
        if (endSeen) {
            return null;
        }
        EngineOutput item = queue.take();
        return unwrapEnd(item);
    }

    /** Polls the next output item with a timeout; {@code null} on timeout or end. */
    public EngineOutput poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (endSeen) {
            return null;
        }
        EngineOutput item = queue.poll(timeout, unit);
        return item == null ? null : unwrapEnd(item);
    }

    /** True after the stream ended and the queue was fully drained. */
    public boolean isDrained() {
        return endSeen || (queue.isEmpty() && (finished || cancelled));
    }

    /** The error that stopped the engine, if any. */
    public Optional<Throwable> error() {
        return Optional.ofNullable(error);
    }

    // ---- internals ----

    private void checkRunning() {
        if (finished) {
            throw new IllegalStateException("engine already finished");
        }
        if (cancelled) {
            throw new IllegalStateException("engine cancelled");
        }
    }

    private EngineOutput unwrapEnd(EngineOutput item) {
        if (item == END) {
            endSeen = true;
            return null;
        }
        return item;
    }

    /** Moves reassembled samples through resampling and framing. */
    private void pump(boolean finish) {
        float[] buf = new float[8192];
        int n;
        while (!cancelled && (n = reassembler.read(buf, 0, buf.length)) > 0) {
            float[] out = resampler == null
                    ? copyOf(buf, n)
                    : resampler.feed(buf, 0, n, false);
            pushToAnalyzer(out);
        }
        if (finish && !cancelled) {
            float[] out = resampler == null
                    ? new float[0]
                    : resampler.feed(buf, 0, 0, true);
            pushToAnalyzer(out);
        }
    }

    private static float[] copyOf(float[] buf, int n) {
        float[] out = new float[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    private void pushToAnalyzer(float[] samples) {
        if (samples.length > 0) {
            analyzer.push(samples, 0, samples.length);
        }
        SpectrumFrame f;
        while (!cancelled && (f = analyzer.next()) != null) {
            emitFrame(f);
            applyPendingParams();
        }
        if (!cancelled) {
            applyPendingParams();
        }
    }

    /** Applies a hot parameter update at the current frame boundary. */
    private void applyPendingParams() {
        AnalysisParams next = pendingParams;
        if (next == null) {
            return;
        }
        pendingParams = null;
        float[] unframed = analyzer.drainUnframed();
        long windowStart = analyzer.currentWindowStart();
        long frameIndex = analyzer.currentFrameIndex();
        analyzer = new SpectrumAnalyzer(next, config.sampleRate(), windowStart, frameIndex);
        analyzer.push(unframed, 0, unframed.length);
        onsetDetector = new OnsetDetector(next, config.sampleRate());
    }

    private void emitFrame(SpectrumFrame frame) {
        offer(new EngineOutput.Frame(frame));
        if (cancelled) {
            return;
        }
        for (AudioEvent e : onsetDetector.onFrame(frame)) {
            emitEvent(e);
        }
    }

    private void emitEvent(AudioEvent e) {
        AudioEvent sequenced = new AudioEvent(e.type(), e.strength(), e.timeSeconds(),
                e.intensity(), eventSeq++);
        offer(new EngineOutput.Event(sequenced));
        if (cancelled || sequenced.type() != AudioEvent.Type.ONSET) {
            return;
        }
        for (AudioEvent b : beatTracker.onOnset(sequenced)) {
            AudioEvent beat = new AudioEvent(b.type(), b.strength(), b.timeSeconds(),
                    b.intensity(), eventSeq++);
            offer(new EngineOutput.Event(beat));
            if (cancelled) {
                return;
            }
        }
    }

    /** Offers one atomic item to the bounded queue, applying backpressure. */
    private void offer(EngineOutput item) {
        if (cancelled) {
            return;
        }
        if (config.overflowPolicy() == OverflowPolicy.DROP_OLDEST) {
            while (!queue.offer(item)) {
                queue.poll();
            }
            return;
        }
        try {
            boolean ok = queue.offer(item, config.offerTimeout().toMillis(),
                    TimeUnit.MILLISECONDS);
            if (!ok) {
                error = new TimeoutException(
                        "output queue full for " + config.offerTimeout()
                                + "; consumer too slow");
                cancelled = true;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            cancelled = true;
        }
    }

    private void offerEnd() {
        // The END marker must always fit; make room if necessary so consumers
        // can observe termination. This never drops a half item.
        while (!queue.offer(END)) {
            queue.poll();
        }
    }
}
