package net.elfisland.plugin.streamlinenet.util;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CountDownLatch;

@Slf4j
public class TaskUtils {

    public static void runBlocking(CompletableTask task) {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            task.execute(latch);
            latch.await();
        } catch (Exception e) {
            log.warn("Failed to run blocking task!", e);
        }
    }

    @FunctionalInterface
    public interface CompletableTask {
        void execute(CountDownLatch latch);
    }

}
