package org.sawiq.minecanvas.fabric.client.video;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves platform URLs to local cached playable files using yt-dlp + ffmpeg.
 */
public final class PlatformResolver {
    private static final String META_PREFIX = "MineCanvasMeta:";

    /**
     * Live set of yt-dlp / ffmpeg subprocesses so a stale prefetch can be
     * aborted. Without this an abandoned prefetch kept running for up to 30
     * minutes (its waitFor timeout), holding native pipes and the single
     * MineCanvas-Prefetch worker, which blocked prefetch for other screens.
     * Membership is bounded by the number of in-flight downloads.
     */
    private static final Set<Process> ACTIVE_DOWNLOAD_PROCESSES = ConcurrentHashMap.newKeySet();
    private static final java.util.Map<String, DownloadLock> DOWNLOAD_LOCKS = new ConcurrentHashMap<>();
    private static final class DownloadLock { private int users; }

    /**
     * Forcibly terminates every in-flight yt-dlp / ffmpeg subprocess.
     * Partial artifacts are kept so a later attempt can resume them.
     * Idempotent and safe to call from any thread. Each process evicts itself
     * from the registry in its own finally block, so this does not clear().
     */
    static void cancelPrefetchDownloads() {
        for (Process p : ACTIVE_DOWNLOAD_PROCESSES.toArray(new Process[0])) {
            p.destroyForcibly();
        }
    }

    private static final Pattern DOWNLOAD_PROGRESS_PATTERN = Pattern.compile(".*?(\\d{1,3}(?:\\.\\d+)?)%.*?of\\s+~?\\s*(\\d+(?:\\.\\d+)?)(?:\\s*)(B|KiB|MiB|GiB|TiB).*");
    private static final Pattern DOWNLOAD_PERCENT_PATTERN = Pattern.compile(".*?(\\d{1,3}(?:\\.\\d+)?)%.*");
    private static final Pattern DOWNLOAD_SIZE_PATTERN = Pattern.compile(".*?(\\d+(?:\\.\\d+)?)(?:\\s*)(B|KiB|MiB|GiB|TiB).*");

    // ponytail: debug toggle removed from config; dev-only escape hatch via -Dminecanvas.debug
    private static final boolean DEBUG = Boolean.getBoolean("minecanvas.debug");

    private static void dbg(String msg) {
        if (!DEBUG) return;
        try {
            System.out.println("[MineCanvas] " + redactLog(msg));
        } catch (Exception ignored) {
        }
    }

