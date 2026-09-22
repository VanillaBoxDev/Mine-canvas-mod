package org.sawiq.minecanvas.fabric.client.video;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** MC-version-specific chat/screen calls used by the shared VideoScreenManager. */
public final class VideoPlatformBridge {

    private VideoPlatformBridge() {}

    public static void systemMessage(LocalPlayer player, Component message) {
        player.displayClientMessage(message, false);
    }

    public static void overlayMessage(LocalPlayer player, Component message) {
        player.displayClientMessage(message, true);
    }

    public static boolean chatScreenOpen(Minecraft client) {
        return client.screen instanceof ChatScreen;
    }
}
