package com.kraken.client.plugins.entityhider;

import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.Unsupported;

/**
 * The Entity Hider plugin's settings: which players, NPCs and projectiles to hide, and for players and NPCs whether
 * to hide their models, their 2D elements (names, head bars, overhead text) or both.
 */
@ConfigGroup(EntityHiderConfig.GROUP)
public interface EntityHiderConfig extends Config {

    /**
     * The settings' group.
     */
    String GROUP = "entityhider";

    /**
     * Whether other players' models are hidden.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 1, keyName = "hidePlayers", name = "Hide others",
            description = "Configures whether or not other players are hidden.")
    default boolean hideOthers() {
        return true;
    }

    /**
     * Whether other players' 2D elements are hidden.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 2, keyName = "hidePlayers2D", name = "Hide others' 2D",
            description = "Configures whether or not other players' 2D elements are hidden.")
    default boolean hideOthers2D() {
        return true;
    }

    /**
     * Whether party members are hidden.
     *
     * @return true to hide them
     */
    @Unsupported("Needs a party service")
    @ConfigItem(position = 3, keyName = "hidePartyMembers", name = "Hide party members",
            description = "Configures whether or not party members are hidden.")
    default boolean hidePartyMembers() {
        return false;
    }

    /**
     * Whether friends are hidden.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 4, keyName = "hideFriends", name = "Hide friends",
            description = "Configures whether or not friends are hidden.")
    default boolean hideFriends() {
        return false;
    }

    /**
     * Whether friends chat members are hidden. The key says clan mates; it is the friends chat.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 5, keyName = "hideClanMates", name = "Hide friends chat members",
            description = "Configures whether or not friends chat members are hidden.")
    default boolean hideFriendsChatMembers() {
        return false;
    }

    /**
     * Whether clan chat members are hidden.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 6, keyName = "hideClanChatMembers", name = "Hide clan chat members",
            description = "Configures whether or not clan chat members are hidden.")
    default boolean hideClanChatMembers() {
        return false;
    }

    /**
     * Whether ignored players are hidden.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 7, keyName = "hideIgnores", name = "Hide ignores",
            description = "Configures whether or not ignored players are hidden.")
    default boolean hideIgnores() {
        return false;
    }

    /**
     * Whether the local player's model is hidden.
     *
     * @return true to hide it
     */
    @ConfigItem(position = 8, keyName = "hideLocalPlayer", name = "Hide local player",
            description = "Configures whether or not the local player is hidden.")
    default boolean hideLocalPlayer() {
        return false;
    }

    /**
     * Whether the local player's 2D elements are hidden.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 9, keyName = "hideLocalPlayer2D", name = "Hide local player 2D",
            description = "Configures whether or not the local player's 2D elements are hidden.")
    default boolean hideLocalPlayer2D() {
        return false;
    }

    /**
     * Whether NPCs' models are hidden.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 10, keyName = "hideNPCs", name = "Hide NPCs",
            description = "Configures whether or not NPCs are hidden.")
    default boolean hideNPCs() {
        return false;
    }

    /**
     * Whether NPCs' 2D elements are hidden.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 11, keyName = "hideNPCs2D", name = "Hide NPCs 2D",
            description = "Configures whether or not NPCs 2D elements are hidden.")
    default boolean hideNPCs2D() {
        return false;
    }

    /**
     * Whether other players' boats are hidden.
     *
     * @return true to hide them
     */
    @Unsupported("Needs boats, which the client doesn't read yet")
    @ConfigItem(position = 12, keyName = "hideWorldEntities", name = "Hide boats",
            description = "Configures whether boats are hidden.")
    default boolean hideWorldEntities() {
        return false;
    }

    /**
     * Whether other players' pets are hidden.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 20, keyName = "hidePets", name = "Hide others' pets",
            description = "Configures whether or not other players' pets are hidden.")
    default boolean hidePets() {
        return false;
    }

    /**
     * Whether NPCs and players attacking the local player are hidden.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 21, keyName = "hideAttackers", name = "Hide attackers",
            description = "Configures whether or not NPCs/players attacking you are hidden.")
    default boolean hideAttackers() {
        return false;
    }

    /**
     * Whether projectiles are hidden.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 22, keyName = "hideProjectiles", name = "Hide projectiles",
            description = "Configures whether or not projectiles are hidden.")
    default boolean hideProjectiles() {
        return false;
    }

    /**
     * Whether NPCs are hidden once their health reaches 0.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 23, keyName = "hideDeadNpcs", name = "Hide dead NPCs",
            description = "Hides NPCs when their health reaches 0.")
    default boolean hideDeadNpcs() {
        return false;
    }

    /**
     * Whether thralls are hidden.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 24, keyName = "hideThralls", name = "Hide thralls",
            description = "Configures whether or not thralls are hidden.")
    default boolean hideThralls() {
        return false;
    }

    /**
     * Whether other players' random event NPCs are hidden.
     *
     * @return true to hide them
     */
    @ConfigItem(position = 25, keyName = "hideRandomEvents", name = "Hide others' random events",
            description = "Configures whether or not other players' random events are hidden.")
    default boolean hideRandomEvents() {
        return false;
    }
}
