package com.kraken.api.events;

import com.kraken.api.Skill;
import lombok.Value;

/**
 * The client showed an XP drop that adds no XP: the server runs the XP drop script for a skill whose XP can't grow
 * (one at 200M XP), with the XP the action would have given. Comes from the native hook's script reports, so it needs
 * the hook connected; it is posted after the script's {@code ScriptPreFired}.
 */
@Value
public class FakeXpDrop {

    /**
     * The skill the drop shows.
     */
    Skill skill;

    /**
     * The XP the drop shows.
     */
    int xp;
}
