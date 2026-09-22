package org.sawiq.minecanvas.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.sawiq.minecanvas.fabric.net.MineCanvasC2SPayload;
import org.sawiq.minecanvas.fabric.net.MineCanvasS2CPayload;

public final class MineCanvasMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // S2C - сервер -> клиент
        PayloadTypeRegistry.clientboundPlay().register(MineCanvasS2CPayload.ID, MineCanvasS2CPayload.CODEC);
        // C2S - клиент -> сервер
        PayloadTypeRegistry.serverboundPlay().register(MineCanvasC2SPayload.ID, MineCanvasC2SPayload.CODEC);
    }
}
