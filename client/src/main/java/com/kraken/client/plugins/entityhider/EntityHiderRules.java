package com.kraken.client.plugins.entityhider;

import com.kraken.api.gameval.NpcID;
import com.kraken.api.gameval.SpotanimID;
import com.kraken.api.render.VisibilityRules;

import java.util.Set;

/**
 * What Entity Hider draws, from the settings as they were when the rules were made and from what the plugin read
 * about an actor. Each actor kind (the local player, other players, NPCs) has a default the client applies to actors
 * the plugin hasn't decided yet; {@link #drawPlayer} and {@link #drawNpc} decide each one, per layer: the model, or
 * with {@code drawingUI} its 2D elements.
 */
final class EntityHiderRules {

    /**
     * Thralls, including the cosmetic overrides some leagues give them.
     */
    static final Set<Integer> THRALL_IDS = Set.of(
            NpcID.ARCEUUS_THRALL_GHOST_LESSER, NpcID.ARCEUUS_THRALL_SKELETON_LESSER,
            NpcID.ARCEUUS_THRALL_ZOMBIE_LESSER, NpcID.ARCEUUS_THRALL_GHOST_SUPERIOR,
            NpcID.ARCEUUS_THRALL_SKELETON_SUPERIOR, NpcID.ARCEUUS_THRALL_ZOMBIE_SUPERIOR,
            NpcID.ARCEUUS_THRALL_GHOST_GREATER, NpcID.ARCEUUS_THRALL_SKELETON_GREATER,
            NpcID.ARCEUUS_THRALL_ZOMBIE_GREATER, NpcID.THRALL_IMP_MAGIC_LESSER, NpcID.THRALL_IMP_RANGED_LESSER,
            NpcID.THRALL_IMP_MELEE_LESSER, NpcID.THRALL_IMP_MAGIC_SUPERIOR, NpcID.THRALL_IMP_RANGED_SUPERIOR,
            NpcID.THRALL_IMP_MELEE_SUPERIOR, NpcID.THRALL_IMP_MAGIC_GREATER, NpcID.THRALL_IMP_RANGED_GREATER,
            NpcID.THRALL_IMP_MELEE_GREATER);

    /**
     * The NPCs of random events.
     */
    static final Set<Integer> RANDOM_EVENT_NPC_IDS = Set.of(
            NpcID.MACRO_BEEKEEPER_INVITATION, NpcID.MACRO_COMBILOCK_PIRATE, NpcID.MACRO_JEKYLL,
            NpcID.MACRO_JEKYLL_UNDERWATER, NpcID.MACRO_DWARF, NpcID.PATTERN_INVITATION, NpcID.MACRO_EVIL_BOB_OUTSIDE,
            NpcID.MACRO_EVIL_BOB_PRISON, NpcID.PINBALL_INVITATION, NpcID.MACRO_FORESTER_INVITATION,
            NpcID.MACRO_FROG_CRIER, NpcID.MACRO_FROG_GENERIC, NpcID.MACRO_FROG_SULKING, NpcID.MACRO_FROG_NONCOMBAT,
            NpcID.MACRO_FROG_NOHAT, NpcID.MACRO_FROG_PRIN_HE, NpcID.MACRO_FROG_PRIN_SHE, NpcID.MACRO_FROG_PRIN_A,
            NpcID.MACRO_FROG_PRIN_B, NpcID.MACRO_GENI, NpcID.MACRO_GENI_UNDERWATER, NpcID.MACRO_GILES,
            NpcID.MACRO_GILES_UNDERWATER, NpcID.MACRO_GRAVEDIGGER_INVITATION, NpcID.MACRO_MILES,
            NpcID.MACRO_MILES_UNDERWATER, NpcID.MACRO_MYSTERIOUS_OLD_MAN, NpcID.MACRO_MYSTERIOUS_OLD_MAN_UNDERWATER,
            NpcID.MACRO_MAZE_INVITATION, NpcID.MACRO_MIME_INVITATION, NpcID.MACRO_NILES,
            NpcID.MACRO_NILES_UNDERWATER, NpcID.MACRO_PILLORY_GUARD, NpcID.GRAB_POSTMAN,
            NpcID.MACRO_MAGNESON_INVITATION, NpcID.MACRO_HIGHWAYMAN, NpcID.MACRO_HIGHWAYMAN_UNDERWATER,
            NpcID.MACRO_SANDWICH_LADY_NPC, NpcID.MACRO_DRILLDEMON_INVITATION, NpcID.MACRO_COUNTCHECK_SURFACE,
            NpcID.MACRO_COUNTCHECK_UNDERWATER);

    /**
     * The spot anims the Theatre of Blood's Nylocas leave as they die, hidden with dead NPCs.
     */
    static final Set<Integer> NYLOCAS_DEATH_GRAPHICS = Set.of(
            SpotanimID.TOB_NYLOCAS_DEATH_MELEE_STANDARD, SpotanimID.TOB_NYLOCAS_DEATH_RANGED_STANDARD,
            SpotanimID.TOB_NYLOCAS_DEATH_MAGIC_STANDARD, SpotanimID.TOB_NYLOCAS_DEATH_MELEE_DETONATE,
            SpotanimID.TOB_NYLOCAS_DEATH_RANGED_DETONATE, SpotanimID.TOB_NYLOCAS_DEATH_MAGIC_DETONATE);

