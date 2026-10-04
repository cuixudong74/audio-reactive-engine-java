package com.github.cuixudong74.audioreactiveenginejava.audio.engine;

import com.github.cuixudong74.audioreactiveenginejava.audio.analysis.OnsetDetector;
import com.github.cuixudong74.audioreactiveenginejava.audio.analysis.SpectrumAnalyzer;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.AnalysisConfig;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.BeatEvent;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.EngineEvent;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.GapNotice;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SampleChunk;
import com.github.cuixudong74.audioreactiveenginejava.audio.model.SpectrumFrame;
import com.github.cuixudong74.audioreactiveenginejava.audio.pipeline.ChannelMixer;
import com.github.cuixudong74.audioreactiveenginejava.audio.pipeline.ChunkReassembler;
import com.github.cuixudong74.audioreactiveenginejava.audio.pipeline.Resampler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streaming audio analysis engine.
 *
 * <p>Threading model: a single worker thread runs the whole pipeline
 * (mix -&gt; reassemble -&gt; resample -&gt; frame/FFT -&gt; onset detection),
 * which makes the output fully deterministic. Input and output are bounded
 * queues, so memory is bounded by configuration and a slow consumer applies
 * backpressure to {@link #submit(SampleChunk)} instead of growing memory.</p>
 *
 * <p>Consistency guarantees:</p>
 * <ul>
 *   <li>The output depends only on the logical sample sequence (absolute
 *       frame indices), never on chunking, delivery order or duplication.</li>
 *   <li>Events are emitted as whole immutable objects; shutdown, timeout or
 *       cancellation can never produce half an event, and all pipeline state
 *       transitions happen at command boundaries, never mid-frame.</li>
 *   <li>The output timeline (event times) is monotonically non-decreasing
 *       within a session, including across input timestamp jumps and gaps.</li>
 *   <li>Hot config updates apply to samples accepted after the update
 *       command; events already emitted keep their original config version
 *       and are never rewritten.</li>
 * </ul>
 *
 * <p>One engine instance serves exactly one stream: the stream id is fixed
 * by the first accepted chunk; chunks with a different stream id are
 * dropped.</p>
 */
public final class AudioAnalysisEngine implements AutoCloseable {

    private sealed interface Command {
    }

    private record ChunkCommand(SampleChunk chunk) implements Command {
    }

    private record ConfigCommand(AnalysisConfig config, CountDownLatch applied) implements Command {
    }

    private record ResetCommand(CountDownLatch done) implements Command {
    }

    private final BlockingQueueHolder queues;
    private final AtomicReference<AnalysisConfig> currentConfig;
    private final AtomicLong versionCounter;

    private volatile boolean closed;
    private volatile boolean started;
    private Thread worker;

    // ---- worker-thread confined pipeline state ----
    private AnalysisConfig config;
    private ChunkReassembler reassembler;
    private SpectrumAnalyzer analyzer;
    private OnsetDetector detector;
    private Resampler resampler;
    private int resamplerSourceRate = -1;
    private String streamId;

    private static final class BlockingQueueHolder {
        final ArrayBlockingQueue<Command> input;
        final ArrayBlockingQueue<EngineEvent> output;

        BlockingQueueHolder(int inputCapacity, int outputCapacity) {
            this.input = new ArrayBlockingQueue<>(inputCapacity);
            this.output = new ArrayBlockingQueue<>(outputCapacity);
        }
    }

    public AudioAnalysisEngine(AnalysisConfig initialConfig) {
        this.config = initialConfig;
        this.currentConfig = new AtomicReference<>(initialConfig);
        this.versionCounter = new AtomicLong(initialConfig.version());
        this.queues = new BlockingQueueHolder(initialConfig.inputQueueCapacity(),
                initialConfig.outputQueueCapacity());
        this.reassembler = new ChunkReassembler(initialConfig.maxReorderFrames());
        this.analyzer = new SpectrumAnalyzer(initialConfig);
        this.detector = new OnsetDetector(initialConfig);
    }

    /** Starts the worker thread. Must be called before submitting chunks. */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        worker = new Thread(this::runLoop, "audio-analysis-engine");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Offers a chunk to the engine. Returns false immediately when the input
     * queue is full or the engine is closed: backpressure is signalled to the
     * caller, this method never blocks indefinitely. Returns false as well
     * for chunks of a foreign stream id.
     */
    public boolean submit(SampleChunk chunk) {
        if (closed || chunk == null) {
            return false;
        }
        String id = streamId;
        if (id != null && !id.equals(chunk.streamId())) {
            return false;
        }
        return queues.input.offer(new ChunkCommand(chunk));
    }

    /**
     * Polls emitted events, waiting at most {@code timeout} for the first
     * one. Returns an empty list on timeout. Events are returned whole, in
     * emission order; a timeout never yields partial events.
     */
    public List<EngineEvent> poll(long timeout, TimeUnit unit) throws InterruptedException {
        EngineEvent first = queues.output.poll(timeout, unit);
        if (first == null) {
            return List.of();
        }
        List<EngineEvent> out = new ArrayList<>();
        out.add(first);
        queues.output.drainTo(out);
        return out;
    }

    /**
     * Hot-updates the analysis configuration. The new config applies to all
     * chunks submitted after this method returns; chunks already submitted
     * are analyzed with the old config. Already emitted events are never
     * rewritten. The output timeline stays continuous across the change.
     *
     * <p>Note: {@code maxReorderFrames} and the queue capacities are engine
     * structural parameters; they are fixed at construction and a change
     * only takes effect on a new engine (documented restart-only knobs).</p>
     *
     * @return the version assigned to the new config
     */
    public long updateConfig(AnalysisConfig newConfig) {
        ensureOpen();
        long version = versionCounter.incrementAndGet();
        AnalysisConfig versioned = newConfig.withVersion(version);
        CountDownLatch applied = new CountDownLatch(1);
        enqueueControl(new ConfigCommand(versioned, applied));
        awaitLatch(applied, "config update");
        return version;
    }

    /** Returns the currently effective config. */
    public AnalysisConfig currentConfig() {
        return currentConfig.get();
    }

    /**
     * Resets all streaming state so the engine behaves as if freshly started
     * with the current config: the timeline restarts at zero, pending
     * unconsumed output events are discarded (so no stale or duplicated
     * events can leak into the new session), and late chunks from before the
     * reset are treated as duplicates of the new timeline only if their
     * indices fit it.
     */
    public void reset() {
        ensureOpen();
        CountDownLatch done = new CountDownLatch(1);
        enqueueControl(new ResetCommand(done));
        awaitLatch(done, "reset");
    }

    /** Stops the worker thread. Idempotent. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        Thread w = worker;
        if (w != null) {
            try {
                w.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Release any control commands that were enqueued but never processed.
        Command cmd;
        while ((cmd = queues.input.poll()) != null) {
            if (cmd instanceof ConfigCommand cc) {
                cc.applied().countDown();
            } else if (cmd instanceof ResetCommand rc) {
                rc.done().countDown();
            }
        }
    }

    // ------------------------------------------------------------------
    // worker thread
    // ------------------------------------------------------------------

    private void runLoop() {
        try {
            while (!closed) {
                Command cmd = queues.input.poll(50, TimeUnit.MILLISECONDS);
                if (cmd == null) {
                    continue;
                }
                handle(cmd);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handle(Command cmd) {
        if (cmd instanceof ChunkCommand cc) {
            handleChunk(cc.chunk());
        } else if (cmd instanceof ConfigCommand cc) {
            handleConfig(cc);
        } else if (cmd instanceof ResetCommand rc) {
            handleReset();
            rc.done().countDown();
        }
    }

    private void handleChunk(SampleChunk chunk) {
        if (streamId == null) {
            streamId = chunk.streamId();
        } else if (!streamId.equals(chunk.streamId())) {
            return;
        }
        if (chunk.frameCount() == 0) {
            return;
        }
        float[] mono = ChannelMixer.toMono(chunk.samples(), chunk.channels());
        Resampler rs = resamplerFor(chunk.sampleRate());
        ChunkReassembler.Result result = reassembler.accept(chunk.startFrameIndex(), mono);
        if (result.gapFrames() > 0) {
            emit(new GapNotice(streamTimeSeconds(), result.gapFrames(), config.version()));
            if (config.gapPolicy() == AnalysisConfig.GapPolicy.FILL_SILENCE) {
                // Memory stays bounded: silence is materialized only up to the
                // reorder bound; the excess behaves like GapPolicy.SKIP.
                long fill = Math.min(result.gapFrames(), config.maxReorderFrames());
                feed(new float[(int) fill], rs);
            }
        }
        feed(result.ready(), rs);
    }

    private void feed(float[] sourceSamples, Resampler rs) {
        if (sourceSamples.length == 0) {
            return;
        }
        float[] resampled = rs.process(sourceSamples);
        List<SpectrumFrame> frames = analyzer.process(resampled);
        for (SpectrumFrame frame : frames) {
            emit(frame);
            for (BeatEvent beat : detector.process(frame)) {
                emit(beat);
            }
        }
    }

    private void handleConfig(ConfigCommand cmd) {
        AnalysisConfig next = cmd.config();
        // Carry the timeline and unconsumed samples into the new analyzer so
        // the output time axis neither jumps nor rewinds.
        analyzer = new SpectrumAnalyzer(next, analyzer.nextFrameIndex(),
                analyzer.nextFrameStartSample(), analyzer.unconsumedSamples());
        detector.updateConfig(next);
        if (next.targetSampleRate() != config.targetSampleRate()) {
            resampler = null;
            resamplerSourceRate = -1;
        }
        config = next;
        currentConfig.set(next);
        cmd.applied().countDown();
    }

    private void handleReset() {
        reassembler.reset();
        analyzer = new SpectrumAnalyzer(config);
        detector.reset();
        resampler = null;
        resamplerSourceRate = -1;
        streamId = null;
        queues.output.clear();
    }

    private Resampler resamplerFor(int sourceRate) {
        if (resampler == null || resamplerSourceRate != sourceRate) {
            resampler = new Resampler(sourceRate, config.targetSampleRate());
            resamplerSourceRate = sourceRate;
        }
        return resampler;
    }

    /**
     * Time stamp for stream-level notices. Uses the start of the next frame
     * (not the raw stream head) so that a notice emitted between two frames
     * never carries a time later than the next frame's start: the output
     * timeline stays monotonically non-decreasing.
     */
    private double streamTimeSeconds() {
        return analyzer.nextFrameStartSample() / (double) config.targetSampleRate();
    }

    /**
     * Emits one whole event. Blocks (with a shutdown-aware wait) while the
     * output queue is full: this is what propagates backpressure to the
     * input side. Events are dropped only when the engine is closing.
     */
    private void emit(EngineEvent event) {
        try {
            while (!closed) {
                if (queues.output.offer(event, 50, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("engine is closed");
        }
    }

    private void enqueueControl(Command cmd) {
        try {
            queues.input.put(cmd);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while enqueuing control command", e);
        }
    }

    private void awaitLatch(CountDownLatch latch, String what) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for " + what, e);
        }
    }
}
