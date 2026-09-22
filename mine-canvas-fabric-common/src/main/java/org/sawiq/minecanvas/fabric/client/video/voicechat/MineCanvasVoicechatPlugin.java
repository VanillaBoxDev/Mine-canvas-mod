package org.sawiq.minecanvas.fabric.client.video.voicechat;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import org.sawiq.minecanvas.fabric.client.video.audio.SpatialAudio;

public final class MineCanvasVoicechatPlugin implements VoicechatPlugin {
    private volatile SessionFactory factory;

    @Override
    public String getPluginId() {
        return "minecanvas_screen_audio";
    }

    @Override
    public void initialize(VoicechatApi api) { }

    @Override
    public void registerEvents(EventRegistration registration) {
        try {
            registration.registerEvent(ClientVoicechatConnectionEvent.class, this::onConnection);
        } catch (Throwable ignored) {
            disable();
        }
    }

    private synchronized void onConnection(ClientVoicechatConnectionEvent event) {
        try {
            VoicechatClientApi api = event.getVoicechat();
            if (event.isConnected() && !api.isDisconnected() && !api.isDisabled()) {
                SessionFactory connectedFactory = new SessionFactory(api);
                factory = connectedFactory;
                SpatialAudio.setFactory(connectedFactory);
            } else {
                disable();
            }
        } catch (Throwable ignored) {
            disable();
        }
    }

    private synchronized void disable() {
        SessionFactory current = factory;
        factory = null;
        if (current != null) SpatialAudio.clearFactory(current);
    }

    private synchronized void disable(SessionFactory expected) {
        if (factory != expected) return;
        factory = null;
        SpatialAudio.clearFactory(expected);
    }

    private final class SessionFactory implements SpatialAudio.OutputFactory {
        private final VoicechatClientApi api;

        private SessionFactory(VoicechatClientApi api) {
            this.api = api;
        }

        @Override
        public SpatialAudio.Output create(net.minecraft.world.phys.Vec3 center, float distance) {
            if (api.isDisconnected() || api.isDisabled()) {
                disable(this);
                return null;
            }
            return new VoicechatPcmOutput(api, center, distance, () -> disable(this));
        }
    }
}
