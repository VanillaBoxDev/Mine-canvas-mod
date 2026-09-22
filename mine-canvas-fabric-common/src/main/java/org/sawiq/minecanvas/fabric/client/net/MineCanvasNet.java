package org.sawiq.minecanvas.fabric.client.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import org.sawiq.minecanvas.fabric.client.state.ScreenState;
import org.sawiq.minecanvas.fabric.client.video.VideoQuality;
import org.sawiq.minecanvas.fabric.net.MineCanvasC2SPayload;
import org.sawiq.minecanvas.fabric.net.MineCanvasS2CPayload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MineCanvasNet {

    private MineCanvasNet() {}

    private static final boolean DEBUG = false;

    public static final int MAX_PACKET_BYTES = 5_000_000;

    // Protocol constants (mirrored from server MineCanvasProtocol)
    private static final int PROTOCOL_VERSION = 6;
    private static final byte MSG_HELLO = 4;

    public static final Map<String, ScreenState> SCREENS = new ConcurrentHashMap<>();

    public static volatile float GLOBAL_VOLUME = 1.0f;
    public static volatile int HEAR_RADIUS = 100;
    public static volatile long SERVER_NOW_MS = 0;
    public static volatile long CLIENT_RECV_MS = 0;
    public static volatile UUID SERVER_BOOT_ID = new UUID(0L, 0L);

    private static final long HELLO_RESEND_INTERVAL_MS = 10_000L;
    private static volatile long lastHelloSentAtMs = 0L;

    public static void initClientReceiver() {
        if (DEBUG) System.out.println("[MineCanvas] Client init: registering receiver mine-canvas:main");

        ClientPlayNetworking.registerGlobalReceiver(MineCanvasS2CPayload.ID, (payload, context) -> {
            byte[] bytes = payload.data();

            context.client().execute(() -> {
                try {
                    sendHelloIfNeeded();
                    parseWrapped(bytes);
                } catch (Exception e) {
                    if (DEBUG) System.out.println("[MineCanvas] Failed to parse packet: " + e.getMessage());
                }
            });
        });
    }

    private static void sendHelloIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastHelloSentAtMs < HELLO_RESEND_INTERVAL_MS) {
            return;
        }

        try {
            ClientPlayNetworking.send(new MineCanvasC2SPayload(buildHelloMessage()));
            lastHelloSentAtMs = now;
        } catch (Exception e) {
            if (DEBUG) System.out.println("[MineCanvas] Failed to send hello: " + e.getMessage());
        }
    }

    private static byte[] buildHelloMessage() throws Exception {
        var innerBout = new ByteArrayOutputStream();
        var innerOut = new DataOutputStream(innerBout);
        innerOut.writeByte(MSG_HELLO);
        innerOut.writeInt(PROTOCOL_VERSION);
        innerOut.writeUTF(getClientModVersion());
        innerOut.flush();
        byte[] inner = innerBout.toByteArray();

        var bout = new ByteArrayOutputStream();
        var out = new DataOutputStream(bout);
        out.write("COLL".getBytes(StandardCharsets.US_ASCII));
        out.writeInt(inner.length);
        out.write(inner);
        out.flush();
        return bout.toByteArray();
    }

    private static void parseWrapped(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length < 8) return;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = new byte[4];
            in.readFully(magic);

            String m = new String(magic, StandardCharsets.US_ASCII);
            if (!m.equals("COLL")) {
                return;
            }

            int len = in.readInt();
            if (len < 0 || len > MAX_PACKET_BYTES) {
                if (DEBUG) System.out.println("[MineCanvas] Bad len=" + len);
                return;
            }

            int available = in.available();
            if (available < len) {
                if (DEBUG) System.out.println("[MineCanvas] Not enough bytes. need=" + len + " avail=" + available);
                return;
            }

            byte[] inner = new byte[len];
            in.readFully(inner);

            parseInner(inner);
        }
    }

    private static void parseInner(byte[] inner) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(inner))) {
            byte msg = in.readByte();
            int version = in.readInt();

            if (msg != 1) {
                if (DEBUG) System.out.println("[MineCanvas] Unsupported msg=" + msg + " ver=" + version);
                return;
            }

            if (version == 1) {
                int count = in.readInt();
                if (count < 0 || count > 10_000) {
                    if (DEBUG) System.out.println("[MineCanvas] Bad screen count=" + count);
                    return;
                }

                Map<String, ScreenState> screens = new HashMap<>();
                for (int i = 0; i < count; i++) {
                    String name = in.readUTF();
                    String world = in.readUTF();

                    int x1 = in.readInt(), y1 = in.readInt(), z1 = in.readInt();
                    int x2 = in.readInt(), y2 = in.readInt(), z2 = in.readInt();

                    byte axis = in.readByte();
                    String url = in.readUTF();

                    boolean playing = in.readBoolean();
                    boolean loop = in.readBoolean();
                    float volume = in.readFloat();

                    screens.put(name.toLowerCase(), new ScreenState(
                            name, world,
                            x1, y1, z1,
                            x2, y2, z2,
                            axis,
                            url,
                            playing,
                            loop,
                            volume,
                            VideoQuality.DEFAULT,
                            0L,
                            0L,
                            new UUID(0L, 0L),
                            0L,
                            0L
                    ));
                }

                GLOBAL_VOLUME = 1.0f;
                HEAR_RADIUS = 100;
                SERVER_NOW_MS = 0L;
                CLIENT_RECV_MS = 0L;
                SERVER_BOOT_ID = new UUID(0L, 0L);
                SCREENS.clear();
                SCREENS.putAll(screens);
                org.sawiq.minecanvas.fabric.client.video.VideoScreenManager.applySync(SCREENS);
                if (DEBUG) System.out.println("[MineCanvas] SYNC v1 received: " + count + " screens");
                return;
            }

            if (version != 2 && version != 3 && version != 4 && version != 5 && version != 6) {
                if (DEBUG) System.out.println("[MineCanvas] Unsupported msg=" + msg + " ver=" + version);
                return;
            }

            float globalVolume = in.readFloat();
            int hearRadius = in.readInt();
            long serverNowMs = in.readLong();
            long clientRecvMs = System.currentTimeMillis();
            UUID serverBootId = version >= 6
                    ? new UUID(in.readLong(), in.readLong())
                    : new UUID(0L, 0L);

            if (version >= 3) {
                int moddedCount = in.readInt();
                if (moddedCount < 0 || moddedCount > 10_000) {
                    if (DEBUG) System.out.println("[MineCanvas] Bad modded player count=" + moddedCount);
                    return;
                }
                for (int i = 0; i < moddedCount; i++) {
                    in.readLong();
                    in.readLong();
                }
            }

            int count = in.readInt();
            if (count < 0 || count > 10_000) {
                if (DEBUG) System.out.println("[MineCanvas] Bad screen count=" + count);
                return;
            }

            Map<String, ScreenState> screens = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                String world = in.readUTF();

                int x1 = in.readInt(), y1 = in.readInt(), z1 = in.readInt();
                int x2 = in.readInt(), y2 = in.readInt(), z2 = in.readInt();

                byte axis = in.readByte();
                String url = in.readUTF();

                boolean playing = in.readBoolean();
                boolean loop = in.readBoolean();
                float volume = in.readFloat();
                int quality = version >= 4 ? in.readInt() : VideoQuality.DEFAULT;

                long startEpochMs = in.readLong();
                long basePosMs = in.readLong();
                long sourceGeneration = version >= 6 ? in.readLong() : 0L;
                long commandRevision = version >= 6 ? in.readLong() : 0L;

                screens.put(name.toLowerCase(), new ScreenState(
                        name, world,
                        x1, y1, z1,
                        x2, y2, z2,
                        axis,
                        url,
                        playing,
                        loop,
                        volume,
                        quality,
                        startEpochMs,
                        basePosMs,
                        serverBootId,
                        sourceGeneration,
                        commandRevision
                ));
            }

            GLOBAL_VOLUME = globalVolume;
            HEAR_RADIUS = hearRadius;
            SERVER_NOW_MS = serverNowMs;
            CLIENT_RECV_MS = clientRecvMs;
            SERVER_BOOT_ID = serverBootId;
            SCREENS.clear();
            SCREENS.putAll(screens);
            org.sawiq.minecanvas.fabric.client.video.VideoScreenManager.applySync(SCREENS);
            if (DEBUG) System.out.println("[MineCanvas] SYNC v" + version + " received: " + count + " screens");
        }
    }

    private static String getClientModVersion() {
        return FabricLoader.getInstance()
                .getModContainer("mine-canvas-fabric")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .filter(version -> version != null && !version.isBlank() && version.length() <= 64)
                .orElse("unknown");
    }
}
