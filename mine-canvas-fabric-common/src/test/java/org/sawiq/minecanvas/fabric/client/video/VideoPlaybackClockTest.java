package org.sawiq.minecanvas.fabric.client.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoPlaybackClockTest {
    @Test void reportsAudioPositionRelativeToVideoBase() {
        assertEquals(520_000, VideoAudioPlayer.mediaPositionUs(1_020_000, 1_000_000, 24_000, 48_000));
    }

    @Test void fallsBackToWallClockWhenAudioIsUnavailable() {
        VideoScreen.PlaybackClock clock = new VideoScreen.PlaybackClock();

        assertEquals(250_000, clock.elapsedUs(-1, 250_000));
        assertEquals(500_000, clock.elapsedUs(300_000, 500_000));
    }

    @Test void followsAdvancingAudio() {
        VideoScreen.PlaybackClock clock = new VideoScreen.PlaybackClock();

        assertEquals(100_000, clock.elapsedUs(100_000, 200_000));
        assertEquals(250_000, clock.elapsedUs(250_000, 400_000));
    }

    @Test void toleratesShortAudioStall() {
        VideoScreen.PlaybackClock clock = new VideoScreen.PlaybackClock();

        assertEquals(100_000, clock.elapsedUs(100_000, 200_000));
        assertEquals(100_000, clock.elapsedUs(100_000, 699_999));
    }

    @Test void fallsBackAtAudioStallThreshold() {
        VideoScreen.PlaybackClock clock = new VideoScreen.PlaybackClock();

        assertEquals(100_000, clock.elapsedUs(100_000, 200_000));
        assertEquals(600_000, clock.elapsedUs(100_000, 700_000));
    }

    @Test void staysOnWallClockWhenLaggingAudioResumes() {
        VideoScreen.PlaybackClock clock = new VideoScreen.PlaybackClock();

        clock.elapsedUs(100_000, 200_000);
        assertEquals(600_000, clock.elapsedUs(100_000, 700_000));
        assertEquals(700_000, clock.elapsedUs(300_000, 800_000));
    }

    @Test void resetAllowsAudioClockToBeUsedAgain() {
        VideoScreen.PlaybackClock clock = new VideoScreen.PlaybackClock();

        clock.elapsedUs(-1, 700_000);
        clock.reset();

        assertEquals(100_000, clock.elapsedUs(100_000, 800_000));
    }
}
