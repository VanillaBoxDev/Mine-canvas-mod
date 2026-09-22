package org.sawiq.minecanvas.fabric.client.video;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.sawiq.minecanvas.fabric.client.config.MineCanvasClientConfig;
import org.sawiq.minecanvas.fabric.client.state.ScreenState;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads complete files near player. Playback never owns these jobs. */
public final class VideoPrefetcher {
    public enum State { NONE, DOWNLOADING, READY, FAILED }
    public record Status(State state, int percent, long downloadedMb, long totalMb, long downloadedBytes, long totalBytes) { }

    public static final int PREFETCH_RADIUS_BLOCKS = 100;

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MineCanvas-Prefetch"); t.setDaemon(true); return t;
    });

    private VideoPrefetcher() { }

    public static void tick(Minecraft client, Vec3 playerPos) {
        if (playerPos == null) return;
        if (!MineCanvasClientConfig.get().prefetchVideos) {
            if (!ENTRIES.isEmpty()) cancelAll();
            return;
        }
        double radius2 = (double) PREFETCH_RADIUS_BLOCKS * PREFETCH_RADIUS_BLOCKS;
        for (VideoScreen screen : VideoScreenManager.all()) {
            ScreenState state = screen.state();
            if (state == null || !eligible(state, client, playerPos, radius2)) {
                Entry entry = state == null ? null : ENTRIES.remove(key(state));
                if (entry != null) {
                    entry.cancelled = true;
                    PlatformResolver.cancelPrefetchDownloads();
                }
            }
        }
        VideoScreenManager.all().stream().map(VideoScreen::state)
            .filter(state -> eligible(state, client, playerPos, radius2))
            .sorted(Comparator.comparingDouble(state -> distanceSquared(state, playerPos)))
            .map(state -> ENTRIES.compute(key(state), (ignored, old) -> {
                if (same(old, state)) return old;
                if (old != null) {
                    old.cancelled = true;
                    PlatformResolver.cancelPrefetchDownloads();
                }
                return new Entry(state);
            }))
            .filter(entry -> entry.state == State.NONE || entry.state == State.FAILED
                || entry.state == State.READY && VideoPlayer.cachedFile(entry.url, entry.height) == null)
            .findFirst().ifPresent(VideoPrefetcher::start);
    }

    private static boolean same(Entry entry, ScreenState state) {
        return entry != null && entry.url.equals(state.url()) && entry.height == VideoQuality.resolveEffectiveHeight(state.blocksH(), state.quality());
    }
    private static String key(ScreenState state) { return state.name().toLowerCase(Locale.ROOT); }
    private static boolean eligible(ScreenState state, Minecraft client, Vec3 pos, double radius2) {
        return state != null && state.url() != null && !state.url().isBlank()
            && VideoScreenManager.isCompatibleWithCurrentWorld(state, client) && distanceSquared(state, pos) <= radius2;
    }
    private static double distanceSquared(ScreenState state, Vec3 pos) {
        double x = (state.minX() + state.maxX() + 1) * .5 - pos.x, y = (state.minY() + state.maxY() + 1) * .5 - pos.y, z = (state.minZ() + state.maxZ() + 1) * .5 - pos.z;
        return x * x + y * y + z * z;
    }
    private static void start(Entry entry) {
        synchronized (entry) {
            if (entry.state == State.DOWNLOADING || entry.cancelled) return;
            entry.state = State.DOWNLOADING;
            WORKER.execute(() -> {
                PathResult result = VideoPlayer.ensureCachedToDisk(entry.url, entry.height, entry, () -> entry.cancelled);
                if (entry.cancelled) return;
                entry.state = result.path == null ? State.FAILED : State.READY;
                entry.onDownloadProgressBytes(result.total > 0 ? (int) Math.min(100L, result.downloaded * 100L / result.total) : 0, result.downloaded, result.total);
            });
        }
    }
    public static Status status(String name) {
        Entry entry = name == null ? null : ENTRIES.get(name.toLowerCase(Locale.ROOT));
        if (entry == null) return new Status(State.NONE, 0, 0, 0, 0, 0);
        long total = entry.total, downloaded = entry.downloaded;
        int pct = Math.max(entry.percent, total > 0 ? (int) Math.min(100L, downloaded * 100L / total) : 0);
        return new Status(entry.state, pct, Math.max(entry.downloadedMb, Math.round(downloaded / 1048576d)), Math.max(entry.totalMb, Math.round(total / 1048576d)), downloaded, total);
    }
    public static void onScreenRemoved(String name) { if (name != null) { Entry entry = ENTRIES.remove(name.toLowerCase(Locale.ROOT)); if (entry != null) { entry.cancelled = true; PlatformResolver.cancelPrefetchDownloads(); } } }
    public static void cancelAll() { ENTRIES.values().forEach(entry -> entry.cancelled = true); ENTRIES.clear(); PlatformResolver.cancelPrefetchDownloads(); }
    private static final class Entry implements VideoPlayer.FrameSink {
        final String url; final int height; volatile State state = State.NONE; volatile boolean cancelled; volatile int percent; volatile long downloadedMb, totalMb, downloaded, total;
        Entry(ScreenState state) { url = state.url(); height = VideoQuality.resolveEffectiveHeight(state.blocksH(), state.quality()); }
        @Override public void initVideo(int videoW, int videoH, int targetW, int targetH, double fps) { }
        @Override public void onFrame(int[] argb, int w, int h, long timestampUs) { }
        @Override public void onStop() { }
        @Override public void onDownloadStart(String message) { if (!cancelled) state = State.DOWNLOADING; }
        @Override public void onDownloadProgress(int percent, long downloadedMb, long totalMb) {
            if (cancelled) return;
            this.percent = Math.max(this.percent, Math.max(0, percent)); this.downloadedMb = Math.max(this.downloadedMb, Math.max(0L, downloadedMb)); this.totalMb = Math.max(this.totalMb, Math.max(0L, totalMb));
        }
        @Override public void onDownloadProgressBytes(int percent, long downloaded, long total) {
            if (cancelled) return;
            this.percent = Math.max(this.percent, Math.max(0, percent)); this.downloaded = Math.max(this.downloaded, Math.max(0L, downloaded)); this.total = Math.max(this.total, Math.max(0L, total));
            this.downloadedMb = Math.max(this.downloadedMb, Math.round(this.downloaded / 1048576d)); this.totalMb = Math.max(this.totalMb, Math.round(this.total / 1048576d));
        }
    }
    static final class PathResult { final java.nio.file.Path path; final long downloaded, total; PathResult(java.nio.file.Path path, long downloaded, long total) { this.path = path; this.downloaded = downloaded; this.total = total; } }
}
