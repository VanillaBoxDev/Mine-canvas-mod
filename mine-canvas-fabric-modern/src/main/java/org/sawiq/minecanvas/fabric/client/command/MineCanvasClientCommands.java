package org.sawiq.minecanvas.fabric.client.command;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import org.sawiq.minecanvas.fabric.client.config.MineCanvasConfigScreen;

public final class MineCanvasClientCommands {

    private static boolean openConfigNextTick;

    private MineCanvasClientCommands() {
    }

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("minecanvas")
                .then(ClientCommands.literal("config")
                    .executes(ctx -> openConfig())));
        });
    }

    private static int openConfig() {
        openConfigNextTick = true;
        return Command.SINGLE_SUCCESS;
    }

    public static void tick(Minecraft client) {
        if (!openConfigNextTick) return;
        openConfigNextTick = false;
        client.gui.setScreen(new MineCanvasConfigScreen(client.gui.screen()));
    }
}
