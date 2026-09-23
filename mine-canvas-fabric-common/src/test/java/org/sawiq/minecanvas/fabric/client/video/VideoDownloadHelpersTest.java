package org.sawiq.minecanvas.fabric.client.video;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoDownloadHelpersTest {
    @Test void buildsPartialPathAndCountsResponseTotal() {
        assertEquals(Path.of("cache", "video.part"), VideoPlayer.partialPath(Path.of("cache"), "video"));
        assertEquals(1_000, VideoPlayer.responseTotalBytes(600, 400, "bytes 400-999/1000"));
        assertEquals(1_000, VideoPlayer.responseTotalBytes(600, 400, null));
        assertEquals(0, VideoPlayer.responseTotalBytes(-1, 400, "bytes 400-999/*"));
    }

    @Test void calculatesProgressAndSpeed() {
        assertEquals(75, VideoPrefetcher.progressPercent(50, 750, 1_000));
        assertEquals(100, VideoPrefetcher.progressPercent(101, 1_500, 1_000));
        assertEquals(0, VideoPrefetcher.progressPercent(-1, 1, 0));
        assertEquals(500, VideoPrefetcher.bytesPerSecond(1_000, 1_500, 1_000_000_000));
        assertEquals(0, VideoPrefetcher.bytesPerSecond(1_500, 1_500, 1_000_000_000));
    }

    @Test void formatsDownloadSizesAndSpeed() {
        assertEquals("0 B", VideoScreenManager.formatDownloadSize(0, 0));
        assertEquals("1 KB", VideoScreenManager.formatDownloadSize(1, 0));
        assertEquals("1.5 MB", VideoScreenManager.formatDownloadSize(1_572_864, 0));
        assertEquals("10 MB", VideoScreenManager.formatDownloadSize(10_485_760, 0));
        assertEquals("2 MB", VideoScreenManager.formatDownloadSize(0, 2));
        assertEquals("1 KB/s", VideoScreenManager.formatSpeed(1));
        assertEquals("0 B/s", VideoScreenManager.formatSpeed(-1));
    }
}
