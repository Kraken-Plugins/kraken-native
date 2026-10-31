package com.kraken.api;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asks Windows for a 1 ms timer while the poll loop runs. Windows ends a timed wait on its timer tick, about 15.6 ms
 * unless the process asks for less, so the loop's 20 ms wait would last about 31 ms and the client thread would run
 * about 32 times a second instead of 50. Each {@link #raise} is paired with a {@link #restore}. Does nothing off
 * Windows or if the multimedia timer library can't be loaded.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class TimerResolution {

    private static final Logger log = Logger.getLogger("kraken.api");

    /**
     * The timer period asked for, in milliseconds.
     */
    private static final int PERIOD_MS = 1;

    /**
     * {@code TIMERR_NOERROR}, what {@code timeBeginPeriod} returns when it took the period.
     */
    private static final int NO_ERROR = 0;

    /**
     * The multimedia timer functions of {@code winmm.dll}.
     */
    private interface Winmm extends Library {

        /**
         * Asks for a timer period, process-wide, until the matching {@link #timeEndPeriod}.
         *
         * @param period the period in milliseconds
         * @return {@code TIMERR_NOERROR}, or {@code TIMERR_NOCANDO} if the period is out of range
         */
        int timeBeginPeriod(int period);

        /**
         * Takes back a period {@link #timeBeginPeriod} asked for.
         *
         * @param period the same period
         * @return {@code TIMERR_NOERROR}, or {@code TIMERR_NOCANDO} if it wasn't asked for
         */
        int timeEndPeriod(int period);
    }

    private static final Winmm WINMM = load();

    /**
     * Loads {@code winmm.dll}.
     *
     * @return its functions, or null off Windows or if it can't be loaded
     */
    private static Winmm load() {
        if (!Platform.isWindows()) {
            return null;
        }
        try {
            return Native.load("winmm", Winmm.class);
        } catch (LinkageError e) {
            log.log(Level.FINE, "winmm.dll could not be loaded; the poll loop keeps the default timer", e);
            return null;
        }
    }

    /**
     * Asks for the 1 ms timer.
     *
     * @return true if Windows took it, so {@link #restore} must be called
     */
    static boolean raise() {
        return WINMM != null && WINMM.timeBeginPeriod(PERIOD_MS) == NO_ERROR;
    }

    /**
     * Gives back the 1 ms timer a {@link #raise} that returned true asked for.
     */
    static void restore() {
        if (WINMM != null) {
            WINMM.timeEndPeriod(PERIOD_MS);
        }
    }
}
