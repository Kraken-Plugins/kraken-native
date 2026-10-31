package com.kraken.client.worldhopper;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.World;
import lombok.extern.java.Log;

import javax.swing.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the world list: fetched once {@link #start} is called, then every ten minutes and whenever
 * {@link #refresh} asks. Until the first fetch succeeds the list is empty; a failed fetch keeps the last list.
 *
 * <p>Thread-safe. Listeners run on the EDT after each fetch, whether it worked or not.
 */
@Log(topic = "kraken")
@Singleton
public class WorldService {

    private static final long REFRESH_MINUTES = 10;

    private final WorldClient worldClient;
    private final ScheduledExecutorService scheduler;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean fetching = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();

    private volatile List<World> worlds = List.of();
    private volatile Instant updatedAt;
    private volatile String error;

    /**
     * Makes the service; nothing is fetched until {@link #start}.
     *
     * @param worldClient fetches the list
     * @param scheduler   the shared scheduler, which times the refreshes
     */
    @Inject
    WorldService(WorldClient worldClient, ScheduledExecutorService scheduler) {
        this.worldClient = worldClient;
        this.scheduler = scheduler;
    }

    /**
     * Fetches the list now and every ten minutes from then on. Later calls do nothing.
     */
    public void start() {
        if (started.compareAndSet(false, true)) {
            scheduler.scheduleWithFixedDelay(this::refresh, 0, REFRESH_MINUTES, TimeUnit.MINUTES);
        }
    }

    /**
     * Fetches the list now, unless a fetch is already under way.
     */
    public void refresh() {
        if (!fetching.compareAndSet(false, true)) {
            return;
        }
        try {
            worldClient.fetch().whenComplete((fetched, failure) -> {
                if (failure == null) {
                    worlds = fetched;
                    updatedAt = Instant.now();
                    error = null;
                } else {
                    failed(failure);
                }
                fetching.set(false);
                notifyListeners();
            });
        } catch (RuntimeException e) {
            failed(e);
            fetching.set(false);
            notifyListeners();
        }
    }

    /**
     * The worlds as last fetched.
     *
     * @return the worlds sorted by number; empty until a fetch has worked
     */
    public List<World> getWorlds() {
        return worlds;
    }

    /**
     * A world of the last list, by number.
     *
     * @param id the world's number
     * @return the world, or null if the list has none with that number
     */
    public World findWorld(int id) {
        for (World world : worlds) {
            if (world.getId() == id) {
                return world;
            }
        }
        return null;
    }

    /**
     * When the list was last fetched.
     *
     * @return the time of the last fetch that worked, or null if none has
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Why the last fetch failed.
     *
     * @return the reason, or null if the last fetch worked or none has finished
     */
    public String getError() {
        return error;
    }

    /**
     * Whether a fetch is under way.
     *
     * @return true until the fetch finishes
     */
    public boolean isFetching() {
        return fetching.get();
    }

    /**
     * Runs a listener on the EDT after each fetch.
     *
     * @param listener the listener
     */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void failed(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        if (!message.equals(error)) {
            log.warning("Could not fetch the world list: " + message);
        }
        error = message;
    }

    private void notifyListeners() {
        SwingUtilities.invokeLater(() -> listeners.forEach(Runnable::run));
    }
}
