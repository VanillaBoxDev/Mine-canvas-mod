package org.sawiq.minecanvas.fabric.client.video.voicechat;

import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.audiochannel.ClientLocationalAudioChannel;
import net.minecraft.world.phys.Vec3;
import org.sawiq.minecanvas.fabric.client.video.audio.MonoFramer;
import org.sawiq.minecanvas.fabric.client.video.audio.SpatialAudio;

import java.nio.Buffer;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

public final class VoicechatPcmOutput implements SpatialAudio.Output {
    private static final long LEAD_NANOS = 50_000_000L;

    private final ClientLocationalAudioChannel channel;
    private final MonoFramer framer = new MonoFramer();
    // Only the decode thread mutates this deque; other threads signal through active.
    private final ArrayDeque<short[]> frames = new ArrayDeque<>();
    private final Runnable onFailure;
    private volatile boolean active = true;
    private volatile float gain = 1f;
    private boolean started;
    private long anchorNanos;
    private long framesPushed;

    public VoicechatPcmOutput(VoicechatClientApi api, Vec3 center, float distance, Runnable onFailure) {
        this.onFailure = onFailure;
        channel = api.createLocationalAudioChannel(UUID.randomUUID(), api.createPosition(center.x, center.y, center.z));
        channel.setLocation(api.createPosition(center.x, center.y, center.z));
        channel.setDistance(distance > 0f ? distance : (float) api.getVoiceChatDistance());
    }

    @Override
    public void write(Buffer[] samples, int channels, long timestampUs) {
        if (!active) return;
        try {
            framer.accept(samples, channels, gain, frames::addLast);
            drain();
        } catch (Throwable ignored) {
            fail();
        }
    }

    @Override
    public void prebuffer(Buffer[] samples, int channels, long timestampUs) {
        if (!active) return;
        try {
            framer.accept(samples, channels, gain, frames::addLast);
        } catch (Throwable ignored) {
            fail();
        }
    }

    @Override
    public void start(long videoTimestampUs) {
        if (!active || started) return;
        started = true;
        anchorNanos = System.nanoTime();
        try {
            drain();
        } catch (Throwable ignored) {
            fail();
        }
    }

    private void drain() {
        if (!started) return;
        while (active && !frames.isEmpty()) {
            // 100 ms is a producer-lead bound, not an end-to-end A/V bound;
            // Simple Voice Chat/OpenAL output latency is not observable here.
            while (active && !Thread.currentThread().isInterrupted()
                    && MonoFramer.shouldWait(framesPushed, System.nanoTime() - anchorNanos, LEAD_NANOS)) {
                LockSupport.parkNanos(1_000_000L);
            }
            if (!active || Thread.currentThread().isInterrupted()) break;
            channel.play(frames.removeFirst());
            framesPushed++;
        }
        if (!active) frames.clear();
    }

    @Override
    public void setGain(float gain) {
        this.gain = Math.max(0f, gain);
    }

    @Override
    public long positionUs(long videoBaseTimestampUs) {
        return -1;
    }

    @Override
    public void close() {
        active = false;
    }

    private void fail() {
        close();
        try {
            onFailure.run();
        } catch (Throwable ignored) { }
    }
}
