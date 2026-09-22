package org.sawiq.minecanvas.fabric.client.util;

public final class TimeFormatUtil {

    private TimeFormatUtil() {
    }

    public static String formatMs(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000L;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;

        if (hours > 0) {
            return hours + ":" + pad2(minutes) + ":" + pad2(seconds);
        }
        return minutes + ":" + pad2(seconds);
    }

    private static String pad2(long v) {
        if (v < 10) return "0" + v;
        return Long.toString(v);
    }
}
