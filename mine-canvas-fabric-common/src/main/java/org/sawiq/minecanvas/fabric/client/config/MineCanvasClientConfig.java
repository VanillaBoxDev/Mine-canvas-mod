package org.sawiq.minecanvas.fabric.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MineCanvasClientConfig {

    public int localVolumePercent = 100;
    public boolean renderVideo = true;
    public boolean actionbarTimeline = true;
    public int maxCacheGiB = 16;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "mine-canvas.json";

    private static volatile MineCanvasClientConfig INSTANCE;

    private MineCanvasClientConfig() {
    }

    public static MineCanvasClientConfig get() {
        MineCanvasClientConfig cfg = INSTANCE;
        if (cfg == null) {
            cfg = load();
            INSTANCE = cfg;
        }
        return cfg;
    }

    public static MineCanvasClientConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            if (Files.exists(path)) {
                try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    MineCanvasClientConfig cfg = GSON.fromJson(r, MineCanvasClientConfig.class);
                    if (cfg != null) {
                        sanitize(cfg);
                        return cfg;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        MineCanvasClientConfig cfg = new MineCanvasClientConfig();
        sanitize(cfg);
        save(cfg);
        return cfg;
    }

    public static void save(MineCanvasClientConfig cfg) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(cfg, w);
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        MineCanvasClientConfig cfg = get();
        sanitize(cfg);
        save(cfg);
    }

    public static void reload() {
        INSTANCE = load();
    }

    public float localVolumeMultiplier() {
        return Math.max(0f, Math.min(1f, localVolumePercent / 100f));
    }

    private static void sanitize(MineCanvasClientConfig cfg) {
        if (cfg.localVolumePercent < 0) cfg.localVolumePercent = 0;
        if (cfg.localVolumePercent > 100) cfg.localVolumePercent = 100;
        cfg.maxCacheGiB = Math.max(1, Math.min(64, cfg.maxCacheGiB));
    }
}