    private final boolean hideOthers;
    private final boolean hideOthers2D;
    private final boolean hideFriends;
    private final boolean hideFriendsChatMembers;
    private final boolean hideClanMembers;
    private final boolean hideIgnoredPlayers;
    private final boolean hideLocalPlayer;
    private final boolean hideLocalPlayer2D;
    private final boolean hideNPCs;
    private final boolean hideNPCs2D;
    private final boolean hideDeadNpcs;
    private final boolean hidePets;
    private final boolean hideThralls;
    private final boolean hideRandomEvents;
    private final boolean hideAttackers;
    private final boolean hideProjectiles;

    /**
     * Reads the settings once.
     *
     * @param config the settings
     */
    EntityHiderRules(EntityHiderConfig config) {
        hideOthers = config.hideOthers();
        hideOthers2D = config.hideOthers2D();
        hideFriends = config.hideFriends();
        hideFriendsChatMembers = config.hideFriendsChatMembers();
        hideClanMembers = config.hideClanChatMembers();
        hideIgnoredPlayers = config.hideIgnores();
        hideLocalPlayer = config.hideLocalPlayer();
        hideLocalPlayer2D = config.hideLocalPlayer2D();
        hideNPCs = config.hideNPCs();
        hideNPCs2D = config.hideNPCs2D();
        hideDeadNpcs = config.hideDeadNpcs();
        hidePets = config.hidePets();
        hideThralls = config.hideThralls();
        hideRandomEvents = config.hideRandomEvents();
        hideAttackers = config.hideAttackers();
        hideProjectiles = config.hideProjectiles();
    }

    /**
     * How a player stands with the local player, the first that holds in this order.
     */
    enum Relation {
        FRIEND, FRIENDS_CHAT, CLAN, IGNORED, NONE
    }

    /**
     * What the plugin read about a player.
     *
     * @param local    whether it is the local player
     * @param named    whether its name could be read
     * @param attacker whether it is interacting with the local player
     * @param relation how it stands with the local player
     */
    record PlayerFacts(boolean local, boolean named, boolean attacker, Relation relation) {
    }

    /**
     * What the plugin read about an NPC.
     *
     * @param id        its id
     * @param othersPet whether it is another player's follower
     * @param dying     whether it is dying (only read while dead NPCs are hidden)
     * @param attacker  whether it is interacting with the local player
     */
    record NpcFacts(int id, boolean othersPet, boolean dying, boolean attacker) {
    }

    /**
     * Sets the defaults for each kind, the decision for actors not decided apart, and what is hidden as a whole:
     * projectiles, and the Nylocas death spot anims with dead NPCs.
     *
     * @param visibility the plugin's visibility rules
     */
    void applyKinds(VisibilityRules visibility) {
        visibility.setLocalPlayerHidden(hideLocalPlayer, hideLocalPlayer2D);
        visibility.setPlayersHidden(hideOthers, hideOthers2D);
        visibility.setNpcsHidden(hideNPCs, hideNPCs2D);
        visibility.setProjectilesHidden(hideProjectiles);
        visibility.setGraphicsObjectsHidden(hideDeadNpcs ? NYLOCAS_DEATH_GRAPHICS : Set.of());
    }

    /**
     * Whether dead NPCs are hidden, so the plugin must read whether each NPC is dying.
     *
     * @return true while they are hidden
     */
    boolean hidesDeadNpcs() {
        return hideDeadNpcs;
    }

    /**
     * Whether a player is drawn.
     *
     * @param player    what is known about it
     * @param drawingUI true for its 2D elements, false for its model
     * @return true to draw it
     */
    boolean drawPlayer(PlayerFacts player, boolean drawingUI) {
        if (!player.named()) {
            return true;
        }
        // Hiding the local player and still drawing its 2D elements is an established way to play in PvP
        if (player.local()) {
            return !(drawingUI ? hideLocalPlayer2D : hideLocalPlayer);
        }
        if (hideAttackers && player.attacker()) {
            return false;
        }
        return switch (player.relation()) {
            case FRIEND -> !hideFriends;
            case FRIENDS_CHAT -> !hideFriendsChatMembers;
            case CLAN -> !hideClanMembers;
            case IGNORED -> !hideIgnoredPlayers;
            case NONE -> !(drawingUI ? hideOthers2D : hideOthers);
        };
    }

    /**
     * Whether an NPC is drawn.
     *
     * @param npc       what is known about it
     * @param drawingUI true for its 2D elements, false for its model
     * @return true to draw it
     */
    boolean drawNpc(NpcFacts npc, boolean drawingUI) {
        if (npc.othersPet()) {
            return !hidePets;
        }
        // A dead NPC can still be interacting, so dying goes first
        if (npc.dying() && hideDeadNpcs) {
            return false;
        }
        if (npc.attacker()) {
            boolean hide = hideAttackers;
            // With NPCs hidden in one layer only, hiding attackers only hides them in that layer
            if (hideNPCs2D || hideNPCs) {
                hide &= drawingUI ? hideNPCs2D : hideNPCs;
            }
            return !hide;
        }
        if (THRALL_IDS.contains(npc.id())) {
            return !hideThralls;
        }
        if (RANDOM_EVENT_NPC_IDS.contains(npc.id())) {
            return !hideRandomEvents;
        }
        return !(drawingUI ? hideNPCs2D : hideNPCs);
    }
}
