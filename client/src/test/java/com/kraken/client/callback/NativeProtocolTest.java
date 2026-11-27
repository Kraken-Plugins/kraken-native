package com.kraken.client.callback;

import com.kraken.api.internal.ScriptComponents;
import com.kraken.api.internal.draw.WireDrawList;
import com.kraken.api.internal.draw.WireSprite;
import com.kraken.api.internal.event.NativeEvent;
import com.kraken.api.internal.event.ScriptRequest;
import com.kraken.api.internal.event.ScriptRun;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.internal.menu.MenuClick;
import com.kraken.api.internal.menu.WireRule;
import com.kraken.api.internal.render.WireVisibility;
import com.kraken.api.internal.widget.ComponentOp;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.menu.MenuRule;
import com.kraken.api.menu.ShiftState;
import com.kraken.api.menu.TextMatch;
import com.kraken.client.input.KeyHook;
import com.kraken.client.input.KeyReport;
import com.kraken.client.callback.NativeProtocol.Drawing;
import com.kraken.client.callback.NativeProtocol.Plan;
import com.kraken.client.callback.NativeProtocol.Slot;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire format {@link NativeProtocol} shares with {@code kraken_hook.dll}: the handshake and commands it builds and
 * the messages it parses, field for field and op for op.
 */
class NativeProtocolTest {

    @Test
    void handshakeHasMagicCountAndEachSlotInOrder() {
        List<Slot> slots = List.of(
                new Slot(0xc44d0L, -1, 0, 1, List.of()),
                new Slot(0xc7b20L, 40, 0, 4, List.of(
                        new Plan(NativeEvent.RDX, NativeProtocol.OP_I32),
                        new Plan(NativeEvent.R8, NativeProtocol.OP_CSTR),
                        new Plan(NativeEvent.R9, NativeProtocol.OP_I32))));
        Drawing drawing = new Drawing(0xe98c18L, 0x918950L, 0xa555c0L);
        byte[] scripts = {9, 8, 7};
        byte[] menu = {6, 5};
        byte[] keys = {4};
        byte[] visibility = {3, 2};
        byte[] components = {1};
        byte[] frames = {0, 11};
        byte[] draw = {12, 13, 14};
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.handshake(slots, drawing, scripts, menu, keys, visibility,
                components, frames, draw)).order(ByteOrder.LITTLE_ENDIAN);

        byte[] magic = new byte[4];
        buf.get(magic);
        assertArrayEquals("KRKA".getBytes(StandardCharsets.US_ASCII), magic);
        assertEquals(2, buf.getShort(), "slot count");

        assertEquals(0xc44d0L, buf.getLong());
        assertEquals(-1, buf.getInt(), "idArg slot carries no fixed id");
        assertEquals(0, buf.get(), "idArg register");
        assertEquals(1, buf.get(), "args");
        assertEquals(0, buf.get(), "empty plan");

