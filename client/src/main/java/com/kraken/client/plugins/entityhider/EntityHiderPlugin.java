package com.kraken.client.plugins.entityhider;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.ActorDeath;
import com.kraken.api.events.GameTick;
import com.kraken.api.events.InteractingChanged;
import com.kraken.api.events.NpcChanged;
import com.kraken.api.events.NpcDespawned;
import com.kraken.api.events.NpcSpawned;
import com.kraken.api.events.PlayerDespawned;
import com.kraken.api.events.PlayerSpawned;
import com.kraken.api.render.VisibilityRules;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.game.NpcUtil;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.plugins.entityhider.EntityHiderRules.NpcFacts;
import com.kraken.client.plugins.entityhider.EntityHiderRules.PlayerFacts;
import com.kraken.client.plugins.entityhider.EntityHiderRules.Relation;

/**
 * Hides players, NPCs and projectiles. The plugin keeps visibility rules ({@link Client#getEntityVisibility()}) that
 * the client's native hook applies every frame: a default for each kind of actor, set from the settings, and a
 * decision for each actor whose friends, attackers, pets or dying status matter, made as it spawns, changes target or
 * form, or dies, and again at every game tick.
 *
 * <p>Differences from the original: an actor that appears is drawn for one frame before it is hidden, and one whose
 * standing changes waits for its event or the next game tick rather than the next frame; party members and other
 * players' boats can't be hidden yet.
 */
@PluginDescriptor(
        name = "Entity Hider",
        description = "Hide players, NPCs, and/or projectiles",
        tags = {"npcs", "players", "projectiles"},
        enabledByDefault = false
)
public class EntityHiderPlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private EntityHiderConfig config;

    private volatile EntityHiderRules rules;
    private volatile VisibilityRules visibility;

    @Provides
    EntityHiderConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(EntityHiderConfig.class);
    }

    @Override
    protected void startUp() {
        rules = new EntityHiderRules(config);
        visibility = client.getEntityVisibility().createRules();
        clientThread.invoke(this::decideAll);
    }

    @Override
    protected void shutDown() {
        VisibilityRules closing = visibility;
        visibility = null;
        if (closing != null) {
            closing.close();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (EntityHiderConfig.GROUP.equals(event.getGroup())) {
            clientThread.invoke(() -> {
                rules = new EntityHiderRules(config);
                decideAll();
            });
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        decideAll();
    }

    @Subscribe
    public void onPlayerSpawned(PlayerSpawned event) {
        decide(event.getPlayer(), client.getLocalPlayer());
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        decide(event.getNpc(), client.getLocalPlayer());
    }

    @Subscribe
    public void onNpcChanged(NpcChanged event) {
        decide(event.getNpc(), client.getLocalPlayer());
    }

    @Subscribe
    public void onInteractingChanged(InteractingChanged event) {
        decide(event.getSource(), client.getLocalPlayer());
    }

    @Subscribe
    public void onActorDeath(ActorDeath event) {
        decide(event.getActor(), client.getLocalPlayer());
    }

    @Subscribe
    public void onPlayerDespawned(PlayerDespawned event) {
        VisibilityRules current = visibility;
        if (current != null) {
            current.reset(event.getPlayer());
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event) {
        VisibilityRules current = visibility;
        if (current != null) {
            current.reset(event.getNpc());
        }
    }

    /**
     * Sets the kinds' defaults from the settings and decides every loaded player and NPC.
     */
    private void decideAll() {
        VisibilityRules current = visibility;
        if (current == null) {
            return;
        }
        rules.applyKinds(current);
        Player local = client.getLocalPlayer();
        for (Player player : client.getPlayers()) {
            decide(player, local);
        }
        for (NPC npc : client.getNpcs()) {
            decide(npc, local);
        }
    }

    /**
     * Decides whether one actor's model and 2D elements are drawn.
     *
     * @param actor the player or NPC
     * @param local the local player, or null
     */
    private void decide(Actor actor, Player local) {
        VisibilityRules current = visibility;
        EntityHiderRules decide = rules;
        if (current == null || actor == null) {
            return;
        }
        if (actor instanceof Player player) {
            PlayerFacts facts = facts(player, local);
            current.setHidden(player, !decide.drawPlayer(facts, false), !decide.drawPlayer(facts, true));
        } else if (actor instanceof NPC npc) {
            NpcFacts facts = facts(npc, local, decide);
            current.setHidden(npc, !decide.drawNpc(facts, false), !decide.drawNpc(facts, true));
        }
    }

    /**
     * Reads what decides a player, stopping at the first relation that holds.
     *
     * @param player the player
     * @param local  the local player, or null
     * @return the facts
     */
    private static PlayerFacts facts(Player player, Player local) {
        boolean named = !player.getName().isEmpty();
        boolean isLocal = player == local;
        if (!named || isLocal) {
            return new PlayerFacts(isLocal, named, false, Relation.NONE);
        }
        boolean attacker = local != null && player.getInteracting() == local;
        Relation relation;
        if (player.isOnlineFriend()) {
            relation = Relation.FRIEND;
        } else if (player.isFriendsChatMember()) {
            relation = Relation.FRIENDS_CHAT;
        } else if (player.isClanMember()) {
            relation = Relation.CLAN;
        } else if (player.isIgnored()) {
            relation = Relation.IGNORED;
        } else {
            relation = Relation.NONE;
        }
        return new PlayerFacts(false, true, attacker, relation);
    }

    /**
     * Reads what decides an NPC; whether it is dying only while dead NPCs are hidden.
     *
     * @param npc   the NPC
     * @param local the local player, or null
     * @param rules the rules in force
     * @return the facts
     */
    private static NpcFacts facts(NPC npc, Player local, EntityHiderRules rules) {
        boolean othersPet = npc.isFollower() && !npc.isLocalFollower();
        boolean dying = rules.hidesDeadNpcs() && NpcUtil.isDying(npc);
        boolean attacker = local != null && npc.getInteracting() == local;
        return new NpcFacts(npc.getId(), othersPet, dying, attacker);
    }
}
