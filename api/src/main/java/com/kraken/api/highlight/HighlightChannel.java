package com.kraken.api.highlight;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One highlight channel a caller holds: a style and the targets it lights up. Taken with
 * {@link Highlights#channel(HighlightCategory)} and given back with {@link #release()}.
 *
 * <p>Changes are sent to the client at once when the hook is connected and kept otherwise; {@link Highlights} applies
 * the whole channel again when the hook (re)connects. {@link #set} is the usual way to drive a channel: call it with
 * everything that should be lit (every tick, or on each spawn and despawn) and only the differences are sent.
 *
 * <p>Thread-safe; every method locks the {@link Highlights} it came from.
 */
public final class HighlightChannel implements AutoCloseable {

    private final Highlights highlights;
    private final HighlightCategory category;
    private final int channel;
    private final Set<HighlightTarget> targets = new LinkedHashSet<>();
    private HighlightConfig config = HighlightConfig.DEFAULT;
    private boolean released;

    HighlightChannel(Highlights highlights, HighlightCategory category, int channel) {
        this.highlights = highlights;
        this.category = category;
        this.channel = channel;
    }

    /**
     * The category the channel lights.
     *
     * @return the category
     */
    public HighlightCategory getCategory() {
        return category;
    }

    /**
     * The channel's number within its category.
     *
     * @return the channel
     */
    public int getChannel() {
        return channel;
    }

    /**
     * How the channel draws.
     *
     * @return the style last set, {@link HighlightConfig#DEFAULT} until one is
     */
    public HighlightConfig getConfig() {
        synchronized (highlights) {
            return config;
        }
    }

    /**
     * Sets how the channel draws. Sends nothing if the style is unchanged, so it can be called every frame.
     *
     * @param config the style
     */
    public void configure(HighlightConfig config) {
        Objects.requireNonNull(config, "config");
        synchronized (highlights) {
            checkHeld();
            if (!config.equals(this.config)) {
                this.config = config;
                highlights.configure(this, config);
            }
        }
    }

    /**
     * What the channel lights up.
     *
     * @return a copy of the targets
     */
    public Set<HighlightTarget> getTargets() {
        synchronized (highlights) {
            return Set.copyOf(targets);
        }
    }

    /**
     * Lights one more target.
     *
     * @param target the target, of this channel's category
     * @throws IllegalArgumentException if the target belongs to another category
     */
    public void add(HighlightTarget target) {
        synchronized (highlights) {
            checkHeld();
            check(target);
            if (targets.add(target)) {
                highlights.update(this, List.of(), List.of(target));
            }
        }
    }

    /**
     * Stops lighting a target.
     *
     * @param target the target
     */
    public void remove(HighlightTarget target) {
        synchronized (highlights) {
            checkHeld();
            if (targets.remove(target)) {
                highlights.update(this, List.of(target), List.of());
            }
        }
    }

    /**
     * Lights exactly these targets: turns on the new ones and off the ones not listed, and sends nothing for the
     * rest.
     *
     * @param wanted the targets, all of this channel's category
     * @throws IllegalArgumentException if a target belongs to another category
     */
    public void set(Collection<? extends HighlightTarget> wanted) {
        Set<HighlightTarget> want = new LinkedHashSet<>(wanted);
        want.forEach(this::check);
        synchronized (highlights) {
            checkHeld();
            List<HighlightTarget> off = new ArrayList<>();
            for (HighlightTarget target : targets) {
                if (!want.contains(target)) {
                    off.add(target);
                }
            }
            List<HighlightTarget> on = new ArrayList<>();
            for (HighlightTarget target : want) {
                if (!targets.contains(target)) {
                    on.add(target);
                }
            }
            if (off.isEmpty() && on.isEmpty()) {
                return;
            }
            targets.removeAll(off);
            targets.addAll(on);
            highlights.update(this, off, on);
        }
    }

    /**
     * Stops lighting everything; the channel keeps its style.
     */
    public void clear() {
        synchronized (highlights) {
            checkHeld();
            if (!targets.isEmpty()) {
                targets.clear();
                highlights.clear(this);
            }
        }
    }

    /**
     * Turns everything off and gives the channel back. Using it afterwards throws.
     */
    public void release() {
        synchronized (highlights) {
            if (!released) {
                released = true;
                targets.clear();
                highlights.release(this);
            }
        }
    }

    /**
     * Whether the channel was given back.
     *
     * @return true after {@link #release()}
     */
    public boolean isReleased() {
        synchronized (highlights) {
            return released;
        }
    }

    /**
     * Same as {@link #release()}.
     */
    @Override
    public void close() {
        release();
    }

    HighlightConfig config() {
        return config;
    }

    Set<HighlightTarget> targets() {
        return targets;
    }

    private void check(HighlightTarget target) {
        if (Objects.requireNonNull(target, "target").category() != category) {
            throw new IllegalArgumentException(target + " is not a " + category + " target");
        }
    }

    private void checkHeld() {
        if (released) {
            throw new IllegalStateException(category + " highlight channel " + channel + " was released");
        }
    }

    @Override
    public String toString() {
        return "HighlightChannel[" + category + " " + channel + "]";
    }
}
