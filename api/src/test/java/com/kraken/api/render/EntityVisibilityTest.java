package com.kraken.api.render;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.internal.render.WireVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static com.kraken.api.internal.render.WireVisibility.HIDE_LOCAL_3D;
import static com.kraken.api.internal.render.WireVisibility.HIDE_NPCS_2D;
import static com.kraken.api.internal.render.WireVisibility.HIDE_NPCS_3D;
import static com.kraken.api.internal.render.WireVisibility.HIDE_PLAYERS_3D;
import static com.kraken.api.internal.render.WireVisibility.HIDE_PROJECTILES;
import static com.kraken.api.internal.render.WireVisibility.LAYER_2D;
import static com.kraken.api.internal.render.WireVisibility.LAYER_3D;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityVisibility} and {@link VisibilityRules} over the {@link GameFixture}: how every plugin's rules combine
 * into what the hook is sent, when they are sent, and what happens to closed rules and actors that have gone.
 */
class EntityVisibilityTest {

    private static final int BOTH = LAYER_3D | LAYER_2D;

    private GameFixture game;
    private Client client;
    private EntityVisibility visibility;
    private final List<WireVisibility> sent = new ArrayList<>();

    /**
     * Lays out the fixture and a client over it, with a hook that keeps what it is sent.
     *
     * @throws IOException if the bundled mapping can't be read
     */
    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        visibility = client.getEntityVisibility();
    }

    private WireVisibility last() {
        return sent.get(sent.size() - 1);
    }

    @Test
    void theSeedMappingCanHideActors() {
        assertTrue(visibility.isSupported());
        assertFalse(visibility.isAvailable(), "no hook yet");
        visibility.setHook(sent::add);
        assertTrue(visibility.isAvailable());
    }

    @Test
    void aHookIsSentEveryRuleAsItConnects() {
        VisibilityRules rules = visibility.createRules();
        rules.setNpcsHidden(true, false);
        visibility.flush();
        assertTrue(sent.isEmpty(), "nothing goes out without a hook");

        visibility.setHook(sent::add);
        assertEquals(1, sent.size());
        assertEquals(HIDE_NPCS_3D, last().classes());

        visibility.setHook(null);
        visibility.setHook(sent::add);
        assertEquals(2, sent.size(), "a reconnect is sent everything again");
        assertEquals(HIDE_NPCS_3D, last().classes());
    }

    @Test
    void rulesAreSentOnceAfterTheyChange() {
        visibility.setHook(sent::add);
        assertEquals(List.of(WireVisibility.NONE), sent, "a new hook hears that nothing is hidden");

        VisibilityRules rules = visibility.createRules();
        visibility.flush();
        assertEquals(1, sent.size(), "empty rules change nothing");

        rules.setProjectilesHidden(true);
        visibility.flush();
        visibility.flush();
        assertEquals(2, sent.size());
        assertEquals(HIDE_PROJECTILES, last().classes());

        rules.setProjectilesHidden(true);
        visibility.flush();
        assertEquals(2, sent.size(), "setting what is set already sends nothing");
    }

    @Test
    void everyPluginsKindsAreCombined() {
        visibility.setHook(sent::add);
        visibility.createRules().setPlayersHidden(true, false);
        VisibilityRules other = visibility.createRules();
        other.setNpcsHidden(false, true);
        other.setProjectilesHidden(true);
        other.setGraphicsObjectsHidden(Set.of(1590, 1591));
        visibility.createRules().setGraphicsObjectsHidden(Set.of(1591, 7));
        visibility.flush();

        assertEquals(HIDE_PLAYERS_3D | HIDE_NPCS_2D | HIDE_PROJECTILES, last().classes());
        assertArrayEquals(new int[]{7, 1590, 1591}, last().graphics(), "the ids of all, once each, in order");
        assertTrue(last().actors().isEmpty());
    }

    @Test
    void anActorDecidedApartIsSentByAddressWhereItDiffersFromItsKind() {
        visibility.setHook(sent::add);
        VisibilityRules rules = visibility.createRules();
        rules.setNpcsHidden(true, true);
        rules.setHidden(client.npc(5), false, false);
        rules.setHidden(client.player(9), false, false);
        visibility.flush();

        assertEquals(List.of(new WireVisibility.ActorRule(GameFixture.NPC, BOTH, 0)), last().actors(),
                "the goblin is shown against its kind; Bob is shown like other players, so needs no rule");

        rules.setHidden(client.npc(5), true, false);
        visibility.flush();
        assertEquals(List.of(new WireVisibility.ActorRule(GameFixture.NPC, BOTH, LAYER_3D)), last().actors());

        rules.reset(client.npc(5));
        visibility.flush();
        assertTrue(last().actors().isEmpty(), "back to its kind");
    }

    @Test
    void anActorIsHiddenIfAnyPluginHidesIt() {
        visibility.setHook(sent::add);
        VisibilityRules hidesNpcs = visibility.createRules();
        hidesNpcs.setNpcsHidden(true, false);
        VisibilityRules showsGoblin = visibility.createRules();
        showsGoblin.setHidden(client.npc(5), false, false);
        visibility.flush();
        assertTrue(last().actors().isEmpty(), "the other rules still hide it by its kind");

        VisibilityRules hidesBob = visibility.createRules();
        hidesBob.setHidden(client.player(9), false, true);
        visibility.flush();
        assertEquals(List.of(new WireVisibility.ActorRule(GameFixture.PLAYER + 9 * 0x10000L, BOTH, LAYER_2D)),
                last().actors());
    }

    @Test
    void theLocalPlayerFollowsItsOwnKind() {
        visibility.setHook(sent::add);
        VisibilityRules rules = visibility.createRules();
        rules.setLocalPlayerHidden(true, false);
        rules.setHidden(client.getLocalPlayer(), false, false);
        rules.setHidden(client.player(9), false, false);
        visibility.flush();

        assertEquals(HIDE_LOCAL_3D, last().classes());
        assertEquals(List.of(new WireVisibility.ActorRule(GameFixture.PLAYER, BOTH, 0)), last().actors(),
                "the local player is shown against its kind; Bob, another player, follows his");
    }

    @Test
    void closingRulesShowsAgainWhatTheyHid() {
        visibility.setHook(sent::add);
        VisibilityRules rules = visibility.createRules();
        rules.setNpcsHidden(true, true);
        rules.setHidden(client.player(9), true, true);
        visibility.flush();
        assertEquals(HIDE_NPCS_3D | HIDE_NPCS_2D, last().classes());

        rules.close();
        assertTrue(rules.isClosed());
        visibility.flush();
        assertEquals(WireVisibility.NONE, last());

        rules.setPlayersHidden(true, true);
        visibility.flush();
        assertEquals(WireVisibility.NONE, last(), "closed rules ignore changes");
    }

    @Test
    void decisionsAboutActorsThatHaveGoneAreDropped() {
        visibility.setHook(sent::add);
        VisibilityRules rules = visibility.createRules();
        rules.setHidden(client.npc(5), true, true);
        visibility.flush();
        assertEquals(1, last().actors().size());

        game.setNpcLoaded(5, false);
        IntStream.range(0, 50).forEach(i -> visibility.flush());
        assertTrue(last().actors().isEmpty(), "the goblin left, so its decision went with it");
    }

    @Test
    void aFailedSendIsTriedAgainAtTheNextFlush() {
        int[] attempts = {0};
        visibility.setHook(rules -> {
            if (attempts[0]++ < 2) {
                throw new java.io.UncheckedIOException(new java.io.IOException("broken pipe"));
            }
            sent.add(rules);
        });
        visibility.createRules().setNpcsHidden(true, false);
        visibility.flush();
        assertTrue(sent.isEmpty());
        visibility.flush();
        assertEquals(HIDE_NPCS_3D, last().classes());
    }
}
