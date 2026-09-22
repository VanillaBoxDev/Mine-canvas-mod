package org.sawiq.minecanvas.fabric.client.video;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoAudioPlayerPrebufferTest {
    @ParameterizedTest
    @CsvSource({
            "768000, 5500000, 9500000",
            "576000, 5500000, 8500000",
            "0, 5500000, 5400000",
            "4, 5500000, 5500001"
    })
    void skipsStaleAudioOnPcmFrameBoundary(int expected, long audioStartUs, long videoTimestampUs) {
        assertEquals(expected, VideoAudioPlayer.prebufferSkipBytes(audioStartUs, videoTimestampUs, 48_000, 2, 768_000));
    }

    @ParameterizedTest
    @CsvSource({
            "8, 8, 4",
            "4, 7, 4",
            "0, 3, 4"
    })
    void keepsOnlyCompletePcmSampleFrames(int expected, int byteCount, int frameBytes) {
        assertEquals(expected, VideoAudioPlayer.completePcmBytes(byteCount, frameBytes));
    }
}
