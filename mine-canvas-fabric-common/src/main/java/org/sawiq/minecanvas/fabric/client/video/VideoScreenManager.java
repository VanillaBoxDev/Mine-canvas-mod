package org.sawiq.minecanvas.fabric.client.video;

import org.sawiq.minecanvas.fabric.client.config.MineCanvasClientConfig;
import org.sawiq.minecanvas.fabric.client.net.MineCanvasNet;
import org.sawiq.minecanvas.fabric.client.state.ScreenState;
import org.sawiq.minecanvas.fabric.client.util.TimeFormatUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

public final class VideoScreenManager {

    private static final boolean DEBUG = false;

    private VideoScreenManager() {}

    private static final Map<String, VideoScreen> SCREENS = new ConcurrentHashMap<>();
    private static final Set<String> SHOWN_DELETE_PROMPT = ConcurrentHashMap.newKeySet();

    private static final int GREEN = 0x00FF00;
    private static final int GRAY = 0xAAAAAA;
    private static final int YELLOW = 0xFFFF55;
    private static final class Components {
        private static final Component PREFIX = Component.translatable("text.minecanvas.prefix").setStyle(Style.EMPTY.withColor(GREEN));
    }

    private static volatile long lastActionbarUpdateMs = 0;
    private static volatile String lastClientWorldKey = "";

    static String currentWorldKey(Minecraft client) {
        if (client == null) return "";
        try {
            if (client.level != null && client.level.dimension() != null) {
                String k = client.level.dimension().identifier().toString();
                return (k == null) ? "" : k;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String defaultBukkitWorldNameForDim(String dimKey) {
        if (dimKey == null || dimKey.isBlank()) return null;
        String k = dimKey.toLowerCase(Locale.ROOT);
        if (k.equals("minecraft:overworld")) return "world";
        if (k.equals("minecraft:the_nether")) return "world_nether";
        if (k.equals("minecraft:the_end")) return "world_the_end";
        return null;
    }

    private static boolean isDefaultBukkitWorldName(String w) {
        if (w == null) return false;
        String s = w.toLowerCase(Locale.ROOT);
        return s.equals("world") || s.equals("world_nether") || s.equals("world_the_end");
    }

    static boolean isCompatibleWithCurrentWorld(ScreenState st, Minecraft client) {
        if (st == null || client == null) return true;
        String sw = st.world();
        if (sw == null || sw.isBlank()) return true;

        String dimKey = currentWorldKey(client);
        if (sw.regionMatches(true, 0, "minecraft:", 0, "minecraft:".length())) {
            return sw.equalsIgnoreCase(dimKey);
        }

        if (isDefaultBukkitWorldName(sw)) {
            String expected = defaultBukkitWorldNameForDim(dimKey);
            return expected != null && sw.equalsIgnoreCase(expected);
        }

        return true;
    }

    public static Collection<VideoScreen> all() {
        return SCREENS.values();
    }

    public static VideoScreen getByName(String name) {
        if (name == null) return null;
        return SCREENS.get(name.toLowerCase(Locale.ROOT));
    }

    private static VideoScreen findNearestPlaying(Vec3 playerPos) {
        if (playerPos == null) return null;

        Minecraft client = Minecraft.getInstance();
        VideoScreen best = null;
        double bestDist2 = Double.MAX_VALUE;

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st == null) continue;
            if (!isCompatibleWithCurrentWorld(st, client)) continue;
            if (!st.playing()) continue;
            if (st.url() == null || st.url().isEmpty()) continue;

            double cx = (st.minX() + st.maxX() + 1) * 0.5;
            double cy = (st.minY() + st.maxY() + 1) * 0.5;
            double cz = (st.minZ() + st.maxZ() + 1) * 0.5;

            double dx = playerPos.x - cx;
            double dy = playerPos.y - cy;
            double dz = playerPos.z - cz;

            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = s;
            }
        }

        return best;
    }

