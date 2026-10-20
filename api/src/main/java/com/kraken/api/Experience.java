package com.kraken.api;

/**
 * The XP table. Lua's {@code ClientState.getStatRemainingXPUntilLevel(stat, level)} is
 * {@code getXpForLevel(level) - client.getSkillExperience(skill)}.
 */
public final class Experience {

    /**
     * The highest level a skill can reach.
     */
    public static final int MAX_REAL_LEVEL = 99;

    /**
     * The highest virtual level the table goes to.
     */
    public static final int MAX_VIRT_LEVEL = 126;

    /**
     * The most XP a skill can hold.
     */
    public static final int MAX_SKILL_XP = 200_000_000;

    /**
     * XP_FOR_LEVEL[n - 1] = total XP needed for level n, as the client's table holds it.
     */
    private static final int[] XP_FOR_LEVEL = table();

    private Experience() {
    }

    /**
     * The total XP a level takes.
     *
     * @param level the level, 1 to {@link #MAX_VIRT_LEVEL}
     * @return the XP; 0 for level 1
     * @throws IllegalArgumentException for a level outside the table
     */
    public static int getXpForLevel(int level) {
        if (level < 1 || level > MAX_VIRT_LEVEL) {
            throw new IllegalArgumentException(level + " is not a valid level");
        }
        return XP_FOR_LEVEL[level - 1];
    }

    /**
     * The level an amount of XP gives.
     *
     * @param xp the XP
     * @return the level, 1 to {@link #MAX_VIRT_LEVEL}
     * @throws IllegalArgumentException for negative XP
     */
    public static int getLevelForXp(int xp) {
        if (xp < 0) {
            throw new IllegalArgumentException("XP (" + xp + ") must not be negative");
        }
        int low = 0;
        int high = XP_FOR_LEVEL.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (XP_FOR_LEVEL[mid] <= xp) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return high + 1;
    }

    private static int[] table() {
        int[] table = new int[MAX_VIRT_LEVEL];
        int points = 0;
        for (int level = 1; level < MAX_VIRT_LEVEL; level++) {
            points += (int) Math.floor(level + 300 * Math.pow(2, level / 7.0));
            table[level] = points / 4;
        }
        return table;
    }
}