    private static String redactLog(String message) {
        if (message == null) return "";
        Matcher matcher = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE).matcher(message);
        StringBuilder safe = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(safe, Matcher.quoteReplacement(redact(matcher.group())));
        return matcher.appendTail(safe).toString().replaceAll(
            "(?i)(token|cookie|signature|sig|auth|key)=([^\\s&]+)", "$1=<redacted>");
    }

    // RuTube videos use a 32-char lowercase hex id under several path prefixes.
    // Mirrors the canonical match in yt-dlp's RutubeIE._VALID_URL.
    private static final Pattern RUTUBE_PATTERN = Pattern.compile(
        "(?:https?://)?(?:www\\.)?rutube\\.ru/(?:(?:live/)?video(?:/private)?|(?:play/)?embed|shorts)/([0-9a-f]{32})"
    );

    // VK videos always reference a (owner_id)_(video_id) pair. Owner can be
    // negative (group/community). Hosts: vk.com, m.vk.com, vkvideo.ru, m.vkvideo.ru.
    // Mirrors the relevant subset of yt-dlp's VKVideoIE._VALID_URL.
    private static final Pattern VK_PATTERN = Pattern.compile(
        "(?:https?://)?(?:(?:www\\.|m\\.|new\\.)?vk\\.com|(?:www\\.|m\\.)?vkvideo\\.ru)/(?:video|clip|live|video_ext\\.php\\?[^#]*?\\boid=(-?\\d+)[^#]*?\\bid=(\\d+)|[^?#]*?\\bz=video)(-?\\d+_\\d+)?"
    );

    /**
     * VK live links ({@code /live-<owner>_<id>}) are not recognized by yt-dlp:
     * the same broadcast is reachable as {@code /video-<owner>_<id>} once it has
     * ended, so normalize before resolving. Active broadcasts are still refused
     * downstream by the VOD pipeline ({@code --match-filters !is_live}).
     */
    private static final Pattern VK_LIVE_PATTERN = Pattern.compile(
        "(?i)^(https?://(?:(?:www\\.|m\\.|new\\.)?vk\\.com|(?:www\\.|m\\.)?vkvideo\\.ru))/live(-?\\d+_\\d+)");

    private static String normalizeVkLiveUrl(String url) {
        if (url == null) return null;
        return VK_LIVE_PATTERN.matcher(url).replaceFirst("$1/video$2");
    }

    /**
     * Single-source-of-truth platform classifier for any URL we know how to
     * resolve via yt-dlp + ffmpeg. Each entry carries the on-disk cache
     * subdirectory (relative to {@code mine-canvas-cache/}).
     */
    private enum Platform {
        RUTUBE("rutube"),
        VK("vk");

        final String cacheSubdir;

        Platform(String cacheSubdir) {
            this.cacheSubdir = cacheSubdir;
        }
    }

    /**
     * Resolved URL cache. Bounded LRU (max {@value #URL_CACHE_MAX} entries)
     * to prevent unbounded growth across long sessions; entries also expire
     * after {@link #URL_CACHE_TTL_MS}. Each cache key is
     * {@code videoId|quality} so the cap is plenty for any realistic
     * single-user playback history.
     */
    private static final int URL_CACHE_MAX = 64;
    @SuppressWarnings("serial")
    private static final java.util.Map<String, ResolvedUrl> URL_CACHE =
        java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, ResolvedUrl>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, ResolvedUrl> eldest) {
                    return size() > URL_CACHE_MAX;
                }
            });
    private static final long URL_CACHE_TTL_MS = 5L * 60L * 60L * 1000L;
    private static final Object TOOL_LOCK = new Object();

    // ----- Cross-platform tool resolution --------------------------------
    // Resolved at class-init from os.name / os.arch so we pick the right
    // yt-dlp / ffmpeg binary on Windows, Linux (x64 + arm64) and macOS.
    // Without this every Linux server crashed with `error=13, Permission
    // denied` because we shipped only `yt-dlp.exe` and never set the
    // executable bit even when the user supplied a Linux binary manually.
    private enum Os { WINDOWS, LINUX, MACOS, OTHER }
    private enum Arch { X64, ARM64, OTHER }

    private static final Os HOST_OS = detectOs();
    private static final Arch HOST_ARCH = detectArch();
    private static final boolean POSIX = HOST_OS == Os.LINUX || HOST_OS == Os.MACOS;

    private static Os detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return Os.WINDOWS;
        if (name.contains("mac") || name.contains("darwin")) return Os.MACOS;
        if (name.contains("nix") || name.contains("nux") || name.contains("aix")) return Os.LINUX;
        return Os.OTHER;
    }

    private static Arch detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) return Arch.ARM64;
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) return Arch.X64;
        return Arch.OTHER;
    }

    private static String ytdlpBinaryName() {
        return HOST_OS == Os.WINDOWS ? "yt-dlp.exe" : "yt-dlp";
    }

    private static String ffmpegBinaryName() {
        return HOST_OS == Os.WINDOWS ? "ffmpeg.exe" : "ffmpeg";
    }

    private static String ytdlpDownloadUrl() {
        String base = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
        return switch (HOST_OS) {
            case WINDOWS -> base + "yt-dlp.exe";
            case LINUX -> base + (HOST_ARCH == Arch.ARM64 ? "yt-dlp_linux_aarch64" : "yt-dlp_linux");
            case MACOS -> base + "yt-dlp_macos";
            case OTHER -> null;
        };
    }

    /**
     * BtbN ships builds for Windows and Linux (x64 + arm64) only - there is
     * no macOS build in that project at all, so the old osx64 URL returned
     * HTTP 404 on every Mac and users were forced to install ffmpeg by hand.
     * For macOS we use Martin Riedl's static builds, which cover both
     * Apple Silicon (arm64) and Intel (amd64) as single-binary zips.
     */
    private static String ffmpegDownloadUrl() {
        String base = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-";
        return switch (HOST_OS) {
            case WINDOWS -> base + "win64-gpl.zip";
            case LINUX -> base + (HOST_ARCH == Arch.ARM64 ? "linuxarm64-gpl.tar.xz" : "linux64-gpl.tar.xz");
            case MACOS -> "https://ffmpeg.martin-riedl.de/redirect/latest/macos/"
                + (HOST_ARCH == Arch.ARM64 ? "arm64" : "amd64") + "/release/ffmpeg.zip";
            case OTHER -> null;
        };
    }

    /**
     * Mark {@code path} as executable on POSIX systems. Without this the
     * downloaded yt-dlp / ffmpeg binary is created with the JVM's default
     * 644 permissions and {@link ProcessBuilder#start()} fails with
     * {@code IOException: Cannot run program ...: error=13, Permission
     * denied}.
     */
    private static void ensureExecutable(Path path) {
        if (!POSIX || path == null) return;
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (Exception e) {
            try { path.toFile().setExecutable(true, false); } catch (Exception ignored) {}
        }
    }

    private static volatile boolean ytdlpAvailable = false;
    private static volatile boolean ytdlpChecked = false;
    private static volatile boolean ytdlpDownloading = false;
    private static volatile int ytdlpDownloadProgress = 0;
    private static volatile boolean ffmpegAvailable = false;
    private static volatile boolean ffmpegChecked = false;

    private record ResolvedUrl(String directUrl, String videoId, long resolvedAtMs, long durationMs) {
        boolean isExpired() {
            return System.currentTimeMillis() - resolvedAtMs > URL_CACHE_TTL_MS;
        }
    }

    public record PlatformResult(
        String directUrl,
        String videoId,
        long durationMs,
        String error,
        boolean needsDownload
    ) {
        public boolean isSuccess() {
            return directUrl != null && !directUrl.isBlank();
        }
    }

    private record StreamMeta(String sourceId, long durationMs) {
    }

    public static boolean isRuTubeUrl(String url) {
        if (url == null || url.isBlank()) return false;
        return url.toLowerCase(Locale.ROOT).contains("rutube.ru");
    }

    public static boolean isVKUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("vk.com/video")
            || u.contains("vk.com/clip")
            || u.contains("vk.com/video_ext.php")
            || u.contains("vkvideo.ru");
    }

    public static boolean isSupportedPlatformUrl(String url) {
        return isRuTubeUrl(url) || isVKUrl(url);
    }

    private static String redact(String value) {
        try { URL url = new URL(value); return url.getProtocol() + "://" + url.getHost() + url.getPath(); }
        catch (Exception ignored) { return "<invalid-url>"; }
    }

    /**
     * Resolves a URL to its {@link Platform} or {@code null} if we don't
     * know how to handle it. Order matters - host checks are mutually
     * exclusive, so order is irrelevant for correctness.
     */
    private static Platform classifyPlatform(String url) {
        if (isRuTubeUrl(url)) return Platform.RUTUBE;
        if (isVKUrl(url)) return Platform.VK;
        return null;
    }

    /** RuTube id is a 32-char lowercase hex string. */
    private static String extractRuTubeId(String url) {
        if (url == null) return null;
        Matcher m = RUTUBE_PATTERN.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * VK videos are addressed by {@code <ownerId>_<videoId>}. The pattern
     * captures the pair either from the {@code /video<owner>_<id>}-style
     * path (group 3) or from the {@code video_ext.php?oid=...&id=...}
     * variant (groups 1 + 2). Returns the canonical
     * {@code <owner>_<id>} string, or {@code null} if the URL is malformed.
     */
    private static String extractVKId(String url) {
        if (url == null) return null;
        Matcher m = VK_PATTERN.matcher(url);
        if (!m.find()) return null;

        String ownerIdPair = m.group(3);
        if (ownerIdPair != null && !ownerIdPair.isBlank()) {
            return ownerIdPair;
        }
        String oid = m.group(1);
        String id = m.group(2);
        if (oid != null && id != null) {
            return oid + "_" + id;
        }
        return null;
    }

    /**
     * Returns the canonical source-id for the given URL on its platform, or
     * {@code null} if the URL doesn't belong to a known platform or the id
     * could not be extracted. Used as the cache-key suffix.
     */
    private static String extractPlatformId(Platform platform, String url) {
        if (platform == null || url == null) return null;
        return switch (platform) {
            case RUTUBE -> extractRuTubeId(url);
            case VK -> extractVKId(url);
        };
    }

    static PlatformResult resolvePrefetch(String url, int preferredHeight, VideoPlayer.FrameSink sink) {
        url = normalizeVkLiveUrl(url);
        if (url == null || url.isBlank()) {
            return new PlatformResult(null, null, 0, "Empty URL", false);
        }

        Platform platform = classifyPlatform(url);
        if (platform == null) {
            return new PlatformResult(null, null, 0, "Not a supported platform URL", false);
        }

        int targetHeight = normalizeTargetHeight(preferredHeight);

        String sourceId = extractPlatformId(platform, url);
        if (sourceId == null || sourceId.isBlank()) {
            sourceId = Integer.toHexString(url.hashCode());
        }

        // === VOD cache fast path (replays, no network). ===
        Path platformCacheDir = getPlatformCacheDir(platform);
        if (sourceId != null && !sourceId.isBlank() && !sourceId.equals(Integer.toHexString(url.hashCode()))) {
            String cacheKey = cacheKey(sourceId, targetHeight);
            ResolvedUrl cached = URL_CACHE.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                Path cachedPath = Path.of(cached.directUrl());
                if (Files.isRegularFile(cachedPath)) {
                    notifyCachedFileUsed(cachedPath, sink);
                    dbg("resolve: fast path memory-cached local file for " + platform + ":" + sourceId);
                    return new PlatformResult(cached.directUrl, sourceId, cached.durationMs, null, false);
                }
            }
            Path cachedFile = findCachedFile(platformCacheDir, sourceId, targetHeight);
            if (cachedFile != null) {
                long durationMs = readCachedDuration(platformCacheDir, sourceId);
                URL_CACHE.put(cacheKey, new ResolvedUrl(cachedFile.toString(), sourceId, System.currentTimeMillis(), durationMs));
                notifyCachedFileUsed(cachedFile, sink);
                dbg("resolve: fast path disk-cached file for " + platform + ":" + sourceId + " quality=" + targetHeight);
                return new PlatformResult(cachedFile.toString(), sourceId, durationMs, null, false);
            }
        }

        // === yt-dlp VOD path. ===
        if (!ensureYtdlpAvailable()) {
            if (ytdlpDownloading) {
                return new PlatformResult(null, sourceId, 0, "yt-dlp downloading: " + ytdlpDownloadProgress + "%", true);
            }
            return new PlatformResult(null, sourceId, 0, "yt-dlp not available", true);
        }

        try {
            StreamMeta meta = resolveStreamMeta(url);
            if ((meta.sourceId() != null) && !meta.sourceId().isBlank()) {
                sourceId = meta.sourceId();
            }

            // VOD branch: re-check cache with authoritative source id.
            String cacheKey = cacheKey(sourceId, targetHeight);
            ResolvedUrl cached = URL_CACHE.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                Path cachedPath = Path.of(cached.directUrl());
                if (Files.isRegularFile(cachedPath)) {
                    notifyCachedFileUsed(cachedPath, sink);
                    dbg("resolve: using memory-cached local file for " + platform + ":" + sourceId);
                    return new PlatformResult(cached.directUrl, sourceId, cached.durationMs, null, false);
                }
            }

            Path cachedFile = findCachedFile(platformCacheDir, sourceId, targetHeight);
            if (cachedFile != null) {
                long durationMs = readCachedDuration(platformCacheDir, sourceId);
                URL_CACHE.put(cacheKey, new ResolvedUrl(cachedFile.toString(), sourceId, System.currentTimeMillis(), durationMs));
                notifyCachedFileUsed(cachedFile, sink);
                dbg("resolve: using disk-cached local file for " + platform + ":" + sourceId + " quality=" + targetHeight);
                return new PlatformResult(cachedFile.toString(), sourceId, durationMs, null, false);
            }

            if (!ensureFfmpegAvailable()) {
                return new PlatformResult(null, sourceId, 0, "ffmpeg not available", true);
            }

            return downloadVodToCache(platform, url, sourceId, targetHeight, sink);
        } catch (Exception e) {
            dbg("resolve: metadata error " + e.getMessage());

            // Minimal fallback chain: for VOD platforms we
            // can still hit the disk cache (transient extractor failure
            // doesn't invalidate previously-downloaded files). Skip the
            // expensive yt-dlp binary refresh - it re-downloads ~20MB on
            // every failure.
            Path cachedFile = findCachedFile(platformCacheDir, sourceId, targetHeight);
            if (cachedFile != null) {
                long durationMs = readCachedDuration(platformCacheDir, sourceId);
                URL_CACHE.put(cacheKey(sourceId, targetHeight), new ResolvedUrl(cachedFile.toString(), sourceId, System.currentTimeMillis(), durationMs));
                notifyCachedFileUsed(cachedFile, sink);
                dbg("resolve: metadata fallback to cached " + platform + " file for " + sourceId);
                return new PlatformResult(cachedFile.toString(), sourceId, durationMs, null, false);
            }

            return new PlatformResult(null, sourceId, 0, "Resolution failed: " + e.getMessage(), false);
        }
    }

    public static boolean isYtdlpAvailable() {
        if (ytdlpChecked) return ytdlpAvailable;

        Path ytdlp = getYtdlpPath();
        ytdlpAvailable = Files.isRegularFile(ytdlp) && Files.isExecutable(ytdlp);
        ytdlpChecked = true;
        return ytdlpAvailable;
    }

    public static boolean isDownloading() {
        return ytdlpDownloading;
    }

    public static void downloadYtdlpAsync() {
        if (ytdlpDownloading || ytdlpAvailable) return;

        Thread t = new Thread(PlatformResolver::ensureYtdlpAvailable, "MineCanvas-YtdlpDownload");
        t.setDaemon(true);
        t.start();
    }

    /** Downloads ffmpeg in the background if not already present. Safe to call multiple times. */
    public static void downloadFfmpegAsync() {
        if (ffmpegAvailable) return;

        Thread t = new Thread(PlatformResolver::ensureFfmpegAvailable, "MineCanvas-FfmpegDownload");
        t.setDaemon(true);
        t.start();
    }

    private static boolean ensureYtdlpAvailable() {
        if (ytdlpAvailable) return true;

        synchronized (TOOL_LOCK) {
            Path ytdlp = getYtdlpPath();
            if (Files.isRegularFile(ytdlp)) {
                ytdlpAvailable = true;
                ytdlpChecked = true;
                return true;
            }

            dbg("ensureYtdlpAvailable: downloading yt-dlp...");
            ytdlpDownloading = true;
            ytdlpDownloadProgress = 0;

            try {
                String url = ytdlpDownloadUrl();
                if (url == null) {
                    dbg("ensureYtdlpAvailable: no yt-dlp build for " + HOST_OS + "/" + HOST_ARCH);
                    return false;
                }
                Files.createDirectories(getToolsDir());
                downloadFile(url, ytdlp, true);
                ytdlpAvailable = Files.isRegularFile(ytdlp);
                if (ytdlpAvailable) ensureExecutable(ytdlp);
                ytdlpChecked = true;
                return ytdlpAvailable;
            } catch (Exception e) {
                dbg("ensureYtdlpAvailable: download error " + e.getMessage());
                return false;
            } finally {
                ytdlpDownloading = false;
            }
        }
    }

    private static boolean ensureFfmpegAvailable() {
        if (ffmpegChecked && ffmpegAvailable) return true;

        synchronized (TOOL_LOCK) {
            Path ffmpeg = getFfmpegPath();
            if (Files.isRegularFile(ffmpeg)) {
                ffmpegAvailable = true;
                ffmpegChecked = true;
                return true;
            }

            try {
                String url = ffmpegDownloadUrl();
                if (url == null) {
                    dbg("ensureFfmpegAvailable: no ffmpeg build for " + HOST_OS + "/" + HOST_ARCH
                        + "; install ffmpeg manually and put it on PATH");
                    return false;
                }
                Files.createDirectories(getToolsDir());
                boolean isZip = url.endsWith(".zip");
                Path archiveTmp = getToolsDir().resolve(isZip ? "ffmpeg.zip.tmp" : "ffmpeg.tar.xz.tmp");
                downloadFile(url, archiveTmp, false);

                ffmpegAvailable = isZip
                    ? extractFfmpegFromZip(archiveTmp, ffmpeg)
                    : extractFfmpegFromTarXz(archiveTmp, ffmpeg);

                Files.deleteIfExists(archiveTmp);
                if (ffmpegAvailable) ensureExecutable(ffmpeg);
                ffmpegChecked = true;
                return ffmpegAvailable;
            } catch (Exception e) {
                dbg("ensureFfmpegAvailable: error " + e.getMessage());
                return false;
            }
        }
    }

    private static boolean downloadFile(String urlStr, Path target, boolean trackProgress) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(300_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 MineCanvas-Fabric");

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            return false;
        }

        long contentLength = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream(); var out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long written = 0;
            int r;
            while ((r = in.read(buf)) >= 0) {
                out.write(buf, 0, r);
                written += r;
                if (trackProgress && contentLength > 0) {
                    ytdlpDownloadProgress = (int) (written * 100 / contentLength);
                }
            }
        }
        conn.disconnect();
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /**
     * Generic VOD download pipeline. Runs {@code yt-dlp} against
     * {@code sourceUrl} and saves the resulting file under the platform's
     * cache directory using {@code sourceId} as the filename prefix.
     *
     * <p>RuTube and VK use this code path with their original URL and
     * extracted source id.</p>
     */
    private static PlatformResult downloadVodToCache(Platform platform,
                                                      String sourceUrl,
                                                      String sourceId,
                                                      int preferredHeight,
                                                      VideoPlayer.FrameSink sink) throws Exception {
        int targetHeight = normalizeTargetHeight(preferredHeight);
        String key = platform + ":" + cacheKey(sourceId, targetHeight);
        DownloadLock lock = DOWNLOAD_LOCKS.compute(key, (ignored, current) -> {
            DownloadLock retained = current == null ? new DownloadLock() : current;
            retained.users++;
            return retained;
        });
        try {
            synchronized (lock) {
                Path cacheDir = getPlatformCacheDir(platform);
                Path cachedFile = findCachedFile(cacheDir, sourceId, targetHeight);
                if (cachedFile != null) {
                    long durationMs = readCachedDuration(cacheDir, sourceId);
                    URL_CACHE.put(cacheKey(sourceId, targetHeight), new ResolvedUrl(cachedFile.toString(), sourceId, System.currentTimeMillis(), durationMs));
                    notifyCachedFileUsed(cachedFile, sink);
                    return new PlatformResult(cachedFile.toString(), sourceId, durationMs, null, false);
                }
                return downloadVodToCacheLocked(platform, sourceUrl, sourceId, targetHeight, sink);
            }
        } finally {
            DOWNLOAD_LOCKS.computeIfPresent(key, (ignored, current) -> {
                if (current != lock) return current;
                return --current.users == 0 ? null : current;
            });
        }
    }

    private static PlatformResult downloadVodToCacheLocked(Platform platform,
                                                           String sourceUrl,
                                                           String sourceId,
                                                           int preferredHeight,
                                                           VideoPlayer.FrameSink sink) throws Exception {
        int targetHeight = normalizeTargetHeight(preferredHeight);
        Path cacheDir = getPlatformCacheDir(platform);
        Files.createDirectories(cacheDir);
        String cachePrefix = buildCachePrefix(sourceId, targetHeight);

        Path outputBase = cacheDir.resolve(cachePrefix + ".%(ext)s");

        String selector = buildDownloadFormatSelector(targetHeight);
        // Estimate size from the --print before_dl callback emitted by yt-dlp during the actual download.
        // Skipping the extra --simulate run saves an extra subprocess.
        long estimatedTotalBytes = 0L;

        List<String> command = new ArrayList<>();
        command.add(getYtdlpPath().toString());
        command.add("-f");
        command.add(selector);
        command.add("--merge-output-format");
        command.add("mkv");
        command.add("--continue");
        // Hard refusal to download live streams through the VOD pipeline:
        // yt-dlp would otherwise record the live feed indefinitely. With
        // the filter it skips the entry and exits quickly, which surfaces
        // as a normal "Downloaded file not found" error to the caller.
        command.add("--match-filters");
        command.add("!is_live");
        command.add("--concurrent-fragments");
        command.add("4");
        command.add("--no-playlist");
        command.add("--no-warnings");
        command.add("--no-check-certificates");
        command.add("--no-cache-dir");
        command.add("--retries");
        command.add("3");
        command.add("--fragment-retries");
        command.add("5");
        command.add("--socket-timeout");
        command.add("15");
        command.add("--extractor-retries");
        command.add("2");
        command.add("--newline");
        // Six progress fields: percent, downloaded_bytes, total_bytes,
        // total_bytes_estimate, fragment_index, fragment_count. The last
        // two are critical for HLS / DASH (RuTube live, RuTube HLS,
        // some VK formats) where total_bytes is often NA throughout the
        // entire download - fragments give us a monotonic, reliable
        // progress signal even when byte estimates aren't available.
        command.add("--progress-template");
        command.add("download:MineCanvasProgress:%(progress.percent)s:%(progress.downloaded_bytes)s:%(progress.total_bytes)s:%(progress.total_bytes_estimate)s:%(progress.fragment_index)s:%(progress.fragment_count)s");
        command.add("--progress-template");
        command.add("download:fragment:MineCanvasProgress:%(progress.percent)s:%(progress.downloaded_bytes)s:%(progress.total_bytes)s:%(progress.total_bytes_estimate)s:%(progress.fragment_index)s:%(progress.fragment_count)s");
        command.add("--ffmpeg-location");
        command.add(getToolsDir().toString());
        command.add("--output");
        command.add(outputBase.toString());
        command.add("--print");
        command.add("before_dl:MineCanvasMeta:%(filesize)s:%(filesize_approx)s");
        command.add("--print");
        command.add("after_move:%(filepath)s");
        command.add("--print");
        command.add("%(duration)s");
        command.add(sourceUrl);

        dbg("downloadVodToCache: platform=" + platform + " selector=" + selector);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (sink != null) {
            String downloadingKey = switch (platform) {
                case RUTUBE -> "minecanvas.platform.rutube_downloading";
                case VK -> "minecanvas.platform.vk_downloading";
            };
            sink.onDownloadStart(downloadingKey);
        }

        Process p = pb.start();
        ACTIVE_DOWNLOAD_PROCESSES.add(p);
        StringBuilder output = new StringBuilder();
        String finalPath = null;
        long durationMs = 0L;
        AtomicLong totalEstimateBytes = new AtomicLong(Math.max(0L, estimatedTotalBytes));
        AtomicBoolean monitorRunning = new AtomicBoolean(sink != null);
        Thread progressMonitor = null;
        if (sink != null) {
            progressMonitor = new Thread(() -> monitorDownloadProgress(cacheDir, cachePrefix, totalEstimateBytes, monitorRunning, sink),
                "MineCanvas-Progress-" + cachePrefix);
            progressMonitor.setDaemon(true);
            progressMonitor.start();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                String trimmed = line.trim();
                if (trimmed.startsWith("before_dl:MineCanvasMeta:")) {
                    updatePrintedDownloadSize(trimmed, totalEstimateBytes);
                    // Surface the total size to the HUD the moment yt-dlp
                    // announces it (pre-transfer), so the progress line
                    // flips from "preparing..." to "X: 0% (0 MB / Y MB)"
                    // without waiting for the first progress tick. The
                    // Math.max merge in VideoScreen.onDownloadProgress
                    // protects against later "audio-only" announcements
                    // shrinking the displayed total.
                    if (sink != null) {
                        long announcedBytes = totalEstimateBytes.get();
                        if (announcedBytes > 0) {
                            sink.onDownloadProgressBytes(0, 0L, announcedBytes);
                        }
                    }
                }
                if (sink != null) {
                    updateDownloadProgressFromYtdlp(trimmed, sink, totalEstimateBytes);
                }
                if (trimmed.startsWith("after_move:")) {
                    finalPath = trimmed.substring("after_move:".length()).trim();
                } else if (durationMs == 0L) {
                    durationMs = parseDuration(trimmed);
                }
            }
        }

        boolean finished;
        try {
            finished = p.waitFor(30, TimeUnit.MINUTES);
        } finally {
            // Always evict from the active-process registry so that a later
            // Registry cleanup does not touch an already-dead process.
            // pid (typically harmless but creates noisy logs on some JDKs).
            ACTIVE_DOWNLOAD_PROCESSES.remove(p);
        }
        monitorRunning.set(false);
        if (progressMonitor != null) {
            progressMonitor.interrupt();
            try {
                progressMonitor.join(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!finished) {
            p.destroyForcibly();
            return new PlatformResult(null, sourceId, 0, "yt-dlp timeout while downloading video", false);
        }

        if (p.exitValue() != 0) {
            String err = output.toString().trim();
            if (err.length() > 300) err = err.substring(0, 300) + "...";
            return new PlatformResult(null, sourceId, 0, "yt-dlp download error: " + err, false);
        }

        Path finalFile = null;
        if (finalPath != null && !finalPath.isBlank()) {
            Path candidate = Path.of(finalPath);
            if (Files.isRegularFile(candidate)) {
                finalFile = candidate;
            }
        }
        if (finalFile == null) {
            finalFile = findCachedFile(cacheDir, sourceId, targetHeight);
        }
        if (finalFile == null) {
            return new PlatformResult(null, sourceId, 0, "Downloaded file not found", false);
        }

        writeCachedDuration(cacheDir, sourceId, durationMs);
        URL_CACHE.put(cacheKey(sourceId, targetHeight), new ResolvedUrl(finalFile.toString(), sourceId, System.currentTimeMillis(), durationMs));
        notifyCachedFileUsed(finalFile, sink);
        dbg("downloadVodToCache: platform=" + platform + " cached file=" + finalFile);
        return new PlatformResult(finalFile.toString(), sourceId, durationMs, null, false);
    }

    private static void notifyCachedFileUsed(Path file, VideoPlayer.FrameSink sink) {
        if (sink == null || file == null) return;
        try {
            sink.onCachedFileUsed(file.toString(), Files.size(file));
        } catch (Exception ignored) {
        }
    }

    private static void updateDownloadProgressFromYtdlp(String line, VideoPlayer.FrameSink sink, AtomicLong totalEstimateAtomic) {
        if (line == null || line.isBlank()) return;

        try {
            String clean = line.replaceAll("\\u001B\\[[;\\d]*m", "").trim();
            String payload = null;
            int progressIdx = clean.indexOf("MineCanvasProgress:");
            if (progressIdx >= 0) {
                payload = clean.substring(progressIdx + "MineCanvasProgress:".length());
            }
            if (payload != null) {
                String[] parts = payload.split(":", -1);
                double percentRaw = parts.length > 0 ? parseDoubleSafe(parts[0]) : 0.0;
                long downloadedBytes = parts.length > 1 ? parseLongSafe(parts[1]) : 0L;
                long totalBytes = parts.length > 2 ? parseLongSafe(parts[2]) : 0L;
                long totalEstimateBytes = parts.length > 3 ? parseLongSafe(parts[3]) : 0L;
                long fragmentIndex = parts.length > 4 ? parseLongSafe(parts[4]) : 0L;
                long fragmentCount = parts.length > 5 ? parseLongSafe(parts[5]) : 0L;

                long totalBytesFinal = totalBytes > 0 ? totalBytes : totalEstimateBytes;

                // HLS / DASH fallback: yt-dlp leaves total_bytes and
                // total_bytes_estimate as NA for some fragment-based
                // formats (RuTube HLS, certain VK clips). Scale the
                // already-downloaded bytes by the fragment ratio to get
                // a usable total estimate. Refines on every tick as more
                // fragments arrive; VideoScreen.onDownloadProgress takes
                // a Math.max so the displayed total never shrinks.
                if (totalBytesFinal <= 0 && fragmentCount > 0 && fragmentIndex > 0 && downloadedBytes > 0) {
                    totalBytesFinal = Math.round((double) downloadedBytes * fragmentCount / fragmentIndex);
                }

                // Keep the disk-poll monitor in sync. Without this the
                // monitor thread would compute percent against a stale
                // 0 estimate and emit (0%, dlMb, 0) on every tick,
                // forcing the HUD into the "X MB..." (no percent) branch.
                final long observedTotalBytes = totalBytesFinal;
                if (observedTotalBytes > 0 && totalEstimateAtomic != null) {
                    totalEstimateAtomic.updateAndGet(prev -> Math.max(prev, observedTotalBytes));
                }

                // Preference order:
                // 1. yt-dlp's own percent (most accurate when available)
                // 2. fragment ratio (monotonic, ideal for HLS)
                // 3. byte ratio (fine for non-fragment formats)
                int percent;
                if (percentRaw > 0.0) {
                    percent = (int) Math.round(percentRaw);
                } else if (fragmentCount > 0 && fragmentIndex > 0) {
                    percent = (int) Math.min(100L, Math.round((fragmentIndex * 100.0) / fragmentCount));
                } else if (totalBytesFinal > 0 && downloadedBytes > 0) {
                    percent = (int) Math.round((downloadedBytes * 100.0) / totalBytesFinal);
                } else {
                    percent = 0;
                }
                if (percent > 0 || downloadedBytes > 0 || totalBytesFinal > 0) {
                    sink.onDownloadProgressBytes(percent, downloadedBytes, totalBytesFinal);
                    return;
                }
            }

            Matcher percentMatcher = DOWNLOAD_PERCENT_PATTERN.matcher(clean);
            if (!percentMatcher.matches()) return;

            int percent = (int) Math.round(Double.parseDouble(percentMatcher.group(1)));
            long downloadedMb = 0L;
            long totalMb = 0L;

            Matcher progressMatcher = DOWNLOAD_PROGRESS_PATTERN.matcher(clean);
            if (progressMatcher.matches()) {
                double totalValue = Double.parseDouble(progressMatcher.group(2));
                String unit = progressMatcher.group(3);
                totalMb = toMegabytes(totalValue, unit);
                downloadedMb = totalMb > 0 ? Math.max(0L, Math.round(totalMb * (percent / 100.0))) : 0L;
            } else {
                Matcher sizeMatcher = DOWNLOAD_SIZE_PATTERN.matcher(clean);
                if (sizeMatcher.matches()) {
                    double downloadedValue = Double.parseDouble(sizeMatcher.group(1));
                    String unit = sizeMatcher.group(2);
                    downloadedMb = toMegabytes(downloadedValue, unit);
                }
            }

            sink.onDownloadProgress(percent, downloadedMb, totalMb);
        } catch (Exception ignored) {
        }
    }

    private static long parseLongSafe(String raw) {
        if (raw == null || raw.isBlank() || "NA".equalsIgnoreCase(raw)) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double parseDoubleSafe(String raw) {
        if (raw == null || raw.isBlank() || "NA".equalsIgnoreCase(raw)) return 0.0;
        try {
            String clean = raw.trim().replace("%", "");
            if (clean.contains(",") && clean.contains(".")) {
                clean = clean.replace(",", "");
            } else {
                clean = clean.replace(",", ".");
            }
            return Double.parseDouble(clean);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static long toMegabytes(double value, String unit) {
        if (unit == null) return 0L;
        return switch (unit) {
            case "TiB" -> Math.max(1L, Math.round(value * 1024.0 * 1024.0));
            case "GiB" -> Math.max(1L, Math.round(value * 1024.0));
            case "KiB" -> Math.max(0L, Math.round(value / 1024.0));
            case "B" -> Math.max(0L, Math.round(value / (1024.0 * 1024.0)));
            default -> Math.max(1L, Math.round(value));
        };
    }

    private static String buildDownloadFormatSelector(int preferredHeight) {
        return "bestvideo[height<=" + preferredHeight + "][fps<=60][vcodec^=avc1]+bestaudio[acodec^=mp4a]"
            + "/bestvideo[height<=" + preferredHeight + "][fps<=60][ext=mp4]+bestaudio[ext=m4a]"
            + "/bestvideo[height<=" + preferredHeight + "][fps<=60][vcodec!*=av01]+bestaudio"
            + "/bestvideo[height<=" + preferredHeight + "]+bestaudio"
            + "/best[height<=" + preferredHeight + "][fps<=60]"
            + "/best[height<=" + preferredHeight + "]"
            + "/best";
    }

    private record ProcessResult(int exitCode, boolean timedOut, String output) {
    }

    private static ProcessResult runYtdlpCommand(List<String> command, long timeout, TimeUnit unit, String threadName) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        ACTIVE_DOWNLOAD_PROCESSES.add(process);

        StringBuilder output = new StringBuilder();
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (Exception ignored) {
            }
        }, threadName);
        readerThread.setDaemon(true);
        readerThread.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout, unit);
        } finally {
            ACTIVE_DOWNLOAD_PROCESSES.remove(process);
        }

        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }

        try {
            readerThread.join(1500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new ProcessResult(finished ? process.exitValue() : -1, !finished, output.toString());
    }

    private static StreamMeta resolveStreamMeta(String url) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(getYtdlpPath().toString());
        command.add("--print");
        command.add(META_PREFIX + "%(id)s\t%(duration)s");
        command.add("--skip-download");
        command.add("--no-playlist");
        command.add("--no-warnings");
        command.add("--no-check-certificates");
        command.add("--no-cache-dir");
        command.add("--quiet");
        command.add("--socket-timeout");
        command.add("10");
        command.add("--retries");
        command.add("1");
        command.add("--extractor-retries");
        command.add("1");
        command.add(url);

        ProcessResult result = runYtdlpCommand(command, 15, TimeUnit.SECONDS, "MineCanvas-YTDLP-Meta");
        String output = result.output();
        StreamMeta meta = null;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(META_PREFIX)) {
                meta = parsePrintedStreamMeta(trimmed);
            }
        }

        if (result.timedOut()) {
            throw new Exception("yt-dlp metadata timeout");
        }
        if (result.exitCode() != 0) {
            String err = output.trim();
            if (err.length() > 300) {
                err = err.substring(0, 300) + "...";
            }
            throw new Exception("yt-dlp metadata failed: " + err);
        }

        if (meta == null) {
            throw new Exception("yt-dlp metadata is empty");
        }
        return meta;
    }

    private static int normalizeTargetHeight(int preferredHeight) {
        return Math.max(360, Math.min(2160, preferredHeight));
    }

    private static String cacheKey(String videoId, int preferredHeight) {
        return videoId + "@" + normalizeTargetHeight(preferredHeight);
    }

    private static String buildCachePrefix(String videoId, int preferredHeight) {
        return videoId + "." + normalizeTargetHeight(preferredHeight) + "p";
    }

    private static StreamMeta parsePrintedStreamMeta(String line) {
        String payload = line.substring(META_PREFIX.length());
        String[] parts = payload.split("\t", -1);
        String sourceId = parts.length > 0 ? blankToNull(parts[0]) : null;
        String durationRaw = parts.length > 1 ? parts[1] : "";
        long durationMs = parseDurationToken(durationRaw);
        return new StreamMeta(sourceId, durationMs);
    }

    private static int extractCachedQuality(String name) {
        try {
            Matcher matcher = Pattern.compile("\\.(\\d{3,4})p\\.[^.]+$").matcher(name);
            if (!matcher.find()) {
                return 0;
            }
            return Integer.parseInt(matcher.group(1));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Looks for a file with the exact
     * {@code <id>.<height>p.<ext>} prefix first, then falls back (only for
     * the default quality) to the legacy {@code <id>.} prefix.
     */
    private static Path findCachedFile(Path cacheDir, String sourceId, int preferredHeight) {
        try {
            if (cacheDir == null || !Files.isDirectory(cacheDir)) return null;
            String exactPrefix = buildCachePrefix(sourceId, preferredHeight) + ".";
            try (var stream = Files.list(cacheDir)) {
                Path exact = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(exactPrefix))
                    .filter(PlatformResolver::isCompletedMediaFile)
                    .sorted(Comparator.comparingLong(PlatformResolver::safeLastModified).reversed())
                    .findFirst()
                    .orElse(null);
                if (exact != null) {
                    return exact;
                }
            }

            if (normalizeTargetHeight(preferredHeight) != VideoQuality.DEFAULT) {
                return null;
            }

            String legacyPrefix = sourceId + ".";
            try (var stream = Files.list(cacheDir)) {
                return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(legacyPrefix) && extractCachedQuality(name.toLowerCase(Locale.ROOT)) == 0;
                    })
                    .filter(PlatformResolver::isCompletedMediaFile)
                    .sorted(Comparator.comparingLong(PlatformResolver::safeLastModified).reversed())
                    .findFirst()
                    .orElse(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static long readCachedDuration(Path cacheDir, String sourceId) {
        try {
            Path path = cacheDir.resolve(sourceId + ".duration.txt");
            if (!Files.isRegularFile(path)) return 0L;
            return Long.parseLong(Files.readString(path).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void writeCachedDuration(Path cacheDir, String sourceId, long durationMs) {
        if (durationMs <= 0) return;
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(sourceId + ".duration.txt"), Long.toString(durationMs));
        } catch (Exception ignored) {
        }
    }

    private static long parseDuration(String duration) {
        if (duration == null || duration.isBlank()) return 0;

        try {
            String[] parts = duration.split(":");
            long seconds = 0;

            if (parts.length == 1) {
                double rawSeconds = Double.parseDouble(parts[0]);
                seconds = Math.round(rawSeconds);
            } else if (parts.length == 2) {
                seconds = Long.parseLong(parts[0]) * 60 + Math.round(Double.parseDouble(parts[1]));
            } else if (parts.length == 3) {
                seconds = Long.parseLong(parts[0]) * 3600
                    + Long.parseLong(parts[1]) * 60
                    + Math.round(Double.parseDouble(parts[2]));
            }

            return seconds * 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseDurationToken(String token) {
        if (token == null || token.isBlank() || "NA".equalsIgnoreCase(token)) {
            return 0L;
        }
        try {
            return Math.max(0L, Math.round(Double.parseDouble(token.trim()) * 1000.0));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isCompletedMediaFile(Path path) {
        if (path == null) return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".txt") || name.endsWith(".json") || name.endsWith(".description")) return false;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) return false;
        if (name.endsWith(".tmp") || name.endsWith(".temp") || name.endsWith(".ytdl") || name.contains(".part")) return false;
        return name.endsWith(".mp4")
            || name.endsWith(".mkv")
            || name.endsWith(".webm")
            || name.endsWith(".mov")
            || name.endsWith(".m4v");
    }

    private static long safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private static void updatePrintedDownloadSize(String line, AtomicLong totalEstimateBytes) {
        if (line == null || totalEstimateBytes == null) return;
        String payload = line.substring("before_dl:MineCanvasMeta:".length());
        String[] parts = payload.split(":", -1);
        long exactBytes = parts.length > 0 ? parseLongSafe(parts[0]) : 0L;
        long estimatedBytes = parts.length > 1 ? parseLongSafe(parts[1]) : 0L;
        long totalBytes = exactBytes > 0 ? exactBytes : estimatedBytes;
        if (totalBytes > 0) {
            totalEstimateBytes.set(totalBytes);
        }
    }

    private static void monitorDownloadProgress(Path cacheDir, String cachePrefix,
                                                AtomicLong totalEstimateBytes,
                                                AtomicBoolean running, VideoPlayer.FrameSink sink) {
        long lastBytes = -1L;
        while (running.get()) {
            try {
                long downloadedBytes = measureDownloadBytes(cacheDir, cachePrefix);
                if (downloadedBytes > 0 && downloadedBytes != lastBytes) {
                    lastBytes = downloadedBytes;
                    long totalBytes = totalEstimateBytes.get();
                    if (totalBytes > 0 && downloadedBytes > totalBytes) {
                        downloadedBytes = totalBytes;
                    }
                    int percent = totalBytes > 0
                        ? (int) Math.max(0L, Math.min(100L, Math.round((downloadedBytes * 100.0) / totalBytes)))
                        : 0;
                    sink.onDownloadProgressBytes(percent, downloadedBytes, totalBytes);
                }
                Thread.sleep(750L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
            }
        }
    }

    private static long measureDownloadBytes(Path cacheDir, String cachePrefix) {
        long total = 0L;
        try {
            if (cacheDir == null || !Files.isDirectory(cacheDir)) return 0L;
            try (var stream = Files.list(cacheDir)) {
                total = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(cachePrefix + "."))
                    .filter(path -> !path.getFileName().toString().endsWith(".txt"))
                    .filter(path -> !path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".description"))
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (Exception e) {
                            return 0L;
                        }
                    })
                    .sum();
            }
        } catch (Exception ignored) {
            return 0L;
        }
        return total;
    }

    private static Path getYtdlpPath() {
        String name = ytdlpBinaryName();
        try {
            return getToolsDir().resolve(name);
        } catch (Exception e) {
            return Path.of("mine-canvas-tools", name);
        }
    }

    private static Path getFfmpegPath() {
        String name = ffmpegBinaryName();
        try {
            return getToolsDir().resolve(name);
        } catch (Exception e) {
            return Path.of("mine-canvas-tools", name);
        }
    }

    private static boolean extractFfmpegFromZip(Path zipFile, Path ffmpegTarget) throws Exception {
        String wanted = ffmpegBinaryName();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                if (name.endsWith("/" + wanted) || name.endsWith("\\" + wanted) || name.equals(wanted)) {
                    Files.copy(zis, ffmpegTarget, StandardCopyOption.REPLACE_EXISTING);
                    return true;
                }
                zis.closeEntry();
            }
        }
        return false;
    }

    /**
     * Extract {@code ffmpeg} from a {@code .tar.xz} archive by shelling
     * out to the system {@code tar}. Every Linux / macOS host has a
     * {@code tar} that understands {@code -J} (xz), so this avoids
     * pulling in Apache Commons Compress just for the bootstrap path.
     */
    private static boolean extractFfmpegFromTarXz(Path archive, Path ffmpegTarget) {
        Path stage = null;
        try {
            stage = Files.createTempDirectory(getToolsDir(), "ffmpeg-stage-");
            ProcessBuilder pb = new ProcessBuilder("tar", "-xJf", archive.toAbsolutePath().toString(),
                "-C", stage.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var in = p.getInputStream()) {
                byte[] buf = new byte[8 * 1024];
                while (in.read(buf) >= 0) { /* drain */ }
            }
            int rc = p.waitFor();
            if (rc != 0) {
                dbg("tar exited with code " + rc);
                return false;
            }
            String wanted = ffmpegBinaryName();
            Path[] found = new Path[] { null };
            try (var stream = Files.walk(stage)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    if (found[0] == null && path.getFileName().toString().equals(wanted)) {
                        found[0] = path;
                    }
                });
            }
            if (found[0] == null) return false;
            Files.copy(found[0], ffmpegTarget, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            dbg("tar extract error: " + e.getMessage());
            return false;
        } finally {
            if (stage != null) {
                try (var stream = Files.walk(stage)) {
                    stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                } catch (Exception ignored) {}
            }
        }
    }

    private static Path getToolsDir() {
        try {
            return FabricLoader.getInstance().getGameDir().resolve("mine-canvas-tools");
        } catch (Exception e) {
            return Path.of("mine-canvas-tools");
        }
    }

    /**
     * Returns the on-disk cache directory for a given platform's downloaded
     * VOD files. All platforms share the same {@code mine-canvas-cache/} root;
     * each gets its own subdirectory named after {@link Platform#cacheSubdir}
     * so files don't collide and we can delete one platform's cache
     * independently.
     */
    private static Path getPlatformCacheDir(Platform platform) {
        String sub = platform != null ? platform.cacheSubdir : "video";
        try {
            return FabricLoader.getInstance().getGameDir().resolve("mine-canvas-cache").resolve(sub);
        } catch (Exception e) {
            return Path.of("mine-canvas-cache", sub);
        }
    }

}
