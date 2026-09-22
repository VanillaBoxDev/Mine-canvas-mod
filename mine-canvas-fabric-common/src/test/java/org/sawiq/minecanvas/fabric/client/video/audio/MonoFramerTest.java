package org.sawiq.minecanvas.fabric.client.video.audio;

import org.junit.jupiter.api.Test;

import java.nio.Buffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonoFramerTest {
    @Test void downmixesPlanarStereo() {
        short[] left = filled((short) 1_000, MonoFramer.FRAME_SIZE);
        short[] right = filled((short) 3_000, MonoFramer.FRAME_SIZE);
        List<short[]> frames = new ArrayList<>();

        new MonoFramer().accept(buffers(left, right), 2, 1f, frames::add);

        assertEquals(1, frames.size());
        assertEquals(2_000, frames.getFirst()[0]);
        assertEquals(2_000, frames.getFirst()[MonoFramer.FRAME_SIZE - 1]);
    }

    @Test void appliesGainAndSaturates() {
        short[] samples = filled(Short.MAX_VALUE, MonoFramer.FRAME_SIZE);
        List<short[]> frames = new ArrayList<>();

        new MonoFramer().accept(buffers(samples), 1, 2f, frames::add);

        assertEquals(Short.MAX_VALUE, frames.getFirst()[0]);
    }

    @Test void saturatesNegativeSamples() {
        short[] samples = filled(Short.MIN_VALUE, MonoFramer.FRAME_SIZE);
        List<short[]> frames = new ArrayList<>();

        new MonoFramer().accept(buffers(samples), 1, 2f, frames::add);

        assertEquals(Short.MIN_VALUE, frames.getFirst()[0]);
    }

    @Test void emitsExactFramesAndCarriesRemainder() {
        MonoFramer framer = new MonoFramer();
        List<short[]> frames = new ArrayList<>();
        framer.accept(buffers(filled((short) 7, 1_000)), 1, 0.5f, frames::add);
        assertEquals(1, frames.size());
        assertEquals(MonoFramer.FRAME_SIZE, frames.getFirst().length);

        framer.accept(buffers(filled((short) 9, 920)), 1, 1f, frames::add);
        assertEquals(2, frames.size());
        assertEquals(4, frames.get(1)[0]);
        assertEquals(9, frames.get(1)[MonoFramer.FRAME_SIZE - 1]);
    }

    @Test void pacingUsesFrameBoundaryAndCapsLead() {
        assertTrue(MonoFramer.shouldWait(3, 29_999_999L, 50_000_000L));
        assertFalse(MonoFramer.shouldWait(3, 30_000_000L, 50_000_000L));
        assertTrue(MonoFramer.shouldWait(5, 19_999_999L, 500_000_000L));
        assertFalse(MonoFramer.shouldWait(5, 20_000_000L, 500_000_000L));
    }

    private static Buffer[] buffers(short[]... samples) {
        Buffer[] buffers = new Buffer[samples.length];
        for (int i = 0; i < samples.length; i++) buffers[i] = ShortBuffer.wrap(samples[i]);
        return buffers;
    }

    private static short[] filled(short value, int length) {
        short[] samples = new short[length];
        java.util.Arrays.fill(samples, value);
        return samples;
    }
}
