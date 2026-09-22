package org.sawiq.minecanvas.fabric.client.video;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoPlayerLifecycleTest {
    @Test void stopWaitsForPublicationAndRejectsTheRevokedOwner() throws Exception {
        VideoPlayer player = new VideoPlayer(new NoOpSink());
        Thread oldOwner = new Thread();
        setOwner(player, oldOwner);

        CountDownLatch publicationEntered = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        CountDownLatch stopStarted = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread publisher = runChecked(() -> assertTrue(player.publishIfCurrent(oldOwner, () -> {
            publicationEntered.countDown();
            await(releasePublication);
            published.set(true);
        })), failure);
        assertTrue(publicationEntered.await(5, TimeUnit.SECONDS));

        Thread stopper = runChecked(() -> {
            stopStarted.countDown();
            player.stop();
            stopReturned.countDown();
        }, failure);
        assertTrue(stopStarted.await(5, TimeUnit.SECONDS));
        assertTrue(awaitState(stopper, Thread.State.BLOCKED));
        assertEquals(1, stopReturned.getCount());

        releasePublication.countDown();
        join(publisher);
        join(stopper);
        assertNull(failure.get());
        assertTrue(published.get());

        Thread newOwner = new Thread();
        setOwner(player, newOwner);
        AtomicInteger freshState = new AtomicInteger();
        assertFalse(player.publishIfCurrent(oldOwner, freshState::incrementAndGet));
        assertTrue(player.publishIfCurrent(newOwner, freshState::incrementAndGet));
        assertEquals(1, freshState.get());
        player.stop();
    }

    private static Thread runChecked(Runnable task, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        thread.start();
        return thread;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for latch");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(5_000);
        assertFalse(thread.isAlive());
    }

    private static boolean awaitState(Thread thread, Thread.State expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == expected) return true;
            Thread.onSpinWait();
        }
        return thread.getState() == expected;
    }

    private static void setOwner(VideoPlayer player, Thread owner) throws ReflectiveOperationException {
        Field running = VideoPlayer.class.getDeclaredField("running");
        running.setAccessible(true);
        running.setBoolean(player, true);
        Field thread = VideoPlayer.class.getDeclaredField("thread");
        thread.setAccessible(true);
        thread.set(player, owner);
    }

    private static final class NoOpSink implements VideoPlayer.FrameSink {
        @Override public void initVideo(int videoW, int videoH, int targetW, int targetH, double fps) { }
        @Override public void onFrame(int[] argb, int w, int h, long timestampUs) { }
        @Override public void onStop() { }
    }
}
