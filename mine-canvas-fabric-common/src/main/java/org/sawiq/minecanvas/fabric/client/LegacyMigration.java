package org.sawiq.minecanvas.fabric.client;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class LegacyMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineCanvas");

    // Legacy Collins names belong only here so migration can eventually be deleted whole.
    private static final String OLD_CONFIG = "collins.json";
    private static final String OLD_TOOLS = "collins-tools";
    private static final String OLD_CACHE = "collins-cache";

    private LegacyMigration() {
    }

    public static void run() {
        FabricLoader loader = FabricLoader.getInstance();
        move(loader.getConfigDir().resolve(OLD_CONFIG), loader.getConfigDir().resolve("mine-canvas.json"));
        move(loader.getGameDir().resolve(OLD_TOOLS), loader.getGameDir().resolve("mine-canvas-tools"));
        move(loader.getGameDir().resolve(OLD_CACHE), loader.getGameDir().resolve("mine-canvas-cache"));
    }

    private static void move(Path oldPath, Path newPath) {
        try {
            if (Files.exists(oldPath) && Files.notExists(newPath)) {
                Files.move(oldPath, newPath);
                LOGGER.info("Migrated legacy path {} to {}", oldPath, newPath);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not migrate legacy path {} to {}", oldPath, newPath, e);
        }
    }
}
