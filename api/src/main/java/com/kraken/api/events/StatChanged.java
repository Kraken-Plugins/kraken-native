package com.kraken.api.events;

import com.kraken.api.Skill;
import lombok.Value;

/**
 * A skill's XP, level or boosted level changed. One event per changed skill. Every skill fires when the stats arrive
 * after login.
 */
@Value
public class StatChanged {

    /**
     * The skill.
     */
    Skill skill;

    /**
     * The skill's XP.
     */
    int xp;

    /**
     * The skill's level from its XP.
     */
    int level;

    /**
     * The skill's current level, boosted or drained.
     */
    int boostedLevel;
}
