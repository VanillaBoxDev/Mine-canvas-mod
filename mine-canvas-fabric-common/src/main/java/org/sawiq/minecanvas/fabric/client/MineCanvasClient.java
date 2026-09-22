package org.sawiq.minecanvas.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.sawiq.minecanvas.fabric.client.command.MineCanvasClientCommands;
import org.sawiq.minecanvas.fabric.client.config.MineCanvasClientConfig;
import org.sawiq.minecanvas.fabric.client.net.MineCanvasNet;
import org.sawiq.minecanvas.fabric.client.video.VideoScreenManager;
import org.sawiq.minecanvas.fabric.client.video.VideoScreenRenderer;
import org.sawiq.minecanvas.fabric.client.video.PlatformResolver;

public final class MineCanvasClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        LegacyMigration.run();
        MineCanvasClientConfig.get();
        MineCanvasNet.initClientReceiver();
        VideoScreenRenderer.init();
        MineCanvasClientCommands.init();

        // Prefetch yt-dlp + ffmpeg in the background so first platform URL
        // does not stall the game while binaries are downloading.
        if (!PlatformResolver.isYtdlpAvailable() && !PlatformResolver.isDownloading()) {
            PlatformResolver.downloadYtdlpAsync();
        }
        PlatformResolver.downloadFfmpegAsync();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MineCanvasClientCommands.tick(client);
            VideoScreenManager.tick(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            VideoScreenManager.stopAll();
        });

        // Belt-and-suspenders: also wipe state on JOIN. Velocity/BungeeCord
        // proxy server switches sometimes don't fire DISCONNECT cleanly,
        // and the new server's plugin sends a fresh SYNC anyway, so it is
        // safe (and necessary) to drop any leftover screens/audio from the
        // previous connection here.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            VideoScreenManager.stopAll();
        });
    }
}
