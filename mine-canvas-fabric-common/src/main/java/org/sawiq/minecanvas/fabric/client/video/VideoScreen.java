package org.sawiq.minecanvas.fabric.client.video;

import org.lwjgl.system.MemoryUtil;
import org.sawiq.minecanvas.fabric.client.config.MineCanvasClientConfig;
import org.sawiq.minecanvas.fabric.client.state.ScreenState;
import org.sawiq.minecanvas.fabric.client.video.audio.SpatialAudio;
import org.sawiq.minecanvas.fabric.mixin.NativeImageAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.IntBuffer;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public final class VideoScreen implements VideoPlayer.FrameSink {

    private static final long OUT_OF_RADIUS_GRACE_MS = 15_000L;
    private static final long RADIUS_AUDIO_HYSTERESIS_MS = 250L;
    private static final long DRIFT_RESYNC_THRESHOLD_MS = 1_500L;
    private static final long DRIFT_RESYNC_COOLDOWN_MS = 4_000L;
    private static final long START_RETRY_INTERVAL_MS = 1_000L;
    private static final int STANDBY_ABGR = 0xFF141010;

    private ScreenState state;

    private Identifier texId;
    private DynamicTexture texture;
    private volatile boolean textureHasContent;
    private String textureContentUrl = "";

    private VideoPlayer player;

    private int texW, texH;

    private long nativePtr = 0;
    private IntBuffer nativeDst = null;

    private volatile boolean started = false;
    private String startedUrl = "";
    private volatile long lastStartAttemptMs = 0;
    private float lastGain = -1f;
    private boolean lastSpatialAudioAvailable;

    private volatile long durationMs = 0;

    private volatile boolean ended = false;
    private volatile String endedUrl = "";
    private volatile long endedAtMs = 0; // Время окончания для автоскрытия action bar

    private volatile long lastInRadiusAtMs = 0;
    private volatile boolean pausedByRadius = false;
    private volatile boolean mutedByRadius = false;
    private volatile long outOfRadiusSinceMs = 0;

    private volatile boolean displayFrozen = false;
    private volatile long displayFrozenPosMs = 0;
    private volatile long displayStartPosMs = 0;
    private volatile long displayWallStartNs = 0;
    private volatile long lastHardResyncAtMs = 0;

    // ===== Очередь кадров для буферизации =====
    private record InitReq(int targetW, int targetH) {}
    private record FrameData(int[] abgr, int w, int h, long timestampUs) {}
    static final class PlaybackClock {
        private static final long AUDIO_STALL_THRESHOLD_US = 500_000L;

        private long lastAudioProgressUs = -1;
        private long lastAudioProgressWallUs;
        private long lastReturnedUs;
        private boolean wallFallbackLatched;

        synchronized long elapsedUs(long audioPositionUs, long wallElapsedUs) {
            if (!wallFallbackLatched && audioPositionUs >= 0) {
                if (audioPositionUs > lastAudioProgressUs) {
                    lastAudioProgressUs = audioPositionUs;
                    lastAudioProgressWallUs = wallElapsedUs;
                } else if (wallElapsedUs - lastAudioProgressWallUs >= AUDIO_STALL_THRESHOLD_US) {
                    wallFallbackLatched = true;
                }
            } else if (audioPositionUs < 0) {
                wallFallbackLatched = true;
            }

            long elapsedUs = audioPositionUs;
            if (wallFallbackLatched) {
                elapsedUs = lastAudioProgressUs < 0
                        ? wallElapsedUs
                        : lastAudioProgressUs + Math.max(0L, wallElapsedUs - lastAudioProgressWallUs);
            }
            lastReturnedUs = Math.max(lastReturnedUs, elapsedUs);
            return lastReturnedUs;
        }

        synchronized void reset() {
            lastAudioProgressUs = -1;
            lastAudioProgressWallUs = 0;
            lastReturnedUs = 0;
            wallFallbackLatched = false;
        }
    }
    // ожидаем ABGR (см. VideoPlayer), timestampUs = позиция кадра в микросекундах

    private final AtomicReference<InitReq> pendingInit = new AtomicReference<>(null);
    private final ConcurrentLinkedQueue<FrameData> frameQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger frameQueueSize = new AtomicInteger(0);
    private final AtomicBoolean pendingStop = new AtomicBoolean(false);
    private final PlaybackClock playbackClock = new PlaybackClock();
    
    // пул свободных буферов - буферы возвращаются после показа кадра
    private final ConcurrentLinkedQueue<int[]> freeBuffers = new ConcurrentLinkedQueue<>();
    private static final int BUFFER_POOL_SIZE = 60; // должен быть > MAX_BUFFER_FRAMES
    
    // буферизация: ждём пока накопится минимум кадров перед показом
    private static final int MIN_BUFFER_FRAMES = 15; // ~0.5 сек при 30fps
    private static final int MAX_BUFFER_FRAMES = 45; // ~1.5 сек максимум
    private volatile boolean buffering = true; // тру пока буферизуем
    // ====================================================================

    // Пейсинг на render thread
    private volatile long playbackStartNs = 0; // время начала воспроизведения (из декодера или локальное)

    // Состояние скачивания
    private volatile boolean downloading = false;
    private volatile int downloadPercent = 0;
    private volatile long downloadedMb = 0;
    private volatile long downloadTotalMb = 0;
    private volatile long downloadedBytes = 0;
    private volatile long downloadTotalBytes = 0;
    private volatile long downloadStartWallMs = 0;
    private volatile boolean resolvingPlatformVideo = false;
    private volatile boolean downloadingYtdlp = false;
    private volatile boolean downloadingPlatformVideo = false;
    private volatile boolean downloadProgressReceived = false;
    private volatile String downloadPhase = "";
    /**
     * Display label of the platform that the current download belongs to
     * (e.g. {@code "RuTube"}, {@code "VK"}). Empty
     * string when no platform-specific phase is active. The HUD feeds
     * this into the {@code text.minecanvas.platform.*} lang strings as the
     * first {@code %s} placeholder.
     */
    private volatile String platformLabel = "";

    public VideoScreen(ScreenState state) {
        this.state = state;
        lastSpatialAudioAvailable = SpatialAudio.isAvailable();
        ensureStandbyTexture();
    }

    public ScreenState state() { return state; }

    public void updateState(ScreenState newState) {
        ScreenState old = this.state;
        this.state = newState;

        if (old == null || newState == null) {
            ensureStandbyTexture();
            return;
        }

        String ou = old.url();
        String nu = newState.url();

        if (!Objects.equals(ou, nu)) {
            VideoPrefetcher.onScreenRemoved(old.name());
            resetForNewVideo();
            ensureStandbyTexture();
            return;
        }
        
        if (!old.playing() && newState.playing()) {
            resetForNewVideo();
            ensureStandbyTexture();
            return;
        }

        if (old.quality() != newState.quality() && newState.playing()) {
            resetForNewVideo();
            ensureStandbyTexture();
            return;
        }

        ensureStandbyTexture();

        if (!started) return;
        if (!old.playing() || !newState.playing()) return;

        long db = Math.abs(newState.basePosMs() - old.basePosMs());
        long ds = Math.abs(newState.startEpochMs() - old.startEpochMs());

        if (db > 250L || ds > 250L) {
            if (ended && endedUrl.equals(newState.url()) && !newState.loop()) {
                return;
            }
            stopPlayerAndClearDecoderState();
            ended = false;
            endedUrl = "";
            endedAtMs = 0;
            started = false;
            startedUrl = "";
        }
    }

    /** Выбрасывает кадры старой позиции, иначе до initVideo нового декодера показывается старая позиция. */
    private void dropPendingFrames() {
        frameQueue.clear();
        frameQueueSize.set(0);
        buffering = true;
        playbackStartNs = 0;
        playbackClock.reset();
    }
    
    private void resetForNewVideo() {
        stopPlayerAndClearDecoderState();
        ended = false;
        endedUrl = "";
        endedAtMs = 0;
        durationMs = 0;
        lastHardResyncAtMs = 0;
        clearCachedFileInfo();
        resetDownloadState();
        started = false;
        startedUrl = "";
        textureHasContent = false;
        textureContentUrl = "";
    }

    private void stopPlayerAndClearDecoderState() {
        if (player != null) player.stop();
        pendingInit.set(null);
        pendingStop.set(false);
        dropPendingFrames();
    }

    public boolean hasTexture() { return texture != null && texId != null; }

    public Identifier textureId() { return texId; }

    public boolean showsPauseOverlay() {
        // basePosMs > 0 separates pause from /film stop and never-played screens.
        return state.url() != null && !state.url().isEmpty() && !state.playing() && state.basePosMs() > 0 && !ended;
    }

    public void tickPlayback(Vec3 playerPos, int radiusBlocks, float globalVolume, long serverNowMs) {
        // 1) применяем всё, что пришло из декодера (ТОЛЬКО тут)
        applyPendingStop();
        applyPendingInit();

        MineCanvasClientConfig cfg = MineCanvasClientConfig.get();

        // 1.1) если в конфиге выключено — полностью останавливаем (и видео, и звук)
        if (!cfg.renderVideo) {
            stop();
            return;
        }

        // 2) управление воспроизведением
        if (state.url() == null || state.url().isEmpty() || !state.playing()) {
            stop();
            return;
        }

        // 2.1) hear radius: вне радиуса полностью отключаем (и видео, и звук)
        boolean inRadius = isInHearRadius(playerPos, radiusBlocks);
        long nowMs = System.currentTimeMillis();

        boolean justResumedFromRadiusPause = false;
        if (inRadius) {
            // Detect re-entry after a real out-of-radius gap: the HUD
            // timeline stays displayFrozen while we are away, and the
            // decoder keeps feeding stale frames into frameQueue; if we
            // simply clear pausedByRadius the render keeps showing old
            // frames and the timeline never unfreezes because
            // shouldHardResync() bails out while displayFrozen is true.
            if (pausedByRadius && outOfRadiusSinceMs > 0 && (nowMs - outOfRadiusSinceMs) > 500L) {
                justResumedFromRadiusPause = true;
            }

            lastInRadiusAtMs = nowMs;
            pausedByRadius = false;
            outOfRadiusSinceMs = 0;

            if (mutedByRadius) {
                mutedByRadius = false;
                lastGain = -1f;
            }
        } else {
            pausedByRadius = true;

            if (outOfRadiusSinceMs == 0) outOfRadiusSinceMs = nowMs;

            if (player != null) {
                if (!mutedByRadius && (nowMs - outOfRadiusSinceMs) >= RADIUS_AUDIO_HYSTERESIS_MS) {
                    player.setGain(0f);
                    mutedByRadius = true;
                }
            }

            if (started && (nowMs - lastInRadiusAtMs) <= OUT_OF_RADIUS_GRACE_MS) {
                displayFrozen = true;
                displayFrozenPosMs = clampToDuration(currentVideoPosMs(serverNowMs));
                return;
            }

            stop();
            return;
        }

        long posMs = currentVideoPosMs(serverNowMs);
        if (!state.loop() && durationMs > 0 && posMs >= durationMs) {
            ended = true;
            endedUrl = state.url();
            if (endedAtMs == 0) endedAtMs = System.currentTimeMillis();
        }
        float gain = Math.max(0f, globalVolume) * Math.max(0f, state.volume()) * cfg.localVolumeMultiplier();

        if (player == null) player = new VideoPlayer(this);

        boolean spatialAudioAvailable = SpatialAudio.isAvailable();
        if (started && !ended && spatialAudioAvailable != lastSpatialAudioAvailable) {
            lastSpatialAudioAvailable = spatialAudioAvailable;
            hardResync(posMs, gain);
            return;
        }
        lastSpatialAudioAvailable = spatialAudioAvailable;

        if (justResumedFromRadiusPause && started) {
            // Bypass the normal resync cooldown so we always re-anchor
            // playback when the player steps back into the hear radius.
            lastHardResyncAtMs = 0;
            hardResync(posMs, gain);
            return;
        }

        if (ended && endedUrl.equals(state.url())) {
            if (player != null && player.isRunning()) {
                player.stop();
            }
            if (player != null) {
                player.setGain(0f);
            }
            started = false;
            startedUrl = "";
            displayFrozen = false;
            displayFrozenPosMs = durationMs > 0 ? durationMs : clampToDuration(posMs);
            displayWallStartNs = 0;
            clearTexture();
            return;
        }

        if (shouldHardResync(serverNowMs)) {
            hardResync(posMs, gain);
            return;
        }

        if (!started || !startedUrl.equals(state.url())) {
            // Файла может ещё не быть: не долбим player.start (и обход кэша) каждый tick.
            if (lastStartAttemptMs != 0 && nowMs - lastStartAttemptMs < START_RETRY_INTERVAL_MS) return;
            startedUrl = state.url();
            ended = false;
            endedUrl = "";
            endedAtMs = 0;
            lastGain = gain;
            displayFrozen = true;
            displayFrozenPosMs = posMs;
            displayStartPosMs = posMs;
            displayWallStartNs = 0;
            boolean ok = player.start(state.url(), state.blocksW(), state.blocksH(), state.loop(), posMs, gain,
                effectiveVideoHeight(), screenCenter(), radiusBlocks);
            lastStartAttemptMs = ok ? 0 : nowMs;
            started = ok;
            return;
        }

        if (Math.abs(gain - lastGain) > 0.001f) {
            lastGain = gain;
            player.setGain(gain);
        }
    }

    public void renderPlayback() {
        if (!started) return;
        if (!MineCanvasClientConfig.get().renderVideo) return;
        if (pausedByRadius) return;
        uploadPendingFrameFast();
    }

    private boolean isInHearRadius(Vec3 playerPos, int radiusBlocks) {
        if (playerPos == null) return false;
        if (radiusBlocks <= 0) return true;

        double cx = (state.minX() + state.maxX() + 1) * 0.5;
        double cy = (state.minY() + state.maxY() + 1) * 0.5;
        double cz = (state.minZ() + state.maxZ() + 1) * 0.5;

        double dx = playerPos.x - cx;
        double dy = playerPos.y - cy;
        double dz = playerPos.z - cz;

        double r = (double) radiusBlocks;
        return (dx * dx + dy * dy + dz * dz) <= (r * r);
    }

    private Vec3 screenCenter() {
        return new Vec3(
            (state.minX() + state.maxX() + 1) * 0.5,
            (state.minY() + state.maxY() + 1) * 0.5,
            (state.minZ() + state.maxZ() + 1) * 0.5
        );
    }

    private void applyPendingStop() {
        if (!pendingStop.getAndSet(false)) return;

        boolean preserveEnded = ended && endedUrl.equals(state.url()) && !state.loop();

        started = false;
        startedUrl = "";
        lastGain = -1f;
        lastHardResyncAtMs = 0;

        frameQueue.clear();
        frameQueueSize.set(0);
        buffering = true;
        playbackStartNs = 0;
        resetDownloadState();

        if (!preserveEnded) {
            displayFrozen = false;
            displayFrozenPosMs = 0;
            displayStartPosMs = 0;
            displayWallStartNs = 0;

            ended = false;
            endedUrl = "";
            endedAtMs = 0;
        }

        lastInRadiusAtMs = 0;
        pausedByRadius = false;
        mutedByRadius = false;
        outOfRadiusSinceMs = 0;
    }

    private void applyPendingInit() {
        InitReq req = pendingInit.getAndSet(null);
        if (req == null) return;

        ensureTexture(req.targetW(), req.targetH());

        // очередь кадров и сбрасываем пейсинг
        dropPendingFrames();

        // пул буферов
        freeBuffers.clear();
        int pixels = texW * texH;
        for (int i = 0; i < BUFFER_POOL_SIZE; i++) {
            freeBuffers.offer(new int[pixels]);
        }
    }

    /**
     * Берём кадр из очереди с пейсингом по fps видео.
     * Буферизация: ждём пока накопится минимум кадров перед показом.
     */
    private void uploadPendingFrameFast() {
        if (texture == null) return;

        int queueSize = frameQueueSize.get();

        // Буферизация:
        if (buffering) {
            if (queueSize < MIN_BUFFER_FRAMES) {
                return; // ещё буферизуем
            }
            buffering = false;
            if (playbackStartNs == 0) playbackStartNs = System.nanoTime();
            if (displayWallStartNs == 0) {
                displayWallStartNs = playbackStartNs;
                displayStartPosMs = displayFrozenPosMs;
                displayFrozen = false;
            }
        }

        if (playbackStartNs == 0) playbackStartNs = System.nanoTime();

        long now = System.nanoTime();
        long wallElapsedUs = (now - playbackStartNs) / 1000L;
        long elapsedUs = playbackClock.elapsedUs(player == null ? -1 : player.playbackPositionUs(), wallElapsedUs);

        FrameData frame = frameQueue.peek();
        if (frame == null) return;

        FrameData chosen = null;
        while (true) {
            FrameData next = frameQueue.peek();
            if (next == null) break;
            if (next.timestampUs() > elapsedUs) break;

            chosen = frameQueue.poll();
            if (chosen == null) break;
            frameQueueSize.decrementAndGet();

            FrameData peekAfter = frameQueue.peek();
            if (peekAfter != null && peekAfter.timestampUs() <= elapsedUs) {
                freeBuffers.offer(chosen.abgr());
                chosen = null;
            }
        }

        if (chosen == null) return;
        frame = chosen;

        int w = frame.w();
        int h = frame.h();
        int[] abgr = frame.abgr();
        
        if (w != texW || h != texH) {
            // Размер не совпадает - возвращаем буфер в пул и пропускаем
            freeBuffers.offer(abgr);
            return;
        }

        IntBuffer dst = nativeDst;
        if (dst == null) {
            freeBuffers.offer(abgr);
            return;
        }
        int pixels = texW * texH;

        dst.position(0);
        dst.put(abgr, 0, pixels);

        texture.upload();
        textureContentUrl = state.url();
        textureHasContent = true;

        // ВАЖНО: возвращаем буфер в пул после использования
        freeBuffers.offer(abgr);
    }

    private long currentVideoPosMs(long serverNowMs) {
        long base = Math.max(0L, state.basePosMs());
        if (serverNowMs <= 0 || state.startEpochMs() <= 0) return base;
        long pos = base + Math.max(0L, serverNowMs - state.startEpochMs());
        return clampToDuration(pos);
    }

    public long currentPosMsForDisplay(long serverNowMs) {
        // Во время скачивания показываем серверное время (таймлайн продолжает идти)
        if (downloading) {
            return currentVideoPosMs(serverNowMs);
        }
        if (started && !ended && displayWallStartNs <= 0) {
            return currentVideoPosMs(serverNowMs);
        }
        if (displayFrozen) {
            return clampToDuration(Math.max(0L, displayFrozenPosMs));
        }
        long ws = displayWallStartNs;
        if (ws > 0) {
            long wallElapsedUs = Math.max(0L, (System.nanoTime() - ws) / 1_000L);
            long elapsedMs = playbackClock.elapsedUs(player == null ? -1 : player.playbackPositionUs(), wallElapsedUs) / 1_000L;
            return clampToDuration(Math.max(0L, displayStartPosMs + elapsedMs));
        }
        return currentVideoPosMs(serverNowMs);
    }

    private boolean shouldHardResync(long serverNowMs) {
        if (!started || ended || downloading || displayFrozen) return false;
        if (player == null || !player.isRunning()) return false;
        if (displayWallStartNs <= 0 || serverNowMs <= 0 || state.startEpochMs() <= 0) return false;

        long now = System.currentTimeMillis();
        if (now - lastHardResyncAtMs < DRIFT_RESYNC_COOLDOWN_MS) return false;

        long serverPosMs = currentVideoPosMs(serverNowMs);
        long localPosMs = currentPosMsForDisplay(serverNowMs);
        long driftMs = Math.abs(serverPosMs - localPosMs);
        return driftMs >= DRIFT_RESYNC_THRESHOLD_MS;
    }

    private void hardResync(long posMs, float gain) {
        lastHardResyncAtMs = System.currentTimeMillis();
        stopPlayerAndClearDecoderState();
        started = false;
        startedUrl = "";
        ended = false;
        endedUrl = "";
        endedAtMs = 0;
        displayFrozen = true;
        displayFrozenPosMs = clampToDuration(posMs);
        displayStartPosMs = displayFrozenPosMs;
        displayWallStartNs = 0;
        lastGain = gain;
    }

    private int effectiveVideoHeight() {
        return VideoQuality.resolveEffectiveHeight(state.blocksH(), state.quality());
    }

    public long durationMs() {
        return durationMs;
    }

    public void stop() {
        if (player != null) player.stop();

        started = false;
        startedUrl = "";
        lastGain = -1f;

        frameQueue.clear();
        frameQueueSize.set(0);
        buffering = true;

        playbackStartNs = 0;
        playbackClock.reset();

        displayFrozen = false;
        displayFrozenPosMs = 0;
        displayStartPosMs = 0;
        displayWallStartNs = 0;
        resetDownloadState();

        mutedByRadius = false;
        outOfRadiusSinceMs = 0;

    }

    private void ensureStandbyTexture() {
        if (state == null || state.url() == null || state.url().isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(this::ensureStandbyTexture);
            return;
        }
        // Standby must never resize texture owned by playback.
        if (started) return;
        if (texture != null && textureHasContent && Objects.equals(textureContentUrl, state.url())) return;
        VideoSizeUtil.Size size = VideoSizeUtil.pick(state.blocksW(), state.blocksH(), state.blocksW(), state.blocksH());
        ensureTexture(size.w(), size.h());
    }

    private void ensureTexture(int width, int height) {
        boolean changed = texture == null || texW != width || texH != height
            || !textureHasContent || !Objects.equals(textureContentUrl, state.url());
        if (texId == null) {
            texId = Identifier.fromNamespaceAndPath("mine-canvas", "screen/" + state.name().toLowerCase());
        }
        if (changed && texture != null) {
            texture.close();
            texture = null;
        }
        texW = width;
        texH = height;
        if (texture == null) {
            texture = new DynamicTexture("minecanvas:" + texId, texW, texH, true);
            Minecraft.getInstance().getTextureManager().register(texId, texture);
        }

        NativeImage image = texture.getPixels();
        if (image != null) {
            nativePtr = ((NativeImageAccessor) (Object) image).minecanvas$getPointer();
            nativeDst = MemoryUtil.memIntBuffer(nativePtr, texW * texH);
            if (changed) {
                image.fillRect(0, 0, texW, texH, STANDBY_ABGR);
                texture.upload();
                textureContentUrl = state.url();
                textureHasContent = true;
            }
        } else {
            nativePtr = 0;
            nativeDst = null;
        }
    }
    
    public void clearTexture() {
        if (texture != null) {
            try {
                texture.close();
            } catch (Exception ignored) {}
            texture = null;
        }
        texId = null;
        nativePtr = 0;
        nativeDst = null;
        textureHasContent = false;
        textureContentUrl = "";
    }

    // ===== FrameSink: эти методы могут вызываться РЗ ДЕКОДЕР-ПОТОКА =====

    @Override
    public void initVideo(int videoW, int videoH, int targetW, int targetH, double fps) {
        pendingInit.set(new InitReq(targetW, targetH));
    }

    @Override
    public void onDuration(long durationMs) {
        long d = Math.max(0L, durationMs);
        // защита от "мусорной" длительности (иногда FFmpeg отдаёт абсурдные значения)
        long max = 12L * 60L * 60L * 1000L;
        if (d > max) d = 0L;
        this.durationMs = d;
    }

    @Override
    public void onEnded(long durationMs) {
        long d = durationMs > 0 ? durationMs : this.durationMs;
        if (d > 0) this.durationMs = d;

        this.ended = true;
        this.endedUrl = startedUrl;
        this.endedAtMs = System.currentTimeMillis();

        this.displayFrozen = true;
        this.displayFrozenPosMs = this.durationMs;
        this.displayWallStartNs = 0;
        resetDownloadState();

        // Сервер сам определяет окончание видео по времени (безопаснее чем клиентское сообщение)
    }

    @Override
    public void onFrame(int[] abgr, int w, int h, long timestampUs) {
        if (abgr == null) return;

        if (!MineCanvasClientConfig.get().renderVideo) {
            freeBuffers.offer(abgr);
            return;
        }
        
        // Ограничиваем размер очереди чтобы не съесть всю память
        if (frameQueueSize.get() >= MAX_BUFFER_FRAMES) {
            // Очередь полна - декодер должен ждать
            freeBuffers.offer(abgr);
            return;
        }
        
        frameQueue.offer(new FrameData(abgr, w, h, timestampUs));
        frameQueueSize.incrementAndGet();
    }

    @Override
    public void onStop() {
        pendingStop.set(true);
    }

    private long clampToDuration(long posMs) {
        long d = durationMs;
        if (d > 0) {
            return Math.min(Math.max(0L, posMs), d);
        }
        return Math.max(0L, posMs);
    }

    @Override
    public void onPlaybackClockStart(long wallStartNs) {
        playbackClock.reset();
        this.playbackStartNs = wallStartNs;
        this.displayWallStartNs = wallStartNs;
        this.displayFrozen = false;

        // Если было скачивание, учитываем время которое прошло
        if (downloadStartWallMs > 0) {
            long downloadDurationMs = System.currentTimeMillis() - downloadStartWallMs;
            this.displayStartPosMs = this.displayFrozenPosMs + downloadDurationMs;
            downloadStartWallMs = 0;
        } else {
            this.displayStartPosMs = this.displayFrozenPosMs;
        }

        resetDownloadState();
    }

    @Override
    public boolean canAcceptFrame() {
        return frameQueueSize.get() < MAX_BUFFER_FRAMES;
    }

    @Override
    public int[] borrowBuffer() {
        return freeBuffers.poll();
    }

    @Override
    public void returnBuffer(int[] buf) {
        if (buf != null) {
            freeBuffers.offer(buf);
        }
    }

    @Override
    public void onDownloadStart(String message) {
        String phase = normalizeDownloadPhase(message);
        boolean samePhase = downloading && phase.equals(downloadPhase);

        this.downloading = true;
        if (!samePhase) {
            this.downloadPercent = 0;
            this.downloadedMb = 0;
            this.downloadTotalMb = 0;
            this.downloadedBytes = 0;
            this.downloadTotalBytes = 0;
            this.downloadProgressReceived = false;

            // Запоминаем время начала скачивания для корректной синхронизации таймлайна
            this.downloadStartWallMs = System.currentTimeMillis();
        }
        
        // Track active platform phase for HUD label. Message keys follow
        // "minecanvas.platform.<platform>_<phase>".
        if (message != null) {
            this.platformLabel = extractPlatformLabel(message);
            boolean hasPlatform = !this.platformLabel.isEmpty();
            this.resolvingPlatformVideo = hasPlatform;
            this.downloadingYtdlp = message.contains("ytdlp");
            this.downloadingPlatformVideo = hasPlatform
                && (message.contains("_downloading"));
        } else {
            this.platformLabel = "";
            this.resolvingPlatformVideo = false;
            this.downloadingYtdlp = false;
            this.downloadingPlatformVideo = false;
        }
        this.downloadPhase = phase;
    }

    /**
     * Returns the display label for the platform encoded in the given
     * download message, or an empty string for messages that don't name
     * a platform (generic downloads, yt-dlp installer, etc.).
     */
    private static String extractPlatformLabel(String message) {
        if (message == null) return "";
        if (message.contains("rutube")) return "RuTube";
        if (message.contains("vk_")) return "VK";
        if (message.contains("video_")) return "Video";
        return "";
    }

    @Override
    public void onDownloadProgress(int percent, long downloadedMb, long totalMb) {
        this.downloadPercent = Math.max(this.downloadPercent, Math.max(0, percent));
        this.downloadedMb = Math.max(this.downloadedMb, Math.max(0L, downloadedMb));
        this.downloadTotalMb = Math.max(this.downloadTotalMb, Math.max(0L, totalMb));
        this.downloadProgressReceived = true;
    }

    @Override
    public void onDownloadProgressBytes(int percent, long downloadedBytes, long totalBytes) {
        this.downloadPercent = Math.max(this.downloadPercent, Math.max(0, percent));
        this.downloadedBytes = Math.max(this.downloadedBytes, Math.max(0L, downloadedBytes));
        this.downloadTotalBytes = Math.max(this.downloadTotalBytes, Math.max(0L, totalBytes));
        this.downloadedMb = Math.max(this.downloadedMb, Math.round(this.downloadedBytes / 1048576.0));
        this.downloadTotalMb = Math.max(this.downloadTotalMb, Math.round(this.downloadTotalBytes / 1048576.0));
        this.downloadProgressReceived = true;
    }

    // Геттеры для состояния скачивания (для отображения в HUD)
    public boolean isDownloading() { return downloading; }
    public int getDownloadPercent() { return downloadPercent; }
    public long getDownloadedMb() { return downloadedMb; }
    public long getDownloadTotalMb() { return downloadTotalMb; }
    public long getDownloadedBytes() { return downloadedBytes; }
    public long getDownloadTotalBytes() { return downloadTotalBytes; }
    public boolean isResolvingPlatformVideo() { return resolvingPlatformVideo; }
    public boolean isDownloadingYtdlp() { return downloadingYtdlp; }
    public boolean isDownloadingPlatformVideo() { return downloadingPlatformVideo; }
    public boolean hasDownloadProgressReceived() { return downloadProgressReceived; }
    /** @return display label of active platform download, or empty string. */
    public String getPlatformLabel() { return platformLabel; }

    // Рнформация о кэшированном файле (для предложения удаления)
    private volatile String cachedFilePath = null;
    private volatile long cachedFileSizeBytes = 0;

    @Override
    public void onCachedFileUsed(String cachedFilePath, long fileSizeBytes) {
        this.cachedFilePath = cachedFilePath;
        this.cachedFileSizeBytes = fileSizeBytes;
    }

    public long getCachedFileSizeMb() { return cachedFileSizeBytes / (1024L * 1024L); }
    public boolean hasCachedFile() { return cachedFilePath != null && !cachedFilePath.isEmpty(); }

    private void clearCachedFileInfo() {
        this.cachedFilePath = null;
        this.cachedFileSizeBytes = 0;
    }

    private void resetDownloadState() {
        this.downloading = false;
        this.downloadPercent = 0;
        this.downloadedMb = 0;
        this.downloadTotalMb = 0;
        this.downloadedBytes = 0;
        this.downloadTotalBytes = 0;
        this.downloadStartWallMs = 0;
        this.resolvingPlatformVideo = false;
        this.downloadingYtdlp = false;
        this.downloadingPlatformVideo = false;
        this.downloadProgressReceived = false;
        this.downloadPhase = "";
        this.platformLabel = "";
    }

    private static String normalizeDownloadPhase(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        // Distinct phases per platform so switching between platform prepares
        // does not falsely count as the "same phase"
        // (which would suppress the progress reset in onDownloadStart).
        if (message.contains("_downloading")) {
            if (message.contains("rutube")) return "rutube_download";
            if (message.contains("vk_")) return "vk_download";
            if (message.contains("video_")) return "video_download";
            return "generic_download";
        }
        if (message.contains("ytdlp")) {
            return "ytdlp";
        }
        if (message.contains("rutube")) return "rutube_prepare";
        if (message.contains("vk_")) return "vk_prepare";
        return "generic";
    }

    // Геттер для проверки окончания видео (показывать "Сеанс окончен" в течение 5 секунд)
    private static final long ENDED_DISPLAY_DURATION_MS = 5000L;

    public boolean isEnded() {
        if (!ended) return false;
        // Показываем "Сеанс окончен" только 5 секунд
        if (endedAtMs > 0 && System.currentTimeMillis() - endedAtMs > ENDED_DISPLAY_DURATION_MS) {
            return false;
        }
        return true;
    }

    // Возвращает true если видео закончилось (без ограничения по времени)
    public boolean hasEnded() { return ended; }
}