        assertEquals(0xc7b20L, buf.getLong());
        assertEquals(40, buf.getInt(), "fixed id");
        assertEquals(0, buf.get());
        assertEquals(4, buf.get());
        assertEquals(3, buf.get(), "plan length");
        assertEquals(NativeEvent.RDX, buf.get());
        assertEquals(NativeProtocol.OP_I32, buf.get());
        assertEquals(NativeEvent.R8, buf.get());
        assertEquals(NativeProtocol.OP_CSTR, buf.get());
        assertEquals(NativeEvent.R9, buf.get());
        assertEquals(NativeProtocol.OP_I32, buf.get());
        assertEquals(0xe98c18L, buf.getLong(), "the client pointer's slot");
        assertEquals(0x918950L, buf.getLong(), "the pump");
        assertEquals(0xa555c0L, buf.getLong(), "the present");
        assertEquals(3, buf.getShort(), "the script block's length");
        assertEquals(9, buf.get());
        assertEquals(8, buf.get());
        assertEquals(7, buf.get());
        assertEquals(2, buf.getShort(), "the menu block's length");
        assertEquals(6, buf.get());
        assertEquals(5, buf.get());
        assertEquals(1, buf.getShort(), "the key block's length");
        assertEquals(4, buf.get());
        assertEquals(2, buf.getShort(), "the visibility block's length");
        assertEquals(3, buf.get());
        assertEquals(2, buf.get());
        assertEquals(1, buf.getShort(), "the component block's length");
        assertEquals(1, buf.get());
        assertEquals(2, buf.getShort(), "the frame block's length");
        assertEquals(0, buf.get());
        assertEquals(11, buf.get());
        assertEquals(3, buf.getShort(), "the draw block's length");
        assertEquals(12, buf.get());
        assertEquals(13, buf.get());
        assertEquals(14, buf.get());
        assertEquals(0, buf.remaining(), "no trailing bytes");
    }

    @Test
    void aHandshakeWithoutDrawingOrScriptsEndsInZeros() {
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.handshake(List.of(), Drawing.NONE, new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], new byte[0], new byte[0])).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        assertEquals(0, buf.getShort());
        assertEquals(0, buf.getLong());
        assertEquals(0, buf.getLong());
        assertEquals(0, buf.getLong());
        assertEquals(0, buf.getShort(), "no script block");
        assertEquals(0, buf.getShort(), "no menu block");
        assertEquals(0, buf.getShort(), "no key block");
        assertEquals(0, buf.getShort(), "no visibility block");
        assertEquals(0, buf.getShort(), "no component block");
        assertEquals(0, buf.getShort(), "no frame block");
        assertEquals(0, buf.getShort(), "no draw block");
        assertEquals(0, buf.remaining());
    }

    @Test
    void theMenuBlockFollowsTheMappingInTheDllsOrder() throws IOException {
        Mapping mapping = Mapping.bundled("241.3");
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.menuLayout(mapping)).order(ByteOrder.LITTLE_ENDIAN);
        for (String function : new String[]{"minimenuViewRebuild", "minimenuRun", "minimenuNodeDestroy",
                "minimenuNodeReserve", "minimenuTextAssign"}) {
            assertEquals(mapping.function(function), buf.getLong(), function);
        }
        for (String key : new String[]{"Client.minimenu", "Minimenu.rows", "Minimenu.open", "Minimenu.clicked"}) {
            assertEquals(mapping.offset(key), buf.getInt(), key);
        }
        for (String key : new String[]{"minimenu.node.list", "minimenu.node.index", "minimenu.node.children",
                "minimenu.node.size", "minimenu.list.entries"}) {
            assertEquals(mapping.container(key), buf.getInt(), key);
        }
        for (String key : new String[]{"MinimenuEntry.size", "MinimenuEntry.option", "MinimenuEntry.target",
                "MinimenuEntry.typeId", "MinimenuEntry.subjectIndex", "MinimenuEntry.opIndex", "MinimenuEntry.level",
                "MinimenuEntry.x", "MinimenuEntry.z", "MinimenuEntry.subjectType"}) {
            assertEquals(mapping.offset(key), buf.getInt(), key);
        }
        for (String kind : new String[]{"TILE", "NPC", "LOC", "OBJ", "PLAYER", "COMPONENT"}) {
            assertEquals(mapping.enumValue("minimenuSubject", kind, -1), buf.get(), kind);
        }
        assertEquals(mapping.enumValue("minimenuOp", "EXAMINE", 0), buf.getInt());
        assertEquals(mapping.enumValue("minimenuOp", "TARGET", 0), buf.getInt());
        for (String key : new String[]{"string.heapFlagByte", "string.heapFlagMask", "string.ssoCapacity"}) {
            assertEquals(mapping.container(key), buf.getInt(), key);
        }
        assertEquals(0, buf.remaining(), "142 bytes, as the DLL reads them");

        ByteBuffer keys = ByteBuffer.wrap(NativeProtocol.keyLayout(mapping)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(mapping.function("renderViewWndProc"), keys.getLong());
        assertEquals(0, keys.remaining());
    }

    @Test
    void menuRulesAreFramedFieldForField() {
        WireRule.Match trigger = new WireRule.Match(Set.of(WireRule.Match.Field.KIND, WireRule.Match.Field.OPTION), 2,
                0, 0, 0, 0, 0, 0, 0, TextMatch.exact("Talk-to"), null, false);
        WireRule.Match swapped = new WireRule.Match(Set.of(WireRule.Match.Field.OPTION), 0, 0, 0, 0, 0, 0, 0, 0,
                TextMatch.contains("bank"), null, false);
        WireRule swap = new WireRule(7, WireRule.Type.SWAP, ShiftState.HELD, false, false, trigger,
                EnumSet.noneOf(MenuRule.Edit.Action.class), "", "", swapped, true, false, true, false, null, null);
        WireRule.Match row = new WireRule.Match(EnumSet.of(WireRule.Match.Field.TYPE_ID, WireRule.Match.Field.INDEX,
                WireRule.Match.Field.OP, WireRule.Match.Field.TILE, WireRule.Match.Field.GROUP), 7, 0x950000, 3, 2,
                149, 1, 3200, 3201, null, TextMatch.wildcard("*coins"), true);
        WireRule edit = new WireRule(8, WireRule.Type.EDIT, ShiftState.NOT_HELD, true, true, row,
                EnumSet.of(MenuRule.Edit.Action.HIDE, MenuRule.Edit.Action.SET_TARGET, MenuRule.Edit.Action.REPORT),
                "", "<col=ff>Coins", null, false, false, false, false, null, null);
        WireRule add = new WireRule(9, WireRule.Type.ADD, ShiftState.ANY, true, false, trigger,
                EnumSet.noneOf(MenuRule.Edit.Action.class), "", "", null, false, false, false, false,
                MenuRule.Add.Position.BELOW, new WireRule.Row(20, "Swap", "T", List.of(
                        new WireRule.Row(21, "A", "", List.of()), new WireRule.Row(22, "B", "", List.of()))));
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.menuRules(NativeProtocol.MENU_UPSERT, List.of(swap, edit, add)))
                .order(ByteOrder.LITTLE_ENDIAN);
        int length = buf.getInt();
        assertEquals(buf.remaining(), length, "the length counts the bytes after it");
        assertEquals(NativeProtocol.COMMAND_MENU, buf.get());
        assertEquals(NativeProtocol.MENU_UPSERT, buf.get());
        assertEquals(3, buf.getShort());

        assertEquals(7, buf.getInt());
        assertEquals(NativeProtocol.RULE_SWAP, buf.get());
        assertEquals(NativeProtocol.RULE_SHIFT_DOWN, buf.get());
        assertEquals(NativeProtocol.MATCH_KIND | NativeProtocol.MATCH_OPTION, buf.getShort());
        assertEquals(2, buf.get(), "kind");
        buf.position(buf.position() + 7 * 4);
        assertEquals(TextMatch.Mode.EXACT.ordinal() + 1, buf.get());
        assertEquals(7, buf.get());
        byte[] text = new byte[7];
        buf.get(text);
        assertEquals("talk-to", new String(text, StandardCharsets.UTF_8));
        assertEquals(NativeProtocol.TEXT_ANY, buf.get());
        assertEquals(0, buf.get());
        assertEquals(NativeProtocol.SWAP_SAME_TARGET | NativeProtocol.SWAP_BELOW, buf.get());
        assertEquals(NativeProtocol.MATCH_OPTION, buf.getShort());
        buf.position(buf.position() + 1 + 7 * 4);
        assertEquals(TextMatch.Mode.CONTAINS.ordinal() + 1, buf.get());
        assertEquals(4, buf.get());
        buf.position(buf.position() + 4 + 2);

        assertEquals(8, buf.getInt());
        assertEquals(NativeProtocol.RULE_EDIT, buf.get());
        assertEquals(NativeProtocol.RULE_SHIFT_UP | NativeProtocol.RULE_SCOPED | NativeProtocol.RULE_FINAL, buf.get());
        assertEquals(NativeProtocol.MATCH_TYPE_ID | NativeProtocol.MATCH_INDEX | NativeProtocol.MATCH_OP
                | NativeProtocol.MATCH_TILE | NativeProtocol.MATCH_GROUP | NativeProtocol.MATCH_SUB_ROWS, buf.getShort());
        assertEquals(7, buf.get());
        assertEquals(0x950000, buf.getInt());
        assertEquals(3, buf.getInt());
        assertEquals(2, buf.getInt());
        assertEquals(149, buf.getInt());
        assertEquals(1, buf.getInt());
        assertEquals(3200, buf.getInt());
        assertEquals(3201, buf.getInt());
        assertEquals(NativeProtocol.TEXT_ANY, buf.get());
        assertEquals(0, buf.get());
        assertEquals(TextMatch.Mode.WILDCARD.ordinal() + 1, buf.get());
        assertEquals(6, buf.get());
        buf.position(buf.position() + 6);
        assertEquals(NativeProtocol.EDIT_HIDE | NativeProtocol.EDIT_SET_TARGET | NativeProtocol.EDIT_REPORT, buf.get());
        assertEquals(0, buf.getShort(), "no option text");
        assertEquals(13, buf.getShort());
        buf.position(buf.position() + 13);

        assertEquals(9, buf.getInt());
        assertEquals(NativeProtocol.RULE_ADD, buf.get());
        assertEquals(NativeProtocol.RULE_SCOPED, buf.get());
        buf.position(buf.position() + 2 + 1 + 7 * 4 + 2 + 7 + 2);
        assertEquals(MenuRule.Add.Position.BELOW.ordinal(), buf.get());
        assertEquals(20, buf.getInt());
        assertEquals(4, buf.getShort());
        buf.position(buf.position() + 4);
        assertEquals(1, buf.getShort());
        buf.position(buf.position() + 1);
        assertEquals(2, buf.get(), "two sub rows");
        assertEquals(21, buf.getInt());
        buf.position(buf.position() + 2 + 1 + 2);
        assertEquals(0, buf.get(), "a sub row has no sub rows");
        assertEquals(22, buf.getInt());
        buf.position(buf.position() + 2 + 1 + 2 + 1);
        assertEquals(0, buf.remaining());

        ByteBuffer remove = ByteBuffer.wrap(NativeProtocol.menuRemove(new int[]{7, 9})).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(12, remove.getInt());
        assertEquals(NativeProtocol.COMMAND_MENU, remove.get());
        assertEquals(NativeProtocol.MENU_REMOVE, remove.get());
        assertEquals(2, remove.getShort());
        assertEquals(7, remove.getInt());
        assertEquals(9, remove.getInt());
    }

    @Test
    void keyCommandsAreFramed() {
        List<KeyHook.Mode> modes = List.of(new KeyHook.Mode(false, List.of()),
                new KeyHook.Mode(true, List.of(new KeyHook.Rule(0x57, 0, 0, 0x26, KeyHook.STAY),
                        new KeyHook.Rule(0x0D, 0, 0, 0x0D, 2), new KeyHook.Rule(0x1B, KeyHook.SHIFT, 7, KeyHook.BLOCK, 0))));
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.keysTable(modes)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(3 + 3 + 3 + 3 * 7, buf.getInt());
        assertEquals(NativeProtocol.COMMAND_KEYS, buf.get());
        assertEquals(NativeProtocol.KEYS_TABLE, buf.get());
        assertEquals(2, buf.get());
        assertEquals(0, buf.get());
        assertEquals(0, buf.getShort());
        assertEquals(NativeProtocol.KEY_MODE_BLOCK_TYPING, buf.get());
        assertEquals(3, buf.getShort());
        assertEquals(0x57, buf.getShort());
        assertEquals(0, buf.get());
        assertEquals(0, buf.get());
        assertEquals(0x26, buf.getShort());
        assertEquals((byte) KeyHook.STAY, buf.get());
        buf.position(buf.position() + 7);
        assertEquals(0x1B, buf.getShort());
        assertEquals(KeyHook.SHIFT, buf.get());
        assertEquals(7, buf.get());
        assertEquals((short) KeyHook.BLOCK, buf.getShort());
        assertEquals(0, buf.get());
        assertEquals(0, buf.remaining());

        ByteBuffer mode = ByteBuffer.wrap(NativeProtocol.keysMode(2)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(3, mode.getInt());
        assertEquals(NativeProtocol.COMMAND_KEYS, mode.get());
        assertEquals(NativeProtocol.KEYS_MODE, mode.get());
        assertEquals(2, mode.get());
        ByteBuffer report = ByteBuffer.wrap(NativeProtocol.keysReport(true)).order(ByteOrder.LITTLE_ENDIAN);
        report.position(4);
        assertEquals(NativeProtocol.COMMAND_KEYS, report.get());
        assertEquals(NativeProtocol.KEYS_REPORT, report.get());
        assertEquals(1, report.get());
    }

    @Test
    void menuClicksAndKeyReportsAreReadFromTheRegisters() {
        NativeEvent rowClick = new NativeEvent(null, NativeProtocol.MENU_CLICK_EVENT_ID,
                new long[]{0, 21, 20, 15L | 40L << 32}, Map.of(), 1);
        assertEquals(new MenuClick(false, 21, 20, 15, 40), NativeProtocol.parseMenuClick(rowClick));
        NativeEvent ruleClick = new NativeEvent(null, NativeProtocol.MENU_CLICK_EVENT_ID,
                new long[]{1, 8, 0xffffffffL, 0}, Map.of(), 2);
        assertEquals(new MenuClick(true, 8, -1, 0, 0), NativeProtocol.parseMenuClick(ruleClick));

        NativeEvent remapped = new NativeEvent(null, NativeProtocol.KEY_EVENT_ID,
                new long[]{NativeProtocol.WM_KEYDOWN, 0x57, 1L << 16 | 0x26, 1 | 1 << 8}, Map.of(), 3);
        assertEquals(new KeyReport(KeyReport.Kind.PRESSED, KeyEvent.VK_W, (char) 0, KeyReport.Action.REMAPPED,
                KeyEvent.VK_UP, 1, 1), NativeProtocol.parseKey(remapped));
        NativeEvent typed = new NativeEvent(null, NativeProtocol.KEY_EVENT_ID,
                new long[]{NativeProtocol.WM_CHAR, 'w', 2L << 16, 1 | 2 << 8}, Map.of(), 4);
        assertEquals(new KeyReport(KeyReport.Kind.TYPED, 0, 'w', KeyReport.Action.BLOCKED, 0, 1, 2),
                NativeProtocol.parseKey(typed));
        NativeEvent released = new NativeEvent(null, NativeProtocol.KEY_EVENT_ID,
                new long[]{NativeProtocol.WM_SYSKEYUP, 0x0D, 0, 0}, Map.of(), 5);
        assertEquals(KeyReport.Kind.RELEASED, NativeProtocol.parseKey(released).kind());
        assertEquals(KeyEvent.VK_ENTER, NativeProtocol.parseKey(released).keyCode());
    }

    @Test
    void theScriptBlockFollowsTheMappingInTheDllsOrder() throws IOException {
        Mapping mapping = Mapping.bundled("241.3");
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.scriptLayout(mapping)).order(ByteOrder.LITTLE_ENDIAN);

        for (String function : new String[]{"executeScript", "executeScriptInner", "scriptLookup", "scriptSwapLocals"}) {
            assertEquals(mapping.function(function), buf.getLong(), function);
        }
        assertEquals(mapping.global("scriptRunner"), buf.getLong(), "the runner");
        for (String key : new String[]{"ScriptRunner.intStack", "ScriptRunner.intStackSize", "ScriptRunner.objStack",
                "ScriptRunner.objStackSize", "ScriptRunner.frameCount", "ScriptFrame.script"}) {
            assertEquals(mapping.offset(key), buf.getInt(), key);
        }
        assertEquals(mapping.container("shared_ptr.ptr"), buf.getInt(), "a shared_ptr's pointer");
        for (String key : new String[]{"ClientScript.id", "VarValue.size", "VarValue.type"}) {
            assertEquals(mapping.offset(key), buf.getInt(), key);
        }
        for (String kind : new String[]{"INT", "STRING", "LONG"}) {
            assertEquals(mapping.enumValue("varValueType", kind, -1), buf.get(), kind);
        }
        for (String key : new String[]{"HookReq.argCount", "HookReq.args", "HookReq.source", "HookReq.target",
                "HookReq.op", "HookReq.subOp", "HookReq.mouseX", "HookReq.mouseY", "HookReq.keyCode",
                "HookReq.keyChar", "HookReq.opbase", "IfType.packedId", "IfType.subId"}) {
            assertEquals(mapping.offset(key), buf.getInt(), key);
        }
        for (String key : new String[]{"string.heapFlagByte", "string.heapFlagMask", "string.ssoCapacity"}) {
            assertEquals(mapping.container(key), buf.getInt(), key);
        }
        for (String key : new String[]{"ScriptRunner.activeComponent", "ScriptRunner.dotComponent"}) {
            assertEquals(mapping.offsetOr(key, NativeProtocol.NO_OFFSET), buf.getInt(), key);
        }
        assertEquals(0, buf.remaining(), "155 bytes, as the DLL reads them");
    }

    @Test
    void theVisibilityBlockFollowsTheMappingInTheDllsOrder() throws IOException {
        Mapping mapping = Mapping.bundled("241.3");
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.visibilityLayout(mapping)).order(ByteOrder.LITTLE_ENDIAN);

        for (String key : new String[]{"Client.topLevelWorld", "Client.worlds.map", "Client.localPlayerIndex",
                "World.players", "World.npcs", "World.graphicsObjects", "Client.projectiles", "Client.keyedProjectiles",
                "Actor.hidden", "Actor.render2D", "Projectile.hidden", "KeyedProjectile.hidden",
                "GraphicsObject.hidden", "GraphicsObject.spotAnimId"}) {
            assertEquals(mapping.offsetOr(key, NativeProtocol.NO_OFFSET), buf.getInt(), key);
        }
        for (String key : new String[]{"hash_map.buckets", "hash_map.bucketCount", "hash_map.node.key",
                "hash_map.node.value", "hash_map.node.next", "list.node.next", "list.node.value"}) {
            assertEquals(mapping.container(key), buf.getInt(), key);
        }
        assertEquals(0, buf.remaining(), "84 bytes, as the DLL reads them");
    }

    @Test
    void theComponentBlockFollowsTheMappingInTheDllsOrder() throws IOException {
        Mapping mapping = Mapping.bundled("241.3");
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.componentLayout(mapping)).order(ByteOrder.LITTLE_ENDIAN);

        for (String function : new String[]{"ifListenerClear", "stringAssign", "ifInvalidate"}) {
            assertTrue(mapping.hasFunction(function), function);
            assertEquals(mapping.function(function), buf.getLong(), function);
        }
        for (String key : new String[]{"Client.interfaceStore", "IfStore.groupCount", "IfStore.groups",
                "IfStoreGroup.stride", "IfStoreGroup.count", "IfStoreGroup.slots", "IfStoreGroup.slotStride"}) {
            assertEquals(mapping.offset(key), buf.getInt(), key);
        }
        assertEquals(mapping.container("shared_ptr.ptr"), buf.getInt(), "a slot's pointer");
        for (String key : new String[]{"IfType.dynCount", "IfType.dynSlots", "IfType.packedId", "IfType.subId"}) {
            assertEquals(mapping.offset(key), buf.getInt(), key);
        }
        for (String key : new String[]{"string.heapFlagByte", "string.heapFlagMask", "string.ssoCapacity"}) {
            assertEquals(mapping.container(key), buf.getInt(), key);
        }
        assertEquals(0, buf.remaining(), "84 bytes, as the DLL reads them");
    }

    @Test
    void theFrameBlockNamesEndFrameAndTheDeviceOffsets() throws IOException {
        Mapping mapping = Mapping.bundled("241.3");
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.frameLayout(mapping)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(mapping.function("renderEndFrame"), buf.getLong(), "endFrame");
        assertEquals(mapping.offset("GlDevice.context"), buf.getInt(), "the context present takes");
        assertEquals(mapping.offset("GlDevice.recordingFrame"), buf.getInt(), "the frame being recorded");
        assertEquals(mapping.offset("GlDevice.renderingFrame"), buf.getInt(), "the frame being rendered");
        assertEquals(0, buf.remaining(), "20 bytes, as the DLL reads them");
    }

    @Test
    void theDrawBlockFollowsTheMappingInTheDllsOrder() throws IOException {
        List<String> versions = bundledVersions();
        assertTrue(versions.contains("241.3"), "the bundled mappings are on the classpath: " + versions);
        for (String version : versions) {
            Mapping mapping = Mapping.bundled(version);
            ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.drawLayout(mapping)).order(ByteOrder.LITTLE_ENDIAN);

            for (String key : new String[]{"Client.topLevelWorld", "World.scene", "World.plane", "World.width",
                    "World.height", "World.npcs"}) {
                assertEquals(mapping.offset(key), buf.getInt(), version + " " + key);
            }
            for (String key : new String[]{"hash_map.buckets", "hash_map.bucketCount", "hash_map.node.key",
                    "hash_map.node.value", "hash_map.node.next"}) {
                assertEquals(mapping.container(key), buf.getInt(), version + " " + key);
            }
            for (String key : new String[]{"Npc.spawnCycle", "Actor.fineX", "Actor.fineZ", "Actor.pathX",
                    "Actor.pathZ", "Scene.sizeX", "Scene.sizeZ", "Scene.tileSettings", "Scene.heights",
                    "Scene.cameraTrig", "Client.camera.pos", "Client.zoomA", "Client.renderer", "Renderer.viewport",
                    "Viewport.centerX", "Viewport.centerY", "Viewport.offsetX", "Viewport.offsetY",
                    "Renderer.canvasWidth", "Renderer.canvasHeight", "Renderer.screenWidth",
                    "Renderer.screenHeight"}) {
                assertEquals(mapping.offset(key), buf.getInt(), version + " " + key);
            }
            assertEquals(0, buf.remaining(), "132 bytes, as the DLL reads them");
        }
    }

    /**
     * Versions of every mapping bundled with the API, so a mapping is checked here as soon as it is added.
     *
     * @return the versions, such as {@code "241.3"}, oldest first
     */
    private static List<String> bundledVersions() {
        try (ScanResult scan = new ClassGraph().acceptPathsNonRecursive(Mapping.RESOURCE_DIR).scan()) {
            return scan.getResourcesWithExtension("json").getPaths().stream()
                    .map(path -> path.substring(Mapping.RESOURCE_DIR.length(), path.length() - ".json".length()))
                    .sorted(Comparator.comparing(NativeProtocolTest::versionParts, Arrays::compare))
                    .toList();
        }
    }

    /**
     * A version's numbers, for sorting {@code "242.10"} after {@code "242.9"}.
     *
     * @param version a mapping version, such as {@code "241.3"}
     * @return its dot-separated numbers
     */
    private static int[] versionParts(String version) {
        return Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
    }

    @Test
    void drawListFramesEachItemWithItsKindsBody() {
        WireDrawList list = new WireDrawList(List.of(
                WireDrawList.Item.tileArea(WireDrawList.ANCHOR_NPC, 5, 1000, -64, 64, 0, 2, 3, 0xff00ffff,
                        0x1400ffff, 32),
                WireDrawList.Item.sprite(WireDrawList.ANCHOR_NPC_TILE, 6, 1001, 0, 0, 240, 10, 20, 30, 12, -15,
                        -9)));
        byte[] command = NativeProtocol.drawList(list);
        ByteBuffer buf = ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(command.length - 4, buf.getInt(), "the length counts the bytes after it");
        assertEquals(NativeProtocol.COMMAND_DRAW_LIST, buf.get());
        assertEquals(2, buf.getShort(), "item count");

        assertEquals(WireDrawList.TILE_AREA, buf.get());
        assertEquals(WireDrawList.ANCHOR_NPC, buf.get());
        assertEquals(5, buf.getInt(), "index");
        assertEquals(1000, buf.getInt(), "spawn cycle");
        assertEquals(-64, buf.getInt(), "dx");
        assertEquals(64, buf.getInt(), "dy");
        assertEquals(0, buf.getInt(), "lift");
        assertEquals(2, buf.getShort(), "size x");
        assertEquals(3, buf.getShort(), "size y");
        assertEquals(0xff00ffff, buf.getInt(), "stroke");
        assertEquals(0x1400ffff, buf.getInt(), "fill");
        assertEquals(32, buf.getShort(), "stroke width in sixteenths");

        assertEquals(WireDrawList.SPRITE, buf.get());
        assertEquals(WireDrawList.ANCHOR_NPC_TILE, buf.get());
        assertEquals(6, buf.getInt());
        assertEquals(1001, buf.getInt());
        assertEquals(0, buf.getInt());
        assertEquals(0, buf.getInt());
        assertEquals(240, buf.getInt(), "lift");
        assertEquals(10, buf.getShort(), "atlas x");
        assertEquals(20, buf.getShort(), "atlas y");
        assertEquals(30, buf.getShort(), "width");
        assertEquals(12, buf.getShort(), "height");
        assertEquals(-15, buf.getShort(), "offset x");
        assertEquals(-9, buf.getShort(), "offset y");
        assertEquals(0, buf.remaining());
    }

    @Test
    void drawSpritesFramesEachSpritesPlaceAndPixels() {
        WireSprite sprite = new WireSprite(4, 5, 2, 1, new int[]{0x80402010, 0xff000000});
        List<byte[]> commands = NativeProtocol.drawSprites(List.of(sprite));
        assertEquals(1, commands.size());
        ByteBuffer buf = ByteBuffer.wrap(commands.get(0)).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(commands.get(0).length - 4, buf.getInt());
        assertEquals(NativeProtocol.COMMAND_DRAW_SPRITES, buf.get());
        assertEquals(1, buf.getShort(), "sprite count");
        assertEquals(4, buf.getShort(), "x");
        assertEquals(5, buf.getShort(), "y");
        assertEquals(2, buf.getShort(), "width");
        assertEquals(1, buf.getShort(), "height");
        assertEquals(0x80402010, buf.getInt(), "pixels as little-endian ARGB, so B, G, R, A in memory");
        assertEquals(0xff000000, buf.getInt());
        assertEquals(0, buf.remaining());
    }

    @Test
    void drawSpritesSplitsBatchesThatWouldPassTheFrameLimit() {
        int side = 600;
        List<WireSprite> sprites = List.of(new WireSprite(0, 8, side, side, new int[side * side]),
                new WireSprite(0, 8 + side, side, side, new int[side * side]),
                new WireSprite(side, 8, side, side, new int[side * side]));
        List<byte[]> commands = NativeProtocol.drawSprites(sprites);

        assertEquals(2, commands.size(), "two 1.4 MB sprites fit one command, a third doesn't");
        int total = 0;
        for (byte[] command : commands) {
            ByteBuffer buf = ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN);
            assertTrue(buf.getInt() <= NativeProtocol.MAX_FRAME);
            assertEquals(NativeProtocol.COMMAND_DRAW_SPRITES, buf.get());
            total += buf.getShort();
        }
        assertEquals(3, total, "every sprite is sent once");
    }

    @Test
    void aMappingWithoutActorFlagsHasNoVisibilityOrComponentBlock() throws IOException {
        Path file = Files.createTempFile("mapping", ".json");
        try {
            Files.writeString(file, "{\"version\": \"1.0\", \"sha256\": \"00\", \"globals\": {\"client\": \"0x10\"},"
                    + " \"structs\": {}, \"containers\": {}, \"enums\": {}, \"functions\": {}}");
            assertEquals(0, NativeProtocol.visibilityLayout(Mapping.load(file)).length);
            assertEquals(0, NativeProtocol.componentLayout(Mapping.load(file)).length);
            assertEquals(0, NativeProtocol.frameLayout(Mapping.load(file)).length);
            assertEquals(0, NativeProtocol.drawLayout(Mapping.load(file)).length);
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void visibilityFramesTheKindsEachActorAndTheGraphicsObjects() {
        WireVisibility rules = new WireVisibility(WireVisibility.HIDE_PLAYERS_3D | WireVisibility.HIDE_PROJECTILES,
                List.of(new WireVisibility.ActorRule(0x1234_5678_9abcL, 3, 1)), new int[]{1590, 1591});
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.visibility(rules)).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(1 + 1 + 2 + 10 + 1 + 8, buf.getInt(), "the length counts the type byte and the body");
        assertEquals(NativeProtocol.COMMAND_VISIBILITY, buf.get());
        assertEquals(4 | 64, buf.get(), "the kinds");
        assertEquals(1, buf.getShort(), "one actor");
        assertEquals(0x1234_5678_9abcL, buf.getLong());
        assertEquals(3, buf.get(), "both layers decided");
        assertEquals(1, buf.get(), "the model hidden");
        assertEquals(2, buf.get(), "two graphics objects");
        assertEquals(1590, buf.getInt());
        assertEquals(1591, buf.getInt());
        assertEquals(0, buf.remaining());

        byte[] none = NativeProtocol.visibility(WireVisibility.NONE);
        assertArrayEquals(new byte[]{5, 0, 0, 0, (byte) NativeProtocol.COMMAND_VISIBILITY, 0, 0, 0, 0}, none,
                "nothing hidden");
    }

    @Test
    void componentWritesAreFramedFieldForField() {
        List<ComponentOp> ops = List.of(
                new ComponentOp(149 << 16, 3, ComponentOp.Op.CLEAR_LISTENER, 0x470, 0),
                new ComponentOp(12 << 16 | 13, -1, ComponentOp.Op.SET_I32, 0x324, 30));
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.components(ops)).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(1 + 2 + 2 * 15, buf.getInt());
        assertEquals(NativeProtocol.COMMAND_COMPONENTS, buf.get());
        assertEquals(2, buf.getShort());
        assertEquals(149 << 16, buf.getInt());
        assertEquals(3, buf.getInt(), "the dynamic child's index");
        assertEquals(1, buf.get(), "clear a listener");
        assertEquals(0x470, buf.getShort());
        assertEquals(0, buf.getInt());
        assertEquals(12 << 16 | 13, buf.getInt());
        assertEquals(-1, buf.getInt(), "a static component");
        assertEquals(0, buf.get(), "write an int");
        assertEquals(0x324, buf.getShort());
        assertEquals(30, buf.getInt());
        assertEquals(0, buf.remaining());
    }

    @Test
    void aTextWriteCarriesItsLengthAndItsUtf8Bytes() {
        List<ComponentOp> ops = List.of(
                new ComponentOp(162 << 16 | 33, -1, ComponentOp.Op.SET_TEXT, 0x158, 0, "1,234 é"),
                new ComponentOp(162 << 16 | 33, -1, ComponentOp.Op.SET_TEXT, 0x158, 0, ""),
                new ComponentOp(149 << 16, 3, ComponentOp.Op.SET_I32, 0x324, 30));
        byte[] text = "1,234 é".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.components(ops)).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(1 + 2 + 3 * 15 + text.length, buf.getInt(), "the length counts the texts' bytes");
        assertEquals(NativeProtocol.COMMAND_COMPONENTS, buf.get());
        assertEquals(3, buf.getShort());
        assertEquals(162 << 16 | 33, buf.getInt());
        assertEquals(-1, buf.getInt());
        assertEquals(2, buf.get(), "set a text");
        assertEquals(0x158, buf.getShort());
        assertEquals(text.length, buf.getInt(), "the value is the text's length in bytes");
        byte[] sent = new byte[text.length];
        buf.get(sent);
        assertArrayEquals(text, sent);
        assertEquals(162 << 16 | 33, buf.getInt());
        assertEquals(-1, buf.getInt());
        assertEquals(2, buf.get());
        assertEquals(0x158, buf.getShort());
        assertEquals(0, buf.getInt(), "an empty text has no bytes");
        assertEquals(149 << 16, buf.getInt(), "the next write follows the text");
        assertEquals(3, buf.getInt());
        assertEquals(0, buf.get());
        assertEquals(0x324, buf.getShort());
        assertEquals(30, buf.getInt());
        assertEquals(0, buf.remaining());
    }

    @Test
    void aMappingWithoutTheScriptRuntimeHasNoScriptBlock() throws IOException {
        Path file = Files.createTempFile("mapping", ".json");
        try {
            Files.writeString(file, "{\"version\": \"1.0\", \"sha256\": \"00\", \"globals\": {\"client\": \"0x10\"},"
                    + " \"structs\": {}, \"containers\": {}, \"enums\": {}, \"functions\": {}}");
            assertEquals(0, NativeProtocol.scriptLayout(Mapping.load(file)).length);
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void scriptCaptureFramesTheFlagsAndTheRootScripts() {
        int[] none = {};
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.scriptCapture(true, false, none)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(3, buf.getInt(), "the type byte, the flags and the count");
        assertEquals(NativeProtocol.COMMAND_SCRIPTS, buf.get());
        assertEquals(1, buf.get(), "starts only");
        assertEquals(0, buf.get(), "no root scripts");
        assertEquals(0, buf.remaining());
        assertEquals(2, NativeProtocol.scriptCapture(false, true, none)[5], "returns only");
        assertEquals(3, NativeProtocol.scriptCapture(true, true, none)[5]);
        assertEquals(0, NativeProtocol.scriptCapture(false, false, none)[5]);

        byte[] xpDrops = NativeProtocol.scriptCapture(false, false, new int[]{2091});
        buf = ByteBuffer.wrap(xpDrops).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(7, buf.getInt());
        assertEquals(NativeProtocol.COMMAND_SCRIPTS, buf.get());
        assertEquals(4, buf.get(), "the listed root scripts only");
        assertEquals(1, buf.get());
        assertEquals(2091, buf.getInt());
        assertEquals(0, buf.remaining());

        int[] many = IntStream.range(0, 20).toArray();
        assertEquals(NativeProtocol.MAX_SCRIPT_ROOTS, NativeProtocol.scriptCapture(false, false, many)[6]);
    }

    @Test
    void parsesAGosubsStacksIntoArraysAsLongAsTheStacks() {
        ByteBuffer buf = scriptHead(12, 1, 1004, 2);
        buf.putInt(5).putShort((short) 2).putInt(30).putInt(40);
        buf.putInt(3).putShort((short) 2);
        buf.put((byte) NativeProtocol.VALUE_STRING).putShort((short) 7).put("Draynor".getBytes(StandardCharsets.UTF_8));
        buf.put((byte) NativeProtocol.VALUE_OTHER);
        buf.putInt(149 << 16).putInt(7).putInt(-1).putInt(-1);
        buf.put((byte) 0);
        byte[] body = java.util.Arrays.copyOf(buf.array(), buf.position());

        assertEquals(NativeProtocol.SCRIPT_EVENT_ID, NativeProtocol.eventId(body));
        assertEquals(12, NativeProtocol.seq(body));
        ScriptRun run = NativeProtocol.parseScript(body);
        assertEquals(ScriptRun.Kind.PRE_GOSUB, run.kind());
        assertEquals(1004, run.scriptId());
        assertEquals(2, run.depth());
        assertArrayEquals(new int[]{0, 0, 0, 30, 40}, run.stack().ints(), "the captured top at the end");
        assertArrayEquals(new Object[]{null, "Draynor", null}, run.stack().objects());
        assertEquals(new ScriptComponents(149 << 16, 7, -1, -1), run.components(), "the active component, no dot one");
        assertNull(run.request());
    }

    @Test
    void parsesARootRunsRequest() {
        ByteBuffer buf = scriptHead(1, 0, 286, 0);
        buf.putInt(0).putShort((short) 0);
        buf.putInt(0).putShort((short) 0);
        buf.putInt(-1).putInt(-1).putInt(-1).putInt(-1);
        buf.put((byte) 1);
        for (int field : new int[]{149 << 16, 4, -1, -1, 2, 0, 310, 220, -1, -1}) {
            buf.putInt(field);
        }
        buf.putShort((short) 5).put("Wield".getBytes(StandardCharsets.UTF_8));
        buf.putShort((short) 3);
        buf.put((byte) NativeProtocol.VALUE_INT).putInt(286);
        buf.put((byte) NativeProtocol.VALUE_INT).putInt(-2147483645);
        buf.put((byte) NativeProtocol.VALUE_LONG).putLong(1L << 40);
        ScriptRun run = NativeProtocol.parseScript(java.util.Arrays.copyOf(buf.array(), buf.position()));

        assertEquals(ScriptRun.Kind.PRE_ROOT, run.kind());
        assertEquals(0, run.stack().ints().length);
        ScriptRequest request = run.request();
        assertEquals(149 << 16, request.sourceId());
        assertEquals(4, request.sourceIndex());
        assertEquals(-1, request.targetId());
        assertEquals(2, request.op());
        assertEquals(0, request.subOp());
        assertEquals(310, request.mouseX());
        assertEquals(220, request.mouseY());
        assertEquals("Wield", request.opbase());
        assertArrayEquals(new Object[]{286, -2147483645, 1L << 40}, request.arguments());
    }

    @Test
    void aStackSizeThatIsABadReadKeepsJustTheCapturedEntries() {
        ByteBuffer buf = scriptHead(1, 2, 7, 0);
        buf.putInt(-5).putShort((short) 1).putInt(9);
        buf.putInt(1_000_000).putShort((short) 0);
        buf.putInt(-1).putInt(-1).putInt(-1).putInt(-1);
        buf.put((byte) 0);
        ScriptRun run = NativeProtocol.parseScript(java.util.Arrays.copyOf(buf.array(), buf.position()));
        assertEquals(ScriptRun.Kind.POST, run.kind());
        assertArrayEquals(new int[]{9}, run.stack().ints());
        assertEquals(0, run.stack().objects().length);
        assertTrue(run.request() == null);
    }

    /**
     * Starts a script message as the DLL lays it out: the head with {@code {kind, id, depth, 0}} and no plan values.
     *
     * @param seq   the sequence number
     * @param kind  0 a root script starting, 1 a gosub, 2 a return
     * @param id    the script's id
     * @param depth its depth
     * @return the buffer, positioned after the head
     */
    private static ByteBuffer scriptHead(int seq, int kind, int id, int depth) {
        ByteBuffer buf = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(seq).putInt(NativeProtocol.SCRIPT_EVENT_ID);
        buf.putLong(kind).putLong(id).putLong(depth).putLong(0);
        buf.put((byte) 0);
        return buf;
    }

    @Test
    void callsFrameEachCallWithItsArgumentsAndData() {
        NativeCall configure = NativeCall.to(0x3ad8c0L).imm(0).imm(7).imm(31).data(new byte[]{1, 2, 3}).build();
        NativeCall clear = NativeCall.to(0x1b9550L).client(0x89680).imm(7).imm(-1).build();
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.calls(42, List.of(configure, clear)))
                .order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(buf.capacity() - 4, buf.getInt(), "the length counts the type and the body");
        assertEquals(NativeProtocol.COMMAND_CALLS, buf.get());
        assertEquals(42, buf.getInt(), "batch id");
        assertEquals(2, buf.getShort(), "call count");

        assertEquals(0x3ad8c0L, buf.getLong());
        assertEquals(4, buf.get(), "argc");
        long[][] args = {{0, 0}, {0, 7}, {0, 31}, {2, 0}};
        for (long[] arg : args) {
            assertEquals(arg[0], buf.get(), "kind");
            assertEquals(arg[1], buf.getLong(), "value");
        }
        assertEquals(3, buf.getShort(), "data length");
        assertEquals(1, buf.get());
        assertEquals(2, buf.get());
        assertEquals(3, buf.get());

        assertEquals(0x1b9550L, buf.getLong());
        assertEquals(3, buf.get());
        assertEquals(1, buf.get(), "CLIENT");
        assertEquals(0x89680, buf.getLong());
        assertEquals(0, buf.get());
        assertEquals(7, buf.getLong());
        assertEquals(0, buf.get());
        assertEquals(-1, buf.getLong(), "negative values go as two's complement");
        assertEquals(0, buf.getShort(), "no data");
        assertEquals(0, buf.remaining());
    }

    @Test
    void scratchAndIndirectArgumentsGoAsTheirKindAndPackedValue() {
        NativeCall create = NativeCall.to(0x6795c0L).scratch(0).imm(7).indirect(0x31a0, 0xa08).build();
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.calls(1, List.of(create))).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4 + 1 + 4 + 2 + 8);
        assertEquals(3, buf.get(), "argc");
        assertEquals(3, buf.get(), "SCRATCH");
        assertEquals(0, buf.getLong());
        assertEquals(0, buf.get(), "IMM");
        assertEquals(7, buf.getLong());
        assertEquals(4, buf.get(), "INDIRECT");
        assertEquals(0xa08L << 32 | 0x31a0, buf.getLong(), "pointer offset low, addend high");
        assertEquals(0, buf.getShort(), "no data");
    }

    @Test
    void surfaceFramesTheGenerationAndName() {
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.surface(3, "Local\\KrakenOverlay-12-3"))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(buf.capacity() - 4, buf.getInt());
        assertEquals(NativeProtocol.COMMAND_SURFACE, buf.get());
        assertEquals(3, buf.getInt());
        int length = buf.getShort();
        byte[] name = new byte[length];
        buf.get(name);
        assertEquals("Local\\KrakenOverlay-12-3", new String(name, StandardCharsets.US_ASCII));
        assertEquals(0, buf.remaining());

        ByteBuffer stop = ByteBuffer.wrap(NativeProtocol.surface(0, "")).order(ByteOrder.LITTLE_ENDIAN);
        stop.position(9);
        assertEquals(0, stop.getShort(), "an empty name stops the drawing");
    }

    @Test
    void frameLimitFramesTheRate() {
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.frameLimit(20)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(5, buf.getInt(), "the type byte and a u32");
        assertEquals(NativeProtocol.COMMAND_FRAME_LIMIT, buf.get());
        assertEquals(20, buf.getInt());
        assertEquals(0, buf.remaining());

        ByteBuffer off = ByteBuffer.wrap(NativeProtocol.frameLimit(-3)).order(ByteOrder.LITTLE_ENDIAN);
        off.position(5);
        assertEquals(0, off.getInt(), "a negative rate is no cap");
    }

    @Test
    void frameSyncAndFrameDoneFrameTheirValues() {
        ByteBuffer on = ByteBuffer.wrap(NativeProtocol.frameSync(true)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, on.getInt(), "the type byte and a u8");
        assertEquals(NativeProtocol.COMMAND_FRAME_SYNC, on.get());
        assertEquals(1, on.get());
        assertEquals(0, on.remaining());
        assertEquals(0, NativeProtocol.frameSync(false)[5]);

        ByteBuffer done = ByteBuffer.wrap(NativeProtocol.frameDone(0x1_0000_0007L)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(5, done.getInt(), "the type byte and a u32");
        assertEquals(NativeProtocol.COMMAND_FRAME_DONE, done.get());
        assertEquals(7, done.getInt(), "the DLL's tags are 32 bits");
        assertEquals(0, done.remaining());
    }

    @Test
    void parsesIdRegistersAndSequenceWithNoValues() {
        byte[] body = message(7, 44, new long[]{0xAA, 0xBB, 0xCC, 0xDD}, builder -> {
        });
        NativeEvent event = NativeProtocol.parse(body, id -> id == 44 ? "ON_GAME_TICK" : null);

        assertEquals("ON_GAME_TICK", event.name());
        assertEquals(44, event.id());
        assertEquals(7, event.seq());
        assertEquals(0xAA, event.reg(NativeEvent.RCX));
        assertEquals(0xDD, event.reg(NativeEvent.R9));
        assertEquals(Map.of(), event.values());
    }

    @Test
    void parsesEachValueOp() {
        byte[] body = message(1, 40, new long[4], builder -> {
            builder.i32(NativeEvent.RDX, 0x12345);
            builder.cstr(NativeEvent.R8, "Oak");
            builder.i32(NativeEvent.R9, 5);
        });
        NativeEvent event = NativeProtocol.parse(body, id -> "ON_LOC_LOAD");

        assertEquals(0x12345, event.i32(NativeEvent.RDX));
        assertEquals("Oak", event.str(NativeEvent.R8));
        assertEquals(5, event.i32(NativeEvent.R9));
    }

    @Test
    void parsesIntPairAndRaw() {
        byte[] body = message(2, 46, new long[4], builder -> {
            builder.i32x2(NativeEvent.RDX, 100, 7);
            builder.raw(NativeEvent.R8, 0x1_0000_0003L);
        });
        NativeEvent event = NativeProtocol.parse(body, id -> "ON_VARBIT_UPDATE");

        assertArrayEquals(new int[]{100, 7}, event.i32x2(NativeEvent.RDX));
        assertEquals(0x1_0000_0003L, event.i64(NativeEvent.R8));
        assertEquals(3, event.i32(NativeEvent.R8), "the low int of a raw register");
    }

    @Test
    void unknownIdLeavesTheNameNull() {
        byte[] body = message(0, 999, new long[4], builder -> {
        });
        NativeEvent event = NativeProtocol.parse(body, id -> null);
        assertNull(event.name());
        assertEquals(999, event.id());
    }

    @Test
    void featureNamesFollowTheBitOrderAndSkipUnknownBits() {
        assertEquals(List.of(), NativeProtocol.featureNames(0));
        assertEquals(List.of("calls", "keys"),
                NativeProtocol.featureNames(NativeProtocol.FEATURE_KEYS | NativeProtocol.FEATURE_CALLS | 1L << 40));
        assertEquals(List.of("calls", "overlay", "frame rate", "scripts", "menu", "keys", "visibility", "components",
                "frame sync", "draw list"), NativeProtocol.featureNames(0x3ff), "every known bit, in bit order");
    }

    /**
     * Builds a message body in the DLL's format, so {@link NativeProtocol#parse} can be tested against bytes laid out
     * the way the DLL lays them ({@code src/core/ring.cpp} and {@code src/raises.cpp}).
     *
     * @param seq     the sequence number
     * @param eventId the event id
     * @param regs    the four argument registers
     * @param values  appends the captured values
     * @return the message body (the bytes after the length frame)
     */
    private static byte[] message(int seq, int eventId, long[] regs, java.util.function.Consumer<Values> values) {
        ByteBuffer buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(seq);
        buf.putInt(eventId);
        for (int i = 0; i < 4; i++) {
            buf.putLong(regs[i]);
        }
        int countAt = buf.position();
        buf.put((byte) 0);
        Values appender = new Values(buf);
        values.accept(appender);
        buf.put(countAt, (byte) appender.count);
        return java.util.Arrays.copyOf(buf.array(), buf.position());
    }

    /**
     * Appends {@code {arg, op, payload}} values to a message body and counts them.
     */
    private static final class Values {
        private final ByteBuffer buf;
        private int count;

        Values(ByteBuffer buf) {
            this.buf = buf;
        }

        void raw(int arg, long value) {
            head(arg, NativeProtocol.OP_RAW);
            buf.putLong(value);
        }

        void i32(int arg, int value) {
            head(arg, NativeProtocol.OP_I32);
            buf.putInt(value);
        }

        void i32x2(int arg, int a, int b) {
            head(arg, NativeProtocol.OP_I32X2);
            buf.putInt(a);
            buf.putInt(b);
        }

        void cstr(int arg, String value) {
            head(arg, NativeProtocol.OP_CSTR);
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            buf.put((byte) bytes.length);
            buf.put(bytes);
        }

        private void head(int arg, int op) {
            buf.put((byte) arg);
            buf.put((byte) op);
            count++;
        }
    }
}
