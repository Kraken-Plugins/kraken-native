package com.kraken.api;

import lombok.Value;

/**
 * A hitsplat an actor shows: one of the client's hitmarks, as it was read.
 */
@Value
public class Hitsplat {

    /**
     * The hitsplat's type, one of {@link HitsplatID}'s.
     */
    int hitsplatType;

    /**
     * The number it shows.
     */
    int amount;

    /**
     * The client cycle it stops showing on ({@link Client#getGameCycle()}): the cycle it arrived, plus its delay and
     * its type's duration.
     */
    int disappearsOnGameCycle;

    /**
     * Whether the hitsplat is the local player's: a hit or block the local player dealt or took.
     *
     * @return true for a "me" type
     */
    public boolean isMine() {
        switch (hitsplatType) {
            case HitsplatID.BLOCK_ME:
            case HitsplatID.DAMAGE_ME:
            case HitsplatID.DAMAGE_ME_CYAN:
            case HitsplatID.DAMAGE_ME_YELLOW:
            case HitsplatID.DAMAGE_ME_ORANGE:
            case HitsplatID.DAMAGE_ME_WHITE:
            case HitsplatID.DAMAGE_ME_POISE:
            case HitsplatID.DAMAGE_MAX_ME:
            case HitsplatID.DAMAGE_MAX_ME_CYAN:
            case HitsplatID.DAMAGE_MAX_ME_ORANGE:
            case HitsplatID.DAMAGE_MAX_ME_YELLOW:
            case HitsplatID.DAMAGE_MAX_ME_WHITE:
            case HitsplatID.DAMAGE_MAX_ME_POISE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether the hitsplat is someone else's: a hit or block between other actors.
     *
     * @return true for an "other" type
     */
    public boolean isOthers() {
        switch (hitsplatType) {
            case HitsplatID.BLOCK_OTHER:
            case HitsplatID.DAMAGE_OTHER:
            case HitsplatID.DAMAGE_OTHER_CYAN:
            case HitsplatID.DAMAGE_OTHER_YELLOW:
            case HitsplatID.DAMAGE_OTHER_ORANGE:
            case HitsplatID.DAMAGE_OTHER_WHITE:
            case HitsplatID.DAMAGE_OTHER_POISE:
                return true;
            default:
                return false;
        }
    }
}
