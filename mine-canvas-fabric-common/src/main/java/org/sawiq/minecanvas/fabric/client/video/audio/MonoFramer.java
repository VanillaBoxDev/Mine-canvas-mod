package org.sawiq.minecanvas.fabric.client.video.audio;

import java.nio.Buffer;
import java.nio.ShortBuffer;
import java.util.function.Consumer;

public final class MonoFramer {
    public static final int FRAME_SIZE = 960;
    public static final long FRAME_NANOS = 20_000_000L;

    private final short[] frame = new short[FRAME_SIZE];
    private int frameLength;

    public void accept(Buffer[] samples, int channels, float gain, Consumer<short[]> output) {
        if (samples == null || samples.length == 0 || !(samples[0] instanceof ShortBuffer)) return;

        if (channels >= 2 && samples.length >= 2 && samples[1] instanceof ShortBuffer) {
            ShortBuffer left = ((ShortBuffer) samples[0]).duplicate();
            ShortBuffer right = ((ShortBuffer) samples[1]).duplicate();
            while (left.hasRemaining() && right.hasRemaining()) append(scaleClamp(((int) left.get() + right.get()) / 2, gain), output);
            return;
        }

        ShortBuffer input = ((ShortBuffer) samples[0]).duplicate();
        if (channels < 2) {
            while (input.hasRemaining()) append(scaleClamp(input.get(), gain), output);
            return;
        }
        while (input.remaining() >= channels) {
            int mixed = 0;
            for (int channel = 0; channel < channels; channel++) mixed += input.get();
            append(scaleClamp(mixed / channels, gain), output);
        }
    }

    private void append(short sample, Consumer<short[]> output) {
        frame[frameLength++] = sample;
        if (frameLength < FRAME_SIZE) return;
        output.accept(frame.clone());
        frameLength = 0;
    }

    static short scaleClamp(int sample, float gain) {
        long scaled = Math.round(sample * (double) Math.max(0f, gain));
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
    }

    public static boolean shouldWait(long framesPushed, long elapsedNanos, long leadNanos) {
        long dueNanos = (framesPushed + 1L) * FRAME_NANOS - Math.min(100_000_000L, Math.max(0L, leadNanos));
        return elapsedNanos < dueNanos;
    }
}
