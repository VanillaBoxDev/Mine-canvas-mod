package org.sawiq.minecanvas.fabric.client.video;

import java.util.List;

public final class VideoQuality {

    public static final int AUTO = 0;
    public static final int DEFAULT = 720;
    private static final List<Integer> ALLOWED = List.of(AUTO, 360, 480, 720, 1080, 1440, 2160);

    private VideoQuality() {
    }

    public static int sanitize(int value, int fallback) {
        if (ALLOWED.contains(value)) {
            return value;
        }
        return ALLOWED.contains(fallback) ? fallback : DEFAULT;
    }

    public static int resolveEffectiveHeight(int blocksH, int serverQuality) {
        int autoHeight = resolveAutoHeight(blocksH);
        int effective = autoHeight;

        int sanitizedServer = sanitize(serverQuality, DEFAULT);
        if (sanitizedServer > AUTO) {
            effective = Math.min(effective, sanitizedServer);
        }

        return Math.max(360, Math.min(2160, effective));
    }

    private static int resolveAutoHeight(int blocksH) {
        int estimatedHeight = Math.max(1, blocksH) * VideoConfig.PX_PER_BLOCK;
        return Math.max(DEFAULT, Math.min(2160, estimatedHeight));
    }
}
