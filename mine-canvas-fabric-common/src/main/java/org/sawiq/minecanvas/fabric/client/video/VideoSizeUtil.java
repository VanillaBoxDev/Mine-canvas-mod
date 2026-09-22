package org.sawiq.minecanvas.fabric.client.video;

public final class VideoSizeUtil {
    private VideoSizeUtil() {}

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // Подбираем размер текстуры так, чтобы:
    // - влезало в блоки * PX_PER_BLOCK
    // - не выходило за MAX
    // - сохраняло аспект видео (без растяжения)
    public static Size pick(int blocksW, int blocksH, int videoW, int videoH) {
        blocksW = Math.max(1, blocksW);
        blocksH = Math.max(1, blocksH);
        videoW = Math.max(1, videoW);
        videoH = Math.max(1, videoH);

        int maxW = clamp(blocksW * VideoConfig.PX_PER_BLOCK, VideoConfig.MIN_W, VideoConfig.MAX_W);
        int maxH = clamp(blocksH * VideoConfig.PX_PER_BLOCK, VideoConfig.MIN_H, VideoConfig.MAX_H);

        double aspect = (double) videoW / (double) videoH;

        int w = maxW;
        int h = (int) Math.round(w / aspect);
        if (h > maxH) {
            h = maxH;
            w = (int) Math.round(h * aspect);
        }

        w = clamp(w, VideoConfig.MIN_W, VideoConfig.MAX_W);
        h = clamp(h, VideoConfig.MIN_H, VideoConfig.MAX_H);
        return new Size(w, h);
    }

    public record Size(int w, int h) {}
}
