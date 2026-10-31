package com.kraken.client.worldhopper;

import com.kraken.api.World;
import com.kraken.api.WorldType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link WorldClient#parse}: the world list's binary layout, sorted by world number, and what a broken list does.
 */
class WorldClientTest {

    private static final Charset CP1252 = Charset.forName("windows-1252");

    /**
     * One world's entry as the list holds it.
     */
    private record Entry(int id, int flags, String address, String activity, int location, int players) {
    }

    private static byte[] list(List<Entry> entries) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeShort(entries.size());
        for (Entry entry : entries) {
            out.writeShort(entry.id());
            out.writeInt(entry.flags());
            out.write(entry.address().getBytes(CP1252));
            out.write(0);
            out.write(entry.activity().getBytes(CP1252));
            out.write(0);
            out.write(entry.location());
            out.writeShort(entry.players());
        }
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        DataOutputStream framed = new DataOutputStream(whole);
        framed.writeInt(body.size());
        framed.write(body.toByteArray());
        // The server pads the response after the list
        framed.write(new byte[16]);
        return whole.toByteArray();
    }

    @Test
    void decodesEachWorldAndSortsThemByNumber() throws IOException {
        String accented = "Pok" + (char) 0xe9 + "mon";
        byte[] body = list(List.of(
                new Entry(330, 1 | 1 << 7, "oldschool30.runescape.com", "2000 skill total", 7, 412),
                new Entry(301, 0, "oldschool1.runescape.com", "Trade - Free", 0, 1431),
                new Entry(579, 1 | 1 << 2 | 1 << 10, "oldschool279.runescape.com", accented, 12, -1)));

        List<World> worlds = WorldClient.parse(body);

        assertEquals(List.of(301, 330, 579), worlds.stream().map(World::getId).toList());
        World free = worlds.get(0);
        assertEquals(EnumSet.noneOf(WorldType.class), free.getTypes());
        assertEquals("oldschool1.runescape.com", free.getAddress());
        assertEquals("Trade - Free", free.getActivity());
        assertEquals(0, free.getLocation());
        assertEquals(1431, free.getPlayerCount());

        World total = worlds.get(1);
        assertEquals(EnumSet.of(WorldType.MEMBERS, WorldType.SKILL_TOTAL), total.getTypes());
        assertEquals(WorldRegion.GERMANY, WorldRegion.of(total.getLocation()));

        World pvp = worlds.get(2);
        assertEquals(EnumSet.of(WorldType.MEMBERS, WorldType.PVP, WorldType.HIGH_RISK), pvp.getTypes());
        assertEquals(accented, pvp.getActivity(), "Windows-1252 text");
        assertEquals(-1, pvp.getPlayerCount(), "offline");
        assertEquals(WorldRegion.SOUTH_AFRICA, WorldRegion.of(pvp.getLocation()));
    }

    @Test
    void anEmptyListHasNoWorlds() throws IOException {
        assertEquals(List.of(), WorldClient.parse(list(List.of())));
    }

    @Test
    void aListCutShortIsRefused() throws IOException {
        byte[] whole = list(List.of(new Entry(301, 0, "oldschool1.runescape.com", "-", 0, 10)));
        byte[] cut = java.util.Arrays.copyOf(whole, 20);
        assertThrows(WorldClient.WorldListException.class, () -> WorldClient.parse(cut), "size beyond the body");

        byte[] lying = whole.clone();
        lying[3] = 12;
        assertThrows(WorldClient.WorldListException.class, () -> WorldClient.parse(lying), "the entry runs past the size");
        assertThrows(WorldClient.WorldListException.class, () -> WorldClient.parse(new byte[2]), "no size");
    }

    @Test
    void regionsFollowTheListsLocationNumbers() {
        Map<Integer, WorldRegion> expected = Map.of(0, WorldRegion.UNITED_STATES, 1, WorldRegion.UNITED_KINGDOM,
                3, WorldRegion.AUSTRALIA, 7, WorldRegion.GERMANY, 8, WorldRegion.BRAZIL, 9, WorldRegion.JAPAN,
                10, WorldRegion.SINGAPORE, 12, WorldRegion.SOUTH_AFRICA);
        expected.forEach((location, region) -> assertEquals(region, WorldRegion.of(location)));
        assertEquals(null, WorldRegion.of(2), "a number no region has");
    }
}
