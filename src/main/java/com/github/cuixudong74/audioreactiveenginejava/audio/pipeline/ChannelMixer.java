package com.github.cuixudong74.audioreactiveenginejava.audio.pipeline;

/**
 * Deterministic multi-channel downmix: arithmetic mean of all channels per frame.
 * Pure function, no state. The mean is computed in double precision and the
 * summation order is fixed (channel 0..n-1), so the result is reproducible.
 */
public final class ChannelMixer {

    private ChannelMixer() {
    }

    /**
     * Downmixes interleaved multi-channel samples to mono.
     *
     * @param interleaved interleaved samples, length == frames * channels
     * @param channels    number of channels
     * @return mono samples, length == frames
     */
    public static float[] toMono(float[] interleaved, int channels) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be > 0");
        }
        if (interleaved.length % channels != 0) {
            throw new IllegalArgumentException("interleaved length must be a multiple of channels");
        }
        int frames = interleaved.length / channels;
        float[] mono = new float[frames];
        if (channels == 1) {
            System.arraycopy(interleaved, 0, mono, 0, frames);
            return mono;
        }
        for (int f = 0; f < frames; f++) {
            double sum = 0.0;
            int base = f * channels;
            for (int c = 0; c < channels; c++) {
                sum += interleaved[base + c];
            }
            mono[f] = (float) (sum / channels);
        }
        return mono;
    }
}