    private static VideoScreen findNearestPlayingInRadius(Vec3 playerPos, int radiusBlocks) {
        if (playerPos == null) return null;
        if (radiusBlocks <= 0) return findNearestPlaying(playerPos);

        Minecraft client = Minecraft.getInstance();
        VideoScreen best = null;
        double bestDist2 = Double.MAX_VALUE;
        double r = (double) radiusBlocks;
        double r2 = r * r;

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st == null) continue;
            if (!isCompatibleWithCurrentWorld(st, client)) continue;
            if (!st.playing() && !s.isEnded()) continue;
            if (st.url() == null || st.url().isEmpty()) continue;

            double cx = (st.minX() + st.maxX() + 1) * 0.5;
            double cy = (st.minY() + st.maxY() + 1) * 0.5;
            double cz = (st.minZ() + st.maxZ() + 1) * 0.5;

            double dx = playerPos.x - cx;
            double dy = playerPos.y - cy;
            double dz = playerPos.z - cz;

            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r2) continue;

            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = s;
            }
        }

        return best;
    }

    public static void applySync(Map<String, ScreenState> incoming) {
        Set<String> keep = new HashSet<>(incoming.keySet());

        for (String key : new ArrayList<>(SCREENS.keySet())) {
            if (!keep.contains(key)) {
                VideoScreen vs = SCREENS.remove(key);
                if (vs != null) {
                    VideoPrefetcher.onScreenRemoved(vs.state() != null ? vs.state().name() : key);
                    if (DEBUG) System.out.println("[MineCanvas] STOP by remove: key=" + key);
                    vs.stop();
                    vs.clearTexture();
                    // Drop any "delete cached file?" prompts that were
                    // associated with this screen, otherwise SHOWN_DELETE_PROMPT
                    // accumulates {name + "_" + url} forever - one entry per
                    // distinct URL ever played on the screen.
                    String namePrefix = vs.state() != null ? vs.state().name() + "_" : null;
                    if (namePrefix != null) {
                        SHOWN_DELETE_PROMPT.removeIf(k -> k.startsWith(namePrefix));
                    }
                }
            }
        }

        for (var e : incoming.entrySet()) {
            String key = e.getKey();
            ScreenState st = e.getValue();

            VideoScreen vs = SCREENS.get(key);
            if (vs == null) {
                vs = new VideoScreen(st);
                SCREENS.put(key, vs);
                if (DEBUG) System.out.println("[MineCanvas] screen created: key=" + key + " name=" + st.name());
            } else {
                vs.updateState(st);
            }

            if (!st.playing() || st.url() == null || st.url().isEmpty()) {
                if (DEBUG) {
                    System.out.println("[MineCanvas] STOP by sync: name=" + st.name() + " playing=" + st.playing() + " url=" + st.url());
                }
                vs.stop();
                if (st.url() == null || st.url().isEmpty()) {
                    vs.clearTexture();
                }
            }
        }
    }

    private static MutableComponent buildActionBarText(VideoScreen nearest, long serverNowMs) {
        VideoPrefetcher.Status prefetch = VideoPrefetcher.status(nearest.state().name());
        if (prefetch.state() == VideoPrefetcher.State.DOWNLOADING) return downloadHud(nearest.state().name(), prefetch);
        long posMs = nearest.currentPosMsForDisplay(serverNowMs);
        long durMs = nearest.durationMs();
        if (durMs > 0) {
            if (prefetch.totalBytes() > 0) {
                int coverage = (int) Math.min(100L, prefetch.downloadedBytes() * 100L / prefetch.totalBytes());
                return Component.translatable("text.minecanvas.timeline.coverage", nearest.state().name(),
                    TimeFormatUtil.formatMs(posMs), TimeFormatUtil.formatMs(durMs), coverage)
                    .setStyle(Style.EMPTY.withColor(GREEN));
            }
            return Component.translatable("text.minecanvas.timeline.full", nearest.state().name(), TimeFormatUtil.formatMs(posMs), TimeFormatUtil.formatMs(durMs))
                .setStyle(Style.EMPTY.withColor(GREEN));
        }
        return Component.translatable("text.minecanvas.timeline.single", nearest.state().name(), TimeFormatUtil.formatMs(posMs))
            .setStyle(Style.EMPTY.withColor(GREEN));
    }

    private static void sendEndedPrompt(LocalPlayer player, VideoScreen nearest) {
        String screenKey = nearest.state().name() + "_" + nearest.state().url();
        if (!nearest.hasCachedFile() || SHOWN_DELETE_PROMPT.contains(screenKey)) return;

        SHOWN_DELETE_PROMPT.add(screenKey);
        long sizeMb = nearest.getCachedFileSizeMb();

        VideoPlatformBridge.systemMessage(player, Components.PREFIX.copy()
            .append(Component.translatable("text.minecanvas.video.session_finished_cache", sizeMb).setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Component.literal("\n"))
            .append(Component.translatable("text.minecanvas.video.cache_config_hint").setStyle(Style.EMPTY.withColor(YELLOW))));
    }

    public static void tick(Minecraft client) {
        LocalPlayer p = client.player;

        // Independent watchdog. We cannot rely solely on
        // ClientPlayConnectionEvents.DISCONNECT because some mods
        // (notably Replay Mod, which records server packets and intercepts
        // network teardown) delay or swallow that event entirely - the user
        // reported audio bleeding through for the full duration of the
        // recording finalization. Whenever the client is no longer in any
        // world (title screen, disconnect, server-switch transition,
        // singleplayer save-and-quit), there is no legitimate reason for
        // any MineCanvas screen to keep playing, so we drop everything.
        if (p == null || client.level == null) {
            if (!SCREENS.isEmpty()) {
                stopAll();
                lastClientWorldKey = "";
            } else {
                // Even with no screens we may still have a stale audio
                // line from a player that was just removed from SCREENS
                // by a previous tick or disconnect hook.
                VideoAudioPlayer.shutdownAll();
            }
            return;
        }

        String worldKey = currentWorldKey(client);
        if (!worldKey.equals(lastClientWorldKey)) {
            lastClientWorldKey = worldKey;
            stopAllPlayback();
        }

        Vec3 pos = p.position();
        int radius = MineCanvasNet.HEAR_RADIUS;
        float globalVolume = MineCanvasNet.GLOBAL_VOLUME;
        long serverNowMs = estimateServerNowMs();

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st != null && !isCompatibleWithCurrentWorld(st, client)) {
                if (st.playing()) s.stop();
                continue;
            }
            s.tickPlayback(pos, radius, globalVolume, serverNowMs);
        }

        VideoPrefetcher.tick(client, pos);

        MineCanvasClientConfig cfg = MineCanvasClientConfig.get();
        if (cfg.renderVideo && cfg.actionbarTimeline && !VideoPlatformBridge.chatScreenOpen(client)) {
            long now = System.currentTimeMillis();
            if (now - lastActionbarUpdateMs >= 500L) {
                lastActionbarUpdateMs = now;

                VideoScreen nearest = findNearestPlayingInRadius(pos, radius);
                if (nearest != null) {
                    if (nearest.isEnded()) {
                        sendEndedPrompt(p, nearest);
                        VideoPlatformBridge.overlayMessage(p, Component.literal(""));
                    } else if (nearest.hasEnded()) {
                        VideoPlatformBridge.overlayMessage(p, Component.literal(""));
                    } else {
                        VideoPlatformBridge.overlayMessage(p, Components.PREFIX.copy().append(buildActionBarText(nearest, serverNowMs)));
                    }
                } else {
                    VideoScreen idle = findNearestPrefetchStatusInRadius(pos, VideoPrefetcher.PREFETCH_RADIUS_BLOCKS);
                    VideoPrefetcher.Status prefetch = idle == null ? null : VideoPrefetcher.status(idle.state().name());
                    if (prefetch != null && prefetch.state() == VideoPrefetcher.State.DOWNLOADING) {
                        VideoPlatformBridge.overlayMessage(p, Components.PREFIX.copy().append(downloadHud(idle.state().name(), prefetch)));
                    } else if (prefetch != null && prefetch.state() == VideoPrefetcher.State.READY) {
                        VideoPlatformBridge.overlayMessage(p, Components.PREFIX.copy().append(Component.translatable("text.minecanvas.prefetch.ready",
                            idle.state().name()).setStyle(Style.EMPTY.withColor(GREEN))));
                    }
                }
            }
        }
    }

    private static VideoScreen findNearestPrefetchStatusInRadius(Vec3 playerPos, int radiusBlocks) {
        if (playerPos == null) return null;
        double radiusSquared = (double) radiusBlocks * radiusBlocks;
        VideoScreen best = null;
        double bestDist2 = Double.MAX_VALUE;
        Minecraft client = Minecraft.getInstance();
        for (VideoScreen screen : SCREENS.values()) {
            ScreenState state = screen.state();
            if (state == null || state.playing() || state.url() == null || state.url().isBlank()
                || !isCompatibleWithCurrentWorld(state, client)) continue;
            VideoPrefetcher.Status prefetch = VideoPrefetcher.status(state.name());
            if (prefetch.state() != VideoPrefetcher.State.DOWNLOADING
                && prefetch.state() != VideoPrefetcher.State.READY) continue;
            double cx = (state.minX() + state.maxX() + 1) * 0.5;
            double cy = (state.minY() + state.maxY() + 1) * 0.5;
            double cz = (state.minZ() + state.maxZ() + 1) * 0.5;
            double dx = playerPos.x - cx, dy = playerPos.y - cy, dz = playerPos.z - cz;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared <= radiusSquared && distanceSquared < bestDist2) {
                bestDist2 = distanceSquared;
                best = screen;
            }
        }
        return best;
    }

    private static MutableComponent downloadHud(String name, VideoPrefetcher.Status status) {
        String downloaded = formatDownloadSize(status.downloadedBytes(), status.downloadedMb());
        MutableComponent text = status.totalBytes() > 0 || status.totalMb() > 0
            ? Component.translatable("text.minecanvas.prefetch.progress_size", name, status.percent(), downloaded,
                formatDownloadSize(status.totalBytes(), status.totalMb()))
            : Component.translatable("text.minecanvas.prefetch.size", name, downloaded);
        return text.append(Component.literal(" • " + formatSpeed(status.bytesPerSecond()))).setStyle(Style.EMPTY.withColor(YELLOW));
    }

    static String formatDownloadSize(long bytes, long megabytes) {
        if (bytes == 0) return megabytes > 0 ? megabytes + " MB" : "0 B";
        if (bytes > 0 && bytes < 1048576L) return Math.max(1L, Math.round(bytes / 1024.0)) + " KB";
        if (bytes > 0 && bytes < 10L * 1048576L) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        if (bytes > 0) return Math.round(bytes / 1048576.0) + " MB";
        return megabytes + " MB";
    }

    static String formatSpeed(long bytesPerSecond) {
        return formatDownloadSize(Math.max(0L, bytesPerSecond), 0) + "/s";
    }

    public static long estimateServerNowMs() {
        long sn = MineCanvasNet.SERVER_NOW_MS;
        long cr = MineCanvasNet.CLIENT_RECV_MS;
        if (sn <= 0 || cr <= 0) return 0;
        return sn + (System.currentTimeMillis() - cr);
    }

    public static void stopAll() {
        if (DEBUG) System.out.println("[MineCanvas] stopAll()");
        // Belt-and-suspenders: globally close every audio line that any
        // VideoPlayer ever opened BEFORE we walk the SCREENS map. Per-screen
        // stop() also tries to silence audio, but it routes through that
        // screen's currentAudio reference, which has a narrow race window
        // around playOnce()'s try-with-resources where currentAudio can be
        // either null or already-discarded. Closing all lines up-front
        // makes the user-perceived silence instant and unconditional.
        VideoAudioPlayer.shutdownAll();
        VideoPrefetcher.cancelAll();
        for (VideoScreen s : SCREENS.values()) {
            s.stop();
            s.clearTexture();
        }
        SCREENS.clear();
    }

    public static void stopAllPlayback() {
        if (DEBUG) System.out.println("[MineCanvas] stopAllPlayback()");
        VideoAudioPlayer.shutdownAll();
        for (VideoScreen s : SCREENS.values()) s.stop();
    }

    public static void clearDeletePromptHistory() {
        SHOWN_DELETE_PROMPT.clear();
    }
}
