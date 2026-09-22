package org.sawiq.minecanvas.fabric.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record MineCanvasC2SPayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MineCanvasC2SPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("mine-canvas", "main_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MineCanvasC2SPayload> CODEC = new StreamCodec<>() {
        @Override
        public MineCanvasC2SPayload decode(RegistryFriendlyByteBuf buf) {
            int readable = buf.readableBytes();
            byte[] bytes = new byte[readable];
            buf.readBytes(bytes);
            return new MineCanvasC2SPayload(bytes);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, MineCanvasC2SPayload payload) {
            buf.writeBytes(payload.data());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
