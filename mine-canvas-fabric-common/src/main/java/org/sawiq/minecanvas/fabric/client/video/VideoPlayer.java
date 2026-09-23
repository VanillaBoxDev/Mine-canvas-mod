package org.sawiq.minecanvas.fabric.client.video;

import net.fabricmc.loader.api.FabricLoader;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.sawiq.minecanvas.fabric.client.config.MineCanvasClientConfig;
import org.sawiq.minecanvas.fabric.client.video.audio.SpatialAudio;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** FFmpeg only receives completed local cache files. */
public final class VideoPlayer {
    public interface FrameSink {
        void initVideo(int videoW, int videoH, int targetW, int targetH, double fps);
        void onFrame(int[] argb, int w, int h, long timestampUs);
        void onStop();
        default void onPlaybackClockStart(long wallStartNs) { }
        default void onDuration(long durationMs) { }
        default void onEnded(long durationMs) { }
        default void onDownloadStart(String message) { }
        default void onDownloadProgress(int percent, long downloadedMb, long totalMb) { }
        default void onDownloadProgressBytes(int percent, long downloaded, long total) { onDownloadProgress(percent, Math.round(downloaded / 1048576d), Math.round(total / 1048576d)); }
        default void onCachedFileUsed(String path, long size) { }
        default boolean canAcceptFrame() { return true; }
        default int[] borrowBuffer() { return null; }
        default void returnBuffer(int[] buffer) { }
    }
    public record CacheInfo(long cacheSizeBytes, int fileCount) { }
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final Map<String, Path> READY = new ConcurrentHashMap<>();
    private static final Map<Path, AtomicInteger> IN_USE = new ConcurrentHashMap<>();
    private static final Map<Path, Boolean> WRITING = new ConcurrentHashMap<>();
    private static final AtomicBoolean AUDIO_UNAVAILABLE_LOGGED = new AtomicBoolean();
    private final FrameSink sink;
    private final Object lifecycleLock = new Object();
    private volatile boolean running;
    private volatile float gain = 1f;
    private volatile SpatialAudio.Output audio;
    private volatile Thread thread;
    private volatile long videoBaseTimestampUs = -1;

    public VideoPlayer(FrameSink sink) { this.sink = sink; }
    public boolean isRunning() { synchronized (lifecycleLock) { return running && thread != null && thread.isAlive(); } }
    public boolean start(String url, int blocksW, int blocksH, boolean loop, long seekMs, float gain, int height, Vec3 center, float distance) {
        Path file = cachedFile(url, height);
        if (file == null) return false;
        sink.onCachedFileUsed(file.toString(), size(file));
        SpatialAudio.Output previousAudio; Thread previousThread; Thread nextThread;
        synchronized (lifecycleLock) {
            this.gain = Math.max(0f, gain);
            running = false; videoBaseTimestampUs = -1;
            previousThread = thread; thread = null;
            previousAudio = audio; audio = null;
            running = true;
            nextThread = new Thread(() -> decode(file, blocksW, blocksH, loop, seekMs, center, distance), "MineCanvas-VideoPlayer");
            nextThread.setDaemon(true); thread = nextThread;
        }
        if (previousAudio != null) previousAudio.close();
        if (previousThread != null) previousThread.interrupt();
        synchronized (lifecycleLock) { if (running && thread == nextThread) nextThread.start(); }
        return true;
    }
    public void setGain(float gain) { SpatialAudio.Output current; synchronized (lifecycleLock) { this.gain = Math.max(0f, gain); current = audio; } if (current != null) current.setGain(this.gain); }
    public void stop() {
        Thread old; SpatialAudio.Output current;
        synchronized (lifecycleLock) { running = false; videoBaseTimestampUs = -1; old = thread; thread = null; current = audio; audio = null; }
        if (current != null) current.close();
        if (old != null) old.interrupt();
    }
    long playbackPositionUs() {
        SpatialAudio.Output current; long base;
        synchronized (lifecycleLock) { current = audio; base = videoBaseTimestampUs; }
        return current == null || base < 0 ? -1 : current.positionUs(base);
    }
    boolean publishIfCurrent(Thread owner, Runnable publication) {
        synchronized (lifecycleLock) {
            if (!running || thread != owner) return false;
            publication.run();
            return true;
        }
    }

