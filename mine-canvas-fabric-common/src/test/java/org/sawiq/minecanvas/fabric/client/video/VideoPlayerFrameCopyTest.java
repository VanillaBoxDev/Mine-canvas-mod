package org.sawiq.minecanvas.fabric.client.video;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoPlayerFrameCopyTest {
    @Test void copiesPaddedAndTightRows() {
        assertRows(3 * 4 + 64);
        assertRows(3 * 4);
    }

    private static void assertRows(int stride) {
        Frame frame = new Frame();
        ByteBuffer pixels = ByteBuffer.allocate(stride * 2).order(ByteOrder.LITTLE_ENDIAN);
        pixels.asIntBuffer().put(0, 11).put(1, 12).put(2, 13).put(stride / 4, 21).put(stride / 4 + 1, 22).put(stride / 4 + 2, 23);
        frame.image = new ByteBuffer[] { pixels };
        frame.imageStride = stride;
        int[] output = new int[6];
        assertTrue(VideoPlayer.copyFrameRows(frame, output, 3, 2));
        assertArrayEquals(new int[] { 11, 12, 13, 21, 22, 23 }, output);
    }
}
