package org.sawiq.minecanvas.fabric.client.video;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ScreenOverlayTextures {

    private static final Logger LOGGER = LoggerFactory.getLogger("MineCanvas");
    private static final Identifier PAUSE_ID = Identifier.fromNamespaceAndPath("mine-canvas", "screen/pause");
    private static final int DARK_ABGR = 0xFF24242A;
    private static final int OUTLINE_ABGR = 0xFF08080A;
    private static final int LIGHT_ABGR = 0xFFF4F1E8;
    private static volatile DynamicTexture pauseTexture;
    private static boolean failureLogged;

    private ScreenOverlayTextures() {}

    static Identifier textureId() {
        if (pauseTexture != null) return PAUSE_ID;
        synchronized (ScreenOverlayTextures.class) {
            if (pauseTexture != null) return PAUSE_ID;
            try {
                DynamicTexture texture = new DynamicTexture("minecanvas:" + PAUSE_ID, 32, 32, true);
                NativeImage image = texture.getPixels();
                image.fillRect(0, 0, 32, 32, 0);
                image.fillRect(7, 4, 18, 24, DARK_ABGR);
                image.fillRect(4, 7, 24, 18, DARK_ABGR);
                image.fillRect(5, 5, 22, 22, DARK_ABGR);
                image.fillRect(9, 9, 5, 14, OUTLINE_ABGR);
                image.fillRect(18, 9, 5, 14, OUTLINE_ABGR);
                image.fillRect(10, 10, 3, 12, LIGHT_ABGR);
                image.fillRect(19, 10, 3, 12, LIGHT_ABGR);
                texture.upload();
                Minecraft.getInstance().getTextureManager().register(PAUSE_ID, texture);
                pauseTexture = texture;
                return PAUSE_ID;
            } catch (Exception e) {
                if (!failureLogged) {
                    failureLogged = true;
                    LOGGER.error("Could not create pause overlay texture", e);
                }
                return null;
            }
        }
    }

}
