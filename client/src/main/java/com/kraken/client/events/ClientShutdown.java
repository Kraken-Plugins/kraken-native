package com.kraken.client.events;

import lombok.Value;
import lombok.extern.java.Log;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Kraken is closing. A subscriber with work to finish (saving a file, say) hands it over with {@link #waitFor}, and the
 * client waits for it, a few seconds at most for all of them, before it stops the plugins and exits. Posted on the
 * thread that started the shutdown, usually the EDT.
 */
@Value
@Log(topic = "kraken")
public class ClientShutdown {

    /**
     * The work the client waits for.
     */
    Queue<Future<?>> tasks = new ConcurrentLinkedQueue<>();

    /**
     * Asks the client to wait for some work before it exits.
     *
     * @param future the work
     */
    public void waitFor(Future<?> future) {
        tasks.add(future);
    }

    /**
     * Waits for the work handed over with {@link #waitFor}, up to a total time. Work that fails is logged.
     *
     * @param totalTimeout how long to wait for all of it together
     */
    public void waitForAllConsumers(Duration totalTimeout) {
        long deadline = System.nanoTime() + totalTimeout.toNanos();
        for (Future<?> task; (task = tasks.poll()) != null; ) {
            long timeout = deadline - System.nanoTime();
            if (timeout < 0) {
                log.warning("Timed out waiting for shutdown tasks");
                return;
            }
            try {
                task.get(timeout, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (TimeoutException e) {
                log.warning("Timed out waiting for shutdown tasks");
                return;
            } catch (Exception e) {
                log.log(Level.WARNING, "A shutdown task failed", e);
            }
        }
    }
}
