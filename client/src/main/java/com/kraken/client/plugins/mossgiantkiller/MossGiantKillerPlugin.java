package com.kraken.client.plugins.mossgiantkiller;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.Skill;
import com.kraken.api.action.ActionResult;
import com.kraken.api.action.Dialogue;
import com.kraken.api.action.Inventory;
import com.kraken.api.action.Magic;
import com.kraken.api.action.Movement;
import com.kraken.api.action.Prayer;
import com.kraken.api.action.Prayers;
import com.kraken.api.action.Spell;
import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.definition.ItemComposition;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.ActorDeath;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.api.item.Item;
import com.kraken.api.scene.Scene;
import com.kraken.api.scene.TileItem;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.ui.overlay.OverlayManager;
import lombok.Getter;
import lombok.extern.java.Log;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fights moss giants (or any NPC named in the settings) near where it was switched on, built on the interaction API:
 * it attacks with {@code npc.interact("Attack")}, eats with {@link Inventory}, picks up loot with
 * {@code TileItem.take()}, buries bones, prays with {@link Prayers}, keeps running with {@link Movement}, clicks
 * through level-up messages with {@link Dialogue}, and teleports away with {@link Magic} when the food runs out.
 *
 * <p>Each game tick it does at most one thing, the first of these that applies:
 * <ol>
 *   <li>eat, when hitpoints are at or below the setting, or cast the escape spell and stop when there's no food;</li>
 *   <li>click through "Click here to continue";</li>
 *   <li>keep fighting the NPC it's fighting, with Protect from Melee on if wanted;</li>
 *   <li>pick up the nearest wanted ground item;</li>
 *   <li>bury bones;</li>
 *   <li>attack the nearest NPC with the name, one attacking you first, that isn't fighting someone else;</li>
 *   <li>walk back to where it started, when it has wandered beyond the fight radius.</li>
 * </ol>
 * After an attack, a pick-up or a walk it waits a few ticks for the game to start it before choosing again. Running
 * is switched on alongside the rest.
 *
 * <p>Every action it sends goes to the game's servers as a real click would; read the disclaimer in the README.
 *
 * <p>Threading: the state is only touched on the client thread (the event handlers, the {@code clientThread} tasks
 * and the overlay's render), so it needs no locking.
 */
@Log(topic = "kraken")
@PluginDescriptor(
        name = "Moss Giant Killer",
        description = "Fights moss giants nearby, eating, looting and burying bones as it goes",
        tags = {"combat", "moss giant", "auto", "eat", "loot", "example"},
        enabledByDefault = false
)
public class MossGiantKillerPlugin extends Plugin {

    /**
     * Ticks between two bites: eating takes three.
     */
    static final int EAT_DELAY_TICKS = 3;

    /**
     * Ticks to wait after an attack for the fight to start: time to walk up to the NPC.
     */
    static final int ATTACK_WAIT_TICKS = 4;

    /**
     * Ticks to wait after a pick-up for the player to walk to the item.
     */
    static final int LOOT_WAIT_TICKS = 3;

    /**
     * Ticks to wait after a kill for its drop to land.
     */
    static final int DROP_WAIT_TICKS = 2;

    /**
     * Ticks to wait after burying a bone.
     */
    static final int BURY_WAIT_TICKS = 2;

    /**
     * Ticks to wait after setting off back to where the plugin started.
     */
    static final int WALK_WAIT_TICKS = 5;

    /**
     * The Prayer level Protect from Melee needs.
     */
    static final int PROTECT_FROM_MELEE_LEVEL = 43;

    /**
     * Ticks between tries at switching running on, since it fails without run energy: about a minute.
     */
    static final int RUN_RETRY_TICKS = 100;

    /**
     * The inventory option that eats an item.
     */
    static final String EAT = "Eat";

    /**
     * The inventory option that buries an item.
     */
    static final String BURY = "Bury";

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private MossGiantKillerConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private MossGiantKillerOverlay overlay;

    @Inject
    private Inventory inventory;

    @Inject
    private Magic magic;

    @Inject
    private Prayers prayers;

    @Inject
    private Movement movement;

    @Inject
    private Dialogue dialogue;

    /**
     * What the plugin is doing, for the overlay.
     */
    @Getter
    private State state = State.STARTING;

    /**
     * The NPCs it has killed since it started.
     */
    @Getter
    private int kills;

    /**
     * The inventory slots holding food, as counted on the last tick.
     */
    @Getter
    private int foodLeft;

    /**
     * Where the plugin started: it fights within the radius of this tile.
     */
    private WorldPoint home;

    /**
     * The NPC it last attacked or fought, whose death counts as a kill.
     */
    private NPC target;

    /**
     * The tick before which no new attack, pick-up, burial or walk is started.
     */
    private int busyUntil;

    /**
     * The tick before which it doesn't eat again.
     */
    private int eatReadyAt;

    /**
     * The tick it last tried switching running on.
     */
    private int lastRunAttempt;

    /**
     * Whether it switched Protect from Melee on, so it only switches off what it switched on.
     */
    private boolean praying;

    @Provides
    MossGiantKillerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(MossGiantKillerConfig.class);
    }

    @Override
    protected void startUp() {
        clientThread.invoke(this::reset);
        updateOverlay();
        log.info("Moss Giant Killer: fighting " + config.npcName() + " within " + config.radius()
                + " tiles of where you stand");
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        clientThread.invoke(() -> {
            stopPraying();
            log.info("Moss Giant Killer: stopped after " + kills + " kills");
        });
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (MossGiantKillerConfig.GROUP.equals(event.getGroup()) && "showOverlay".equals(event.getKey())) {
            updateOverlay();
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGIN_SCREEN) {
            reset();
        }
    }

    @Subscribe
    public void onActorDeath(ActorDeath event) {
        if (target != null && event.getActor() == target) {
            kills++;
            target = null;
            busyUntil = client.getTickCount() + DROP_WAIT_TICKS;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        Player me = client.getLocalPlayer();
        if (state == State.STOPPED || me == null || !client.isLoggedIn()) {
            return;
        }
        int tick = client.getTickCount();
        if (home == null) {
            home = me.getWorldLocation();
            if (home == null) {
                return;
            }
        }
        foodLeft = countHeld(EAT);

        if (needsFood() && tick >= eatReadyAt) {
            eat(tick);
            return;
        }
        if (config.continueDialogues() && dialogue.canContinue()) {
            act(dialogue.clickContinue(), "continue the dialogue", state);
            return;
        }

        if (config.keepRunning() && !movement.isRunning() && tick - lastRunAttempt >= RUN_RETRY_TICKS) {
            lastRunAttempt = tick;
            movement.setRunning(true);
        }

        NPC opponent = opponent(me);
        if (opponent != null) {
            target = opponent;
            state = State.FIGHTING;
            if (config.protectFromMelee() && client.getRealSkillLevel(Skill.PRAYER) >= PROTECT_FROM_MELEE_LEVEL
                    && client.getBoostedSkillLevel(Skill.PRAYER) > 0) {
                praying = prayers.activate(Prayer.PROTECT_FROM_MELEE).isSent() || praying;
            }
            return;
        }
        stopPraying();
        if (tick < busyUntil) {
            return;
        }

        TileItem loot = nearestLoot(me);
        if (loot != null) {
            if (act(loot.take(), "pick up " + loot.getName(), State.LOOTING)) {
                busyUntil = tick + LOOT_WAIT_TICKS;
            }
            return;
        }
        int bones = config.buryBones() ? firstHeld(BURY) : -1;
        if (bones >= 0) {
            if (act(inventory.interact(bones, BURY), "bury item " + bones, State.BURYING)) {
                busyUntil = tick + BURY_WAIT_TICKS;
            }
            return;
        }
        NPC next = nextOpponent(me);
        if (next != null) {
            if (act(next.interact("Attack"), "attack " + next.getName(), State.ATTACKING)) {
                target = next;
                busyUntil = tick + ATTACK_WAIT_TICKS;
            }
            return;
        }
        walkHome(me, tick);
    }

    /**
     * Eats the first food held, or gets away when there is none.
     *
     * @param tick the tick now
     */
    private void eat(int tick) {
        int food = firstHeld(EAT);
        if (food >= 0) {
            if (act(inventory.interact(food, EAT), "eat item " + food, State.EATING)) {
                eatReadyAt = tick + EAT_DELAY_TICKS;
            }
            return;
        }
        if (config.teleportWhenOutOfFood()) {
            Spell spell = config.escapeSpell();
            ActionResult result = magic.cast(spell);
            log.warning("Moss Giant Killer: out of food, cast " + spell + ": " + result);
        } else {
            log.warning("Moss Giant Killer: out of food");
        }
        stop(State.STOPPED);
    }

    /**
     * Walks back to where the plugin started when it has wandered beyond the fight radius, or waits there.
     *
     * @param me   the local player
     * @param tick the tick now
     */
    private void walkHome(Player me, int tick) {
        WorldPoint here = me.getWorldLocation();
        if (here == null || here.distanceTo(home) <= config.radius()) {
            state = State.SEARCHING;
            return;
        }
        if (here.getPlane() != home.getPlane()) {
            log.warning("Moss Giant Killer: you left the plane it started on; stopping");
            stop(State.STOPPED);
            return;
        }
        ActionResult result = movement.walk(home);
        if (result == ActionResult.OUT_OF_SCENE) {
            log.warning("Moss Giant Killer: " + home + " is too far away to walk back to; stopping");
            stop(State.STOPPED);
        } else if (act(result, "walk back to " + home, State.WALKING)) {
            busyUntil = tick + WALK_WAIT_TICKS;
        }
    }

    /**
     * Whether hitpoints are at or below the setting.
     *
     * @return true when it should eat
     */
    private boolean needsFood() {
        int hitpoints = client.getBoostedSkillLevel(Skill.HITPOINTS);
        return hitpoints > 0 && hitpoints <= config.eatAt();
    }

    /**
     * The NPC the local player is fighting.
     *
     * @param me the local player
     * @return the NPC, or null when it isn't fighting one that's alive
     */
    private NPC opponent(Player me) {
        Actor fighting = me.getInteracting();
        return fighting instanceof NPC npc && !npc.isDead() ? npc : null;
    }

    /**
     * The NPC to attack next: one with the name, alive, within the fight radius of where the plugin started, and not
     * fighting anyone else. One attacking the local player comes first, then the nearest.
     *
     * @param me the local player
     * @return the NPC, or null if there is none
     */
    private NPC nextOpponent(Player me) {
        String name = config.npcName().trim();
        WorldPoint here = me.getWorldLocation();
        if (name.isEmpty() || here == null) {
            return null;
        }
        return client.getNpcs().stream()
                .filter(npc -> name.equalsIgnoreCase(npc.getName()) && !npc.isDead())
                .filter(npc -> npc.getInteracting() == null || npc.getInteracting() == me)
                .filter(npc -> npc.getWorldLocation() != null
                        && npc.getWorldLocation().distanceTo(home) <= config.radius())
                .min(Comparator.<NPC>comparingInt(npc -> npc.getInteracting() == me ? 0 : 1)
                        .thenComparingInt(npc -> npc.getWorldLocation().distanceTo(here)))
                .orElse(null);
    }

    /**
     * The nearest ground item to pick up: one whose name is in the loot setting, within the pick-up radius, that
     * fits in the inventory (a stack the inventory already holds fits when it's full).
     *
     * @param me the local player
     * @return the item, or null if there is none
     */
    private TileItem nearestLoot(Player me) {
        Set<String> wanted = names(config.loot());
        Scene scene = client.getScene();
        WorldPoint here = me.getWorldLocation();
        if (wanted.isEmpty() || scene == null || here == null) {
            return null;
        }
        boolean full = inventory.isFull();
        return scene.getGroundItems().stream()
                .filter(item -> item.getTile().getWorldLocation().distanceTo(here) <= config.lootRadius())
                .filter(item -> item.getName() != null && wanted.contains(item.getName().toLowerCase(Locale.ROOT)))
                .filter(item -> !full || stacksOnto(item.getId()))
                .min(Comparator.comparingInt(item -> item.getTile().getWorldLocation().distanceTo(here)))
                .orElse(null);
    }

    /**
     * Whether an item would join a stack the inventory already holds.
     *
     * @param itemId the item's id
     * @return true for a stackable item already held
     */
    private boolean stacksOnto(int itemId) {
        ItemComposition type = client.getItemDefinition(itemId);
        return type != null && type.isStackable() && inventory.contains(itemId);
    }

    /**
     * The first item held that has an inventory option, such as Eat or Bury.
     *
     * @param option the option
     * @return the item's id, or -1 if no item held has it
     */
    private int firstHeld(String option) {
        for (Item item : inventory.getItems()) {
            if (item.getId() >= 0 && hasOption(item.getId(), option)) {
                return item.getId();
            }
        }
        return -1;
    }

    /**
     * How many inventory slots hold an item with an inventory option.
     *
     * @param option the option
     * @return the slots
     */
    private int countHeld(String option) {
        return (int) Arrays.stream(inventory.getItems())
                .filter(item -> item.getId() >= 0 && hasOption(item.getId(), option)).count();
    }

    private boolean hasOption(int itemId, String option) {
        ItemComposition type = client.getItemDefinition(itemId);
        String[] actions = type == null ? null : type.getInventoryActions();
        return actions != null && Arrays.stream(actions).anyMatch(option::equalsIgnoreCase);
    }

    /**
     * Notes an action's result: the new state when it was sent, a console line when it wasn't.
     *
     * @param result what became of the action
     * @param what   the action, for the console
     * @param next   the state to be in once it's sent
     * @return true if it was sent
     */
    private boolean act(ActionResult result, String what, State next) {
        if (result.isSent()) {
            state = next;
            return true;
        }
        log.info("Moss Giant Killer: could not " + what + ": " + result);
        return false;
    }

    private void stop(State why) {
        state = why;
        stopPraying();
    }

    private void stopPraying() {
        if (praying && prayers.deactivate(Prayer.PROTECT_FROM_MELEE).isSent()) {
            praying = false;
        }
    }

    private void reset() {
        state = State.STARTING;
        home = null;
        target = null;
        busyUntil = 0;
        eatReadyAt = 0;
        lastRunAttempt = -RUN_RETRY_TICKS;
        kills = 0;
    }

    private void updateOverlay() {
        if (config.showOverlay()) {
            overlayManager.add(overlay);
        } else {
            overlayManager.remove(overlay);
        }
    }

    /**
     * The names in a comma-separated setting.
     *
     * @param setting the setting's text
     * @return the names, trimmed and lower-cased; empty ones left out
     */
    static Set<String> names(String setting) {
        return Arrays.stream(setting.split(",")).map(name -> name.trim().toLowerCase(Locale.ROOT))
                .filter(name -> !name.isEmpty()).collect(Collectors.toSet());
    }

    /**
     * What the plugin is doing.
     */
    enum State {
        STARTING("Starting"),
        SEARCHING("Looking for a fight"),
        ATTACKING("Attacking"),
        FIGHTING("Fighting"),
        EATING("Eating"),
        LOOTING("Picking up loot"),
        BURYING("Burying bones"),
        WALKING("Walking back"),
        STOPPED("Stopped");

        private final String label;

        State(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
