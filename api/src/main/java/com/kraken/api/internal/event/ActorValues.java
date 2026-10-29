package com.kraken.api.internal.event;

import com.kraken.api.Client;
import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * One value per loaded actor (an animation, whether it is dead, an NPC's type), for the trackers that compare such a
 * value poll to poll. Values are keyed by slot: an NPC's index, or a player's index plus {@link #PLAYER_KEY}. An actor
 * the client lists but whose handle can't be pinned right now, or whose value can't be read, keeps the entry the last
 * read made, so a failed read never looks like an actor leaving and coming back.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ActorValues {

    /**
     * Added to a player's index for its key, past every NPC index.
     */
    static final int PLAYER_KEY = 0x10000;

    /**
     * An actor and the value last read from it.
     *
     * @param actor the actor's handle
     * @param value the value
     * @param <A>   the handle type
     * @param <V>   the value type
     */
    record Entry<A extends Actor, V>(A actor, V value) {

        /**
         * Whether this entry is for the same actor as an earlier one, so their values can be compared.
         *
         * @param earlier the entry the last read made for the same slot, or null
         * @return true if both entries hold the same handle
         */
        boolean sameActor(Entry<A, V> earlier) {
            return earlier != null && earlier.actor == actor;
        }
    }

    /**
     * Reads a value from every loaded NPC, then every loaded player, in the client's list order.
     *
     * @param client the client
     * @param read   reads an actor's value; null if the actor is gone or the value can't be read
     * @param before the last read, by key
     * @param <V>    the value type
     * @return the entries by key, NPCs first
     */
    static <V> Map<Integer, Entry<Actor, V>> actors(Client client, Function<Actor, V> read,
                                                   Map<Integer, Entry<Actor, V>> before) {
        Map<Integer, Entry<Actor, V>> now = new LinkedHashMap<>();
        read(now, before, client.npcIndices(), 0, client::npc, read);
        read(now, before, client.playerIndices(), PLAYER_KEY, client::player, read);
        return now;
    }

    /**
     * Reads a value from every loaded NPC, in the client's list order.
     *
     * @param client the client
     * @param read   reads an NPC's value; null if the NPC is gone or the value can't be read
     * @param before the last read, by key
     * @param <V>    the value type
     * @return the entries by key
     */
    static <V> Map<Integer, Entry<NPC, V>> npcs(Client client, Function<NPC, V> read,
                                               Map<Integer, Entry<NPC, V>> before) {
        Map<Integer, Entry<NPC, V>> now = new LinkedHashMap<>();
        read(now, before, client.npcIndices(), 0, client::npc, read);
        return now;
    }

    /**
     * Reads a value from each actor in an index list.
     *
     * @param into    the entries read so far, by key; gains one per listed actor that has a value
     * @param before  the last read, by key
     * @param indices the actors' indices
     * @param keyBase added to an index for its key
     * @param handle  the handle on the actor at an index now, or null if it can't be pinned
     * @param read    reads an actor's value, or null
     * @param <A>     the handle type
     * @param <V>     the value type
     */
    private static <A extends Actor, V> void read(Map<Integer, Entry<A, V>> into, Map<Integer, Entry<A, V>> before,
                                                  int[] indices, int keyBase, IntFunction<? extends A> handle,
                                                  Function<? super A, V> read) {
        for (int index : indices) {
            int key = keyBase + index;
            Entry<A, V> was = before.get(key);
            A actor = handle.apply(index);
            V value = actor == null ? null : read.apply(actor);
            if (value != null) {
                into.put(key, new Entry<>(actor, value));
            } else if (was != null && (actor == null || was.actor() == actor)) {
                into.put(key, was);
            }
        }
    }
}
