package org.sawiq.minecanvas.fabric.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record MineCanvasS2CPayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MineCanvasS2CPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("mine-canvas", "main"));

    // ВАЖНО: не используем readByteArray()/writeByteArray() из-за лимитов, читаем "весь остаток"
    public static final StreamCodec<RegistryFriendlyByteBuf, MineCanvasS2CPayload> CODEC = new StreamCodec<>() {
        @Override
        public MineCanvasS2CPayload decode(RegistryFriendlyByteBuf buf) {
            int readable = buf.readableBytes();
            byte[] bytes = new byte[readable];
            buf.readBytes(bytes);
            return new MineCanvasS2CPayload(bytes);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, MineCanvasS2CPayload payload) {
            buf.writeBytes(payload.data());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
