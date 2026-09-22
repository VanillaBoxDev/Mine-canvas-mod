package org.sawiq.minecanvas.fabric.client.video.audio;

import net.minecraft.world.phys.Vec3;

import java.nio.Buffer;

public final class SpatialAudio {
    public interface Output extends AutoCloseable {
        void write(Buffer[] samples, int channels, long timestampUs);
        void prebuffer(Buffer[] samples, int channels, long timestampUs);
        void start(long videoTimestampUs);
        void setGain(float gain);
        long positionUs(long videoBaseTimestampUs);
        @Override void close();
    }

    public interface OutputFactory {
        Output create(Vec3 center, float distance);
    }

    private static volatile OutputFactory factory;
    private static volatile boolean available;

    private SpatialAudio() { }

    public static boolean isAvailable() {
        return available && factory != null;
    }

    public static Output create(Vec3 center, float distance) {
        OutputFactory current = factory;
        if (!available || current == null) return null;
        try {
            Output output = current.create(center, distance);
            if (output != null && available && factory == current) return output;
            if (output != null) output.close();
            return null;
        } catch (Throwable ignored) {
            clearFactory(current);
            return null;
        }
    }

    public static synchronized void setFactory(OutputFactory newFactory) {
        factory = newFactory;
        available = newFactory != null;
    }

    public static synchronized void clearFactory(OutputFactory expected) {
        if (factory != expected) return;
        available = false;
        factory = null;
    }
}
