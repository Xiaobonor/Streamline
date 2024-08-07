package net.elfisland.plugin.streamlinenet.util;

import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
public class TaskUtils {

    private static final long DEFAULT_TIMEOUT = 60L; // Default timeout duration in seconds

    /**
     * Executes a CompletableTask in a blocking manner, waiting for the task to signal completion.
     *
     * @param task the task to be executed
     */
    public static void runBlocking(CompletableTask task) {
        runBlocking(task, DEFAULT_TIMEOUT, TimeUnit.SECONDS);
    }

    /**
     * Executes a CompletableTask in a blocking manner with a specified timeout.
     *
     * @param task the task to be executed
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     */
    public static void runBlocking(CompletableTask task, long timeout, TimeUnit unit) {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            task.execute(latch);
            if (!latch.await(timeout, unit)) {
                log.warn("Task did not complete within the specified timeout of {} {}", timeout, unit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Task was interrupted while waiting!", e);
        } catch (Exception e) {
            log.error("Failed to run blocking task!", e);
        }
    }

    /**
     * A functional interface representing a task that can signal its completion using a CountDownLatch.
     */
    @FunctionalInterface
    public interface CompletableTask {
        void execute(CountDownLatch latch);
    }
}