    static VideoPrefetcher.PathResult ensureCachedToDisk(String originalUrl, int height, FrameSink sink,
                                                         BooleanSupplier cancelled, Consumer<Runnable> setAbort) {
        String key = originalUrl + "#" + height;
        Object lock = LOCKS.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            Path ready = cachedFile(originalUrl, height);
            if (ready != null) { long size = size(ready); sink.onDownloadProgressBytes(100, size, size); return new VideoPrefetcher.PathResult(ready, size, size); }
            try {
                if (cancelled.getAsBoolean()) return new VideoPrefetcher.PathResult(null, 0, 0);
                String url = originalUrl;
                if (PlatformResolver.isSupportedPlatformUrl(url)) {
                    PlatformResolver.PlatformResult result = PlatformResolver.resolvePrefetch(url, height, sink, cancelled, setAbort);
                    if (cancelled.getAsBoolean()) return new VideoPrefetcher.PathResult(null, 0, 0);
                    if (!result.isSuccess()) return new VideoPrefetcher.PathResult(null, 0, 0);
                    url = result.directUrl();
                    Path resolved = Path.of(url);
                    if (Files.isRegularFile(resolved)) {
                        if (cancelled.getAsBoolean()) return new VideoPrefetcher.PathResult(null, 0, 0);
                        READY.put(key, resolved); long size = size(resolved); sink.onDownloadProgressBytes(100, size, size); evict(cacheDir(), resolved); return new VideoPrefetcher.PathResult(resolved, size, size);
                    }
                }
                return download(key, url, sink, cancelled, setAbort);
            } catch (Exception ignored) { return new VideoPrefetcher.PathResult(null, 0, 0); }
        }
    }
    static Path cachedFile(String url, int height) {
        String key = url + "#" + height;
        Path path = READY.get(key);
        if (path != null) {
            if (validMedia(path)) return path;
            READY.remove(key);
            IN_USE.remove(path);
        }
        try {
            Path found = findCached(cacheDir(), sha256(key));
            if (found != null && validMedia(found)) { READY.put(key, found); return found; }
        } catch (Exception ignored) { }
        return null;
    }
    private static VideoPrefetcher.PathResult download(String key, String url, FrameSink sink, BooleanSupplier cancelled,
                                                       Consumer<Runnable> setAbort) throws Exception {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return new VideoPrefetcher.PathResult(null, 0, 0);
        Path dir = cacheDir(); Files.createDirectories(dir); String hash = sha256(key);
        Path existing = findCached(dir, hash);
        if (existing != null && validMedia(existing)) { if (!cancelled.getAsBoolean()) READY.put(key, existing); long size = size(existing); sink.onDownloadProgressBytes(100, size, size); return new VideoPrefetcher.PathResult(existing, size, size); }
        Path part = partialPath(dir, hash); long resume = Files.isRegularFile(part) ? size(part) : 0;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000); connection.setReadTimeout(60_000); connection.setRequestProperty("Accept-Encoding", "identity");
        if (resume > 0) connection.setRequestProperty("Range", "bytes=" + resume + "-");
        setAbort.accept(connection::disconnect);
        long written = resume;
        try {
            if (cancelled.getAsBoolean()) return new VideoPrefetcher.PathResult(null, written, 0);
            int status = connection.getResponseCode();
            boolean resumed = resume > 0 && status == HttpURLConnection.HTTP_PARTIAL;
            if (!resumed && resume > 0) { Files.deleteIfExists(part); resume = 0; written = 0; }
            if (status < 200 || status >= 300) return new VideoPrefetcher.PathResult(null, written, 0);
            long length = connection.getContentLengthLong();
            long total = responseTotalBytes(length, resumed ? resume : 0, connection.getHeaderField("Content-Range"));
            String ext = extension(url, connection.getHeaderField("Content-Type"));
            sink.onDownloadStart("minecanvas.prefetch.download");
            sink.onDownloadProgressBytes(total > 0 ? (int) Math.min(100L, written * 100L / total) : 0, written, total);
            WRITING.put(part, Boolean.TRUE);
            try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(part, StandardOpenOption.CREATE, resumed ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] bytes = new byte[65536]; int read;
                while ((read = input.read(bytes)) >= 0) {
                    if (cancelled.getAsBoolean()) return new VideoPrefetcher.PathResult(null, written, total);
                    output.write(bytes, 0, read); written += read;
                    sink.onDownloadProgressBytes(total > 0 ? (int) Math.min(100L, written * 100L / total) : 0, written, total);
                }
            }
            if (cancelled.getAsBoolean()) return new VideoPrefetcher.PathResult(null, written, total);
            if (total > 0 && written != total || !validMedia(part)) { Files.deleteIfExists(part); return new VideoPrefetcher.PathResult(null, written, total); }
            Path target = dir.resolve(hash + ext);
            try { Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(part, target, StandardCopyOption.REPLACE_EXISTING); }
            if (!cancelled.getAsBoolean()) READY.put(key, target);
            evict(dir, target);
            return new VideoPrefetcher.PathResult(target, written, total == 0 ? written : total);
        } finally {
            WRITING.remove(part);
            setAbort.accept(null);
            connection.disconnect();
        }
    }
    private void decode(Path file, int blocksW, int blocksH, boolean loop, long seekMs, Vec3 center, float distance) {
        // Identity of the thread that owns this decode run. stop()+start()
        // replaces `thread` while the old decoder may still be blocked in a
        // native grab() (interrupt is a no-op there); without this check the
        // old thread sees running==true again (set by the new start()) and
        // keeps feeding frames from the OLD position alongside the new
        // decoder - the visible "double image" after a seek. A stale thread
        // must also NOT fire sink.onStop(), or its pendingStop would reset
        // the fresh playback on the next tick.
        Thread self = Thread.currentThread();
        IN_USE.computeIfAbsent(file, ignored -> new AtomicInteger()).incrementAndGet();
        try {
            do {
                try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toString())) {
                    grabber.setPixelFormat(avutil.AV_PIX_FMT_RGBA);
                    grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16); grabber.setAudioChannels(2); grabber.setSampleRate(48000); grabber.start();
                    SpatialAudio.Output currentAudio = null;
                    try {
                        try {
                            SpatialAudio.Output openedAudio = SpatialAudio.create(center, distance);
                            if (openedAudio == null) openedAudio = new VideoAudioPlayer(grabber.getSampleRate(), 2);
                            SpatialAudio.Output selectedAudio = openedAudio;
                            if (!publishIfCurrent(self, () -> { selectedAudio.setGain(this.gain); audio = selectedAudio; })) { selectedAudio.close(); return; }
                            currentAudio = selectedAudio;
                        } catch (Exception e) {
                            if (AUDIO_UNAVAILABLE_LOGGED.compareAndSet(false, true)) System.err.println("[MineCanvas] Audio unavailable: " + e.getMessage());
                        }
                    int width = grabber.getImageWidth(), height = grabber.getImageHeight(); double fps = Math.max(1, grabber.getVideoFrameRate());
                    VideoSizeUtil.Size target = VideoSizeUtil.pick(blocksW, blocksH, width, height);
                    grabber.setImageWidth(target.w()); grabber.setImageHeight(target.h());
                    if (!publishIfCurrent(self, () -> sink.initVideo(width, height, target.w(), target.h(), fps))) return;
                    long durationMs = Math.max(0, grabber.getLengthInTime() / 1000);
                    if (!publishIfCurrent(self, () -> sink.onDuration(durationMs))) return;
                    if (seekMs > 0) grabber.setTimestamp(seekMs * 1000);
                    long base = -1; boolean audioStarted = false;
                    while (running && thread == self) {
                        Frame frame = grabber.grab();
                        if (!running || thread != self) return;
                        if (frame == null) break;
                        if (currentAudio != null && frame.samples != null) {
                            if (audioStarted) currentAudio.write(frame.samples, 2, frame.timestamp);
                            else currentAudio.prebuffer(frame.samples, 2, frame.timestamp);
                        }
                        if (!running || thread != self) return;
                        if (frame.image == null) continue;
                        while (running && thread == self && !sink.canAcceptFrame()) Thread.sleep(1);
                        if (!running || thread != self) return;
                        int[] output = sink.borrowBuffer(); if (output == null || frame.image == null) continue;
                        if (!copyFrameRows(frame, output, target.w(), target.h())) { sink.returnBuffer(output); continue; }
                        boolean accepted = false;
                        try {
                            if (base < 0) {
                                long firstTimestampUs = frame.timestamp; long wallStartNs = System.nanoTime();
                                if (!publishIfCurrent(self, () -> { videoBaseTimestampUs = firstTimestampUs; sink.onPlaybackClockStart(wallStartNs); })) return;
                                base = firstTimestampUs;
                                if (currentAudio != null) currentAudio.start(base);
                                audioStarted = true;
                            }
                            long timestampUs = Math.max(0, frame.timestamp - base);
                            accepted = publishIfCurrent(self, () -> sink.onFrame(output, target.w(), target.h(), timestampUs));
                            if (!accepted) return;
                        } finally {
                            if (!accepted) sink.returnBuffer(output);
                        }
                    }
                    long endedDurationMs = Math.max(0, grabber.getLengthInTime() / 1000);
                    publishIfCurrent(self, () -> sink.onEnded(endedDurationMs));
                    } finally {
                        SpatialAudio.Output closingAudio = currentAudio;
                        if (closingAudio != null) closingAudio.close();
                        publishIfCurrent(self, () -> { if (audio == closingAudio) audio = null; });
                    }
                }
                seekMs = 0;
            } while (running && loop && thread == self);
        } catch (Exception ignored) { } finally {
            AtomicInteger uses = IN_USE.get(file);
            if (uses != null) uses.decrementAndGet();
            publishIfCurrent(self, sink::onStop);
        }
    }
    static boolean copyFrameRows(Frame frame, int[] output, int w, int h) {
        if (frame == null || frame.image == null || frame.image.length == 0 || !(frame.image[0] instanceof ByteBuffer source) || output.length < w * h) return false;
        int rowBytes = frame.imageStride <= 0 ? w * 4 : frame.imageStride;
        if (source.remaining() < (long) h * rowBytes) return false;
        IntBuffer rows = source.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        if (rowBytes == w * 4) { rows.get(output, 0, w * h); return true; }
        int rowInts = rowBytes >>> 2;
        if (rowInts < w) return false;
        for (int y = 0; y < h; y++) { rows.position(y * rowInts); rows.get(output, y * w, w); }
        return true;
    }
    private static boolean validMedia(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] h = input.readNBytes(12); if (h.length < 4) return false;
            return h.length >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p' || h[0] == 0x1A && h[1] == 0x45 && h[2] == (byte) 0xDF && h[3] == (byte) 0xA3;
        } catch (Exception ignored) { return false; }
    }
    private static String extension(String url, String type) { String lower = type == null ? "" : type.toLowerCase(Locale.ROOT); if (lower.contains("webm")) return ".webm"; if (lower.contains("matroska")) return ".mkv"; if (lower.contains("quicktime")) return ".mov"; return ".mp4"; }
    static Path findCached(Path dir, String hash) throws Exception { try (var files = Files.walk(dir)) { return files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().startsWith(hash + ".") && !path.getFileName().toString().endsWith(".part")).findFirst().orElse(null); } }
    static Path cacheDir() { try { return FabricLoader.getInstance().getGameDir().resolve("mine-canvas-cache"); } catch (Exception ignored) { return Path.of("mine-canvas-cache"); } }
    static Path partialPath(Path dir, String hash) { return dir.resolve(hash + ".part"); }
    static long responseTotalBytes(long contentLength, long resumedBytes, String contentRange) {
        if (contentRange != null) {
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < contentRange.length()) {
                try { return Math.max(0L, Long.parseLong(contentRange.substring(slash + 1).trim())); }
                catch (NumberFormatException ignored) { }
            }
        }
        return contentLength < 0 ? 0 : contentLength + Math.max(0L, resumedBytes);
    }
    private static long size(Path path) { try { return Files.size(path); } catch (Exception ignored) { return 0; } }
    private static String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ignored) { return Integer.toHexString(value.hashCode()); } }
    private static void evict(Path dir, Path keep) { long limit = (long) MineCanvasClientConfig.get().maxCacheGiB << 30; try (var files = Files.walk(dir)) { var list = files.filter(Files::isRegularFile).filter(path -> !path.getFileName().toString().endsWith(".part")).sorted(Comparator.comparingLong(VideoPlayer::modified)).toList(); long used = list.stream().mapToLong(VideoPlayer::size).sum(); Path kept = keep == null ? null : keep.toAbsolutePath().normalize(); for (Path path : list) { if (used <= limit) break; if (kept != null && path.toAbsolutePath().normalize().equals(kept)) continue; AtomicInteger inUse = IN_USE.get(path); if (inUse != null && inUse.get() > 0) continue; long bytes = size(path); if (Files.deleteIfExists(path)) used -= bytes; } } catch (Exception ignored) { } }
    private static long modified(Path path) { try { return Files.getLastModifiedTime(path).toMillis(); } catch (Exception ignored) { return 0; } }
    public static CacheInfo getCacheInfo() { Path dir = cacheDir(); try (var files = Files.isDirectory(dir) ? Files.walk(dir) : java.util.stream.Stream.<Path>empty()) { var list = files.filter(Files::isRegularFile).toList(); return new CacheInfo(list.stream().mapToLong(VideoPlayer::size).sum(), list.size()); } catch (Exception ignored) { return new CacheInfo(0, 0); } }
    public static long clearCache() { VideoPrefetcher.cancelAllAndWait(); long deleted = 0; Path dir = cacheDir(); try (var files = Files.isDirectory(dir) ? Files.walk(dir) : java.util.stream.Stream.<Path>empty()) { for (Path path : files.toList()) { AtomicInteger inUse = IN_USE.get(path); if (WRITING.containsKey(path) || inUse != null && inUse.get() > 0) continue; if (Files.isRegularFile(path)) { long bytes = size(path); if (Files.deleteIfExists(path)) deleted += bytes; } } } catch (Exception ignored) { } READY.entrySet().removeIf(entry -> !Files.exists(entry.getValue())); IN_USE.entrySet().removeIf(entry -> !Files.exists(entry.getKey())); return deleted; }
}
