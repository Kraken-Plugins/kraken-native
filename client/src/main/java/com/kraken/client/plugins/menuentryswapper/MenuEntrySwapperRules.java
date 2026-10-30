package com.kraken.client.plugins.menuentryswapper;

import com.kraken.api.MenuAction;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.menu.MenuMatch;
import com.kraken.api.menu.MenuRule;
import com.kraken.api.menu.MenuRule.Edit.Action;
import com.kraken.api.menu.MenuSubject;
import com.kraken.api.menu.ShiftState;
import com.kraken.api.menu.TextMatch;
import com.kraken.client.plugins.menuentryswapper.MenuEntrySwapperConfig.ArdougneCloakMode;
import com.kraken.client.plugins.menuentryswapper.MenuEntrySwapperConfig.DesertAmuletMode;
import com.kraken.client.plugins.menuentryswapper.MenuEntrySwapperConfig.KaramjaGlovesMode;
import com.kraken.client.plugins.menuentryswapper.MenuEntrySwapperConfig.MorytaniaLegsMode;
import com.kraken.client.plugins.menuentryswapper.MenuEntrySwapperConfig.RadasBlessingMode;
import com.kraken.client.plugins.menuentryswapper.MenuEntrySwapperConfig.StairsMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/**
 * Turns the swapper's settings into the menu rules the native hook applies: the built-in swaps, Shift-click Walk
 * here, the bank and shop Shift-clicks, and the swaps the player picked per item, NPC, object, worn item and
 * interface component with Shift + right-click, which the plugin stores as RuneLite's Menu Entry Swapper does.
 *
 * <p>Stored swaps: {@code item_<id>} (group {@code shiftclick} for the Shift-click one), {@code npc_<id>} and
 * {@code npc_shift_<id>}, {@code object_<id>} and {@code object_shift_<id>}, {@code wornitem_<id>} and
 * {@code wornitem_shift_<id>}, {@code ui_<component>[_<item>]} and {@code ui_shift_<component>[_<item>]}. An item's
 * value is its action's index (0 to 4) or -1 for Use; an NPC's or object's the op's index or -1 for Walk here; a
 * worn item's the component op (2 to 9); a component's the op (1 to 10). Item and worn item swaps match the row by
 * the item's name, as the hook sees no item ids; a sub-option swap, stored as the option's hash, isn't applied.
 */
final class MenuEntrySwapperRules {

    /**
     * The group RuneLite keeps the Shift-click item swaps in.
     */
    static final String SHIFTCLICK_GROUP = "shiftclick";

    /**
     * The op number an inventory item's actions 1 to 5 run as: component ops 2 to 4 and 7 to 8.
     */
    static final int[] ITEM_OPS = {2, 3, 4, 7, 8};

    /**
     * The component op a worn item's first action runs as; the rest follow.
     */
    static final int WORN_FIRST_OP = 2;

    private static final MenuAction[] NPC_OPS = {MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
            MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION};
    private static final MenuAction[] OBJECT_OPS = {MenuAction.GAME_OBJECT_FIRST_OPTION,
            MenuAction.GAME_OBJECT_SECOND_OPTION, MenuAction.GAME_OBJECT_THIRD_OPTION,
            MenuAction.GAME_OBJECT_FOURTH_OPTION, MenuAction.GAME_OBJECT_FIFTH_OPTION};

    private static final List<String> ESSENCE_MINE_NPCS = List.of("aubury", "archmage sedridor", "wizard distentor",
            "wizard cromperty", "brimstail");
    private static final List<String> TEMPOROSS_NPCS = List.of("captain dudi", "captain pudi", "first mate deri",
            "first mate peri");
    private static final List<String> JEWELLERY_BOX = List.of("emir's arena", "castle wars", "ferox enclave",
            "fortis colosseum", "burthorpe", "barbarian outpost", "corporeal beast", "tears of guthix",
            "wintertodt camp", "warriors' guild", "champions' guild", "monastery", "ranging guild", "fishing guild",
            "mining guild", "crafting guild", "cooking guild", "woodcutting guild", "farming guild", "miscellania",
            "grand exchange", "falador park", "dondakan's rock", "edgeville", "karamja", "draynor village",
            "al kharid");
    private static final List<String> PORTAL_NEXUS = List.of("annakarl", "ape atoll dungeon", "ardougne", "barrows",
            "battlefront", "camelot", "carrallanger", "catherby", "cemetery", "draynor manor", "falador",
            "fenkenstrain's castle", "fishing guild", "ghorrock", "grand exchange", "great kourend", "harmony island",
            "kharyrll", "lumbridge", "arceuus library", "lunar isle", "marim", "mind altar", "salve graveyard",
            "seers' village", "senntisten", "troll stronghold", "varrock", "watchtower", "waterbirth island", "weiss",
            "west ardougne", "yanille");

    private final MenuEntrySwapperConfig config;
    private final IntFunction<String> itemName;
    private final List<Swap> swaps = new ArrayList<>();

    /**
     * Prepares the built-in swaps over the settings.
     *
     * @param config   the settings, read each time rules are built
     * @param itemName an item's name by id, or null when it can't be read; the item swaps match rows by it
     */
    MenuEntrySwapperRules(MenuEntrySwapperConfig config, IntFunction<String> itemName) {
        this.config = config;
        this.itemName = itemName;
        setupSwaps();
    }

    /**
     * The built-in swaps, in the order they take priority.
     *
     * @return the swaps
     */
    List<Swap> swaps() {
        return List.copyOf(swaps);
    }

    /**
     * Builds the rules for the settings as they are now and the stored swaps.
     *
     * @param stored the stored swaps: each full setting key ({@code group.key}) to its value
     * @return the rules, in the order to apply them: the stored swaps first, then the built-in swaps, the bank and
     * shop Shift-clicks and Shift-click Walk here
     */
    List<MenuRule> rules(Map<String, String> stored) {
        List<MenuRule> rules = new ArrayList<>();
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            MenuRule rule = storedRule(entry.getKey(), entry.getValue());
            if (rule != null) {
                rules.add(rule);
            }
        }
        for (Swap swap : swaps) {
            if (swap.enabled().getAsBoolean()) {
                MenuMatch trigger = MenuMatch.any().option(TextMatch.exact(swap.option()));
                if (swap.target() != null) {
                    trigger = trigger.target(swap.target());
                }
                rules.add(new MenuRule.Swap(trigger, MenuMatch.any().option(swap.swapped()), swap.shift(), true,
                        false, true, false));
            }
        }
        rules.addAll(bankRules());
        if (config.objectShiftClickWalkHere()) {
            rules.add(MenuRule.Edit.of(MenuMatch.any().kind(MenuSubject.LOC), ShiftState.HELD, Action.DEPRIORITIZE));
        }
        if (config.npcShiftClickWalkHere()) {
            rules.add(MenuRule.Edit.of(MenuMatch.any().kind(MenuSubject.NPC), ShiftState.HELD, Action.DEPRIORITIZE));
        }
        if (config.groundItemShiftClickWalkHere()) {
            rules.add(MenuRule.Edit.of(MenuMatch.any().kind(MenuSubject.OBJ), ShiftState.HELD, Action.DEPRIORITIZE));
        }
        return rules;
    }

    /**
     * The rule a stored swap makes.
     *
     * @param key   the full setting key
     * @param value its value
     * @return the rule, or null if the key isn't a swap, its value can't be applied or the item's name is unknown
     */
    MenuRule storedRule(String key, String value) {
        int number;
        try {
            number = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        String swapper = MenuEntrySwapperConfig.GROUP + ".";
        if (key.startsWith(SHIFTCLICK_GROUP + ".item_")) {
            return config.shiftClickCustomization()
                    ? itemRule(key.substring((SHIFTCLICK_GROUP + ".item_").length()), number, ShiftState.HELD) : null;
        }
        if (!key.startsWith(swapper)) {
            return null;
        }
        String rest = key.substring(swapper.length());
        if (rest.startsWith("item_")) {
            return config.leftClickCustomization() ? itemRule(rest.substring(5), number, ShiftState.NOT_HELD) : null;
        }
        if (rest.startsWith("npc_shift_")) {
            return npcRule(rest.substring(10), number, ShiftState.HELD);
        }
        if (rest.startsWith("npc_")) {
            return npcRule(rest.substring(4), number, ShiftState.NOT_HELD);
        }
        if (rest.startsWith("object_shift_")) {
            return objectRule(rest.substring(13), number, ShiftState.HELD);
        }
        if (rest.startsWith("object_")) {
            return objectRule(rest.substring(7), number, ShiftState.NOT_HELD);
        }
        if (rest.startsWith("wornitem_shift_")) {
            return wornRule(rest.substring(15), number, ShiftState.HELD);
        }
        if (rest.startsWith("wornitem_")) {
            return wornRule(rest.substring(9), number, ShiftState.NOT_HELD);
        }
        if (rest.startsWith("ui_shift_")) {
            return uiRule(rest.substring(9), number, ShiftState.HELD);
        }
        if (rest.startsWith("ui_")) {
            return uiRule(rest.substring(3), number, ShiftState.NOT_HELD);
        }
        return null;
    }

    private MenuRule itemRule(String id, int value, ShiftState shift) {
        String name = name(id);
        if (name == null) {
            return null;
        }
        MenuMatch item = MenuMatch.any().group(InterfaceID.INVENTORY).target(TextMatch.exact(name));
        if (value == -1) {
            return MenuRule.Edit.of(item.action(MenuAction.WIDGET_TARGET), shift, Action.PROMOTE);
        }
        if (value < 0 || value >= ITEM_OPS.length) {
            return null;
        }
        return MenuRule.Edit.of(item.action(MenuAction.CC_OP, ITEM_OPS[value]), shift, Action.PROMOTE);
    }

    private MenuRule npcRule(String id, int value, ShiftState shift) {
        Integer npcId = parse(id);
        if (npcId == null) {
            return null;
        }
        MenuMatch npc = MenuMatch.any().kind(MenuSubject.NPC).typeId(npcId);
        if (value == -1) {
            return MenuRule.Edit.of(npc, shift, Action.DEPRIORITIZE);
        }
        if (value < 0 || value >= NPC_OPS.length) {
            return null;
        }
        return MenuRule.Edit.of(npc.action(NPC_OPS[value]), shift, Action.PROMOTE_SUBJECT);
    }

    private MenuRule objectRule(String id, int value, ShiftState shift) {
        Integer objectId = parse(id);
        if (objectId == null) {
            return null;
        }
        MenuMatch object = MenuMatch.any().kind(MenuSubject.LOC).typeId(objectId);
        if (value == -1) {
            return MenuRule.Edit.of(object, shift, Action.DEPRIORITIZE);
        }
        if (value < 0 || value >= OBJECT_OPS.length) {
            return null;
        }
        return MenuRule.Edit.of(object.action(OBJECT_OPS[value]), shift, Action.PROMOTE);
    }

    private MenuRule wornRule(String id, int value, ShiftState shift) {
        String name = name(id);
        if (name == null || value < WORN_FIRST_OP || value > 10) {
            return null;
        }
        return MenuRule.Edit.of(MenuMatch.any().group(InterfaceID.WORNITEMS).target(TextMatch.exact(name))
                .action(MenuAction.CC_OP, value), shift, Action.PROMOTE);
    }

    private MenuRule uiRule(String ids, int value, ShiftState shift) {
        String[] parts = ids.split("_");
        Integer component = parse(parts[0]);
        if (component == null || parts.length > 2 || value < 1 || value > 10) {
            return null;
        }
        MenuMatch match = MenuMatch.any().typeId(component).action(MenuAction.CC_OP, value);
        if (parts.length == 2) {
            String name = name(parts[1]);
            if (name == null) {
                return null;
            }
            match = match.target(TextMatch.exact(name));
        }
        return MenuRule.Edit.of(match, shift, Action.PROMOTE);
    }

    private String name(String id) {
        Integer itemId = parse(id);
        return itemId == null ? null : itemName.apply(itemId);
    }

    private static Integer parse(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The Shift-click withdraw and deposit rules: the chosen op comes to the top on the bank's items and on the
     * inventory beside a bank, deposit box, group storage or Chambers of Xeric storage unit.
     *
     * @return the rules
     */
    private List<MenuRule> bankRules() {
        List<MenuRule> rules = new ArrayList<>();
        ShiftWithdrawMode withdraw = config.bankWithdrawShiftClick();
        if (withdraw != ShiftWithdrawMode.OFF) {
            rules.add(promote(InterfaceID.BANKMAIN, withdraw.getIdentifier()));
            rules.add(promote(InterfaceID.RAIDS_STORAGE_PRIVATE, withdraw.getIdentifierChambersStorageUnit()));
            rules.add(promote(InterfaceID.RAIDS_STORAGE_SHARED, withdraw.getIdentifierChambersStorageUnit()));
        }
        ShiftDepositMode deposit = config.bankDepositShiftClick();
        if (deposit != ShiftDepositMode.OFF) {
            rules.add(promote(InterfaceID.BANKSIDE, deposit.getIdentifier()));
            rules.add(promote(InterfaceID.BANK_DEPOSITBOX, deposit.getIdentifierDepositBox()));
            rules.add(promote(InterfaceID.SHARED_BANK_SIDE, deposit.getIdentifierGroupStorage()));
            rules.add(promote(InterfaceID.RAIDS_STORAGE_SIDE, deposit.getIdentifierChambersStorageUnit()));
        }
        rules.removeIf(rule -> rule == null);
        return rules;
    }

    private static MenuRule promote(int group, int op) {
        if (op <= 0) {
            return null;
        }
        return MenuRule.Edit.of(MenuMatch.any().group(group).action(MenuAction.CC_OP, op), ShiftState.HELD,
                Action.PROMOTE);
    }

    private void setupSwaps() {
        swap("talk-to", "mage of zamorak", "teleport", config::swapAbyssTeleport);
        swap("talk-to", "bank", config::swapBank);
        swap("talk-to", "exchange", config::swapExchange);
        swap("talk-to", "help", config::swapHelp);
        swap("talk-to", "assignment", config::swapAssignment);
        swap("talk-to", "pay", config::swapPay);
        swapContains("talk-to", null, "pay (", config::swapPay);
        swap("talk-to", "trade", config::swapTrade);
        swap("talk-to", "trade-with", config::swapTrade);
        swap("talk-to", "shop", config::swapTrade);
        for (String travel : List.of("travel", "pay-fare", "charter", "take-boat", "fly", "jatizso", "neitiznot",
                "rellekka", "ungael", "pirate's cove", "waterbirth island", "island of stone", "miscellania", "follow",
                "transport")) {
            swap("talk-to", travel, config::swapTravel);
        }
        swap("talk-to", "quick-travel", config::swapQuick);
        for (String npc : ESSENCE_MINE_NPCS) {
            swap("talk-to", npc, "teleport", config::swapEssenceMineTeleport);
        }
        swap("talk-to", "deposit-items", config::swapDepositItems);
        for (String npc : TEMPOROSS_NPCS) {
            swap("talk-to", npc, "leave", config::swapTemporossLeave);
        }

        swap("pass", "energy barrier", "pay-toll(2-ecto)", config::swapTravel);
        swap("open", "gate", "pay-toll(10gp)", config::swapTravel);
        swap("inspect", "trapdoor", "travel", config::swapTravel);
        swap("board", "travel cart", "pay-fare", config::swapTravel);
        swap("board", "sacrificial boat", "quick-board", config::swapQuick);

        swap("cage", "harpoon", config::swapHarpoon);
        swap("big net", "harpoon", config::swapHarpoon);
        swap("net", "harpoon", config::swapHarpoon);
        swap("lure", "bait", config::swapBait);
        swap("net", "bait", config::swapBait);
        swap("small net", "bait", config::swapBait);

        swap("enter", "portal", "home", () -> config.swapHomePortal() == HouseMode.HOME);
        swap("enter", "portal", "build mode", () -> config.swapHomePortal() == HouseMode.BUILD_MODE);
        swap("enter", "portal", "friend's house", () -> config.swapHomePortal() == HouseMode.FRIENDS_HOUSE);

        for (String option : new String[]{"zanaris", "tree"}) {
            swapContains(option, null, "last-destination",
                    () -> config.swapFairyRing() == FairyRingMode.LAST_DESTINATION);
            swapContains(option, null, "configure", () -> config.swapFairyRing() == FairyRingMode.CONFIGURE);
        }
        swapContains("configure", null, "last-destination", () -> config.swapFairyRing() == FairyRingMode.LAST_DESTINATION
                || config.swapFairyRing() == FairyRingMode.ZANARIS);
        swapContains("tree", null, "zanaris", () -> config.swapFairyRing() == FairyRingMode.ZANARIS);

        swap("check", "reset", config::swapBoxTrap);
        swap("dismantle", "reset", config::swapBoxTrap);
        swap("take", "lay", config::swapBoxTrap);
        swap("pick-up", "chase", config::swapChase);
        swap("interact", TextMatch.suffix("birdhouse"), "empty", config::swapBirdhouseEmpty, ShiftState.ANY);

        swap("enter", "quick-enter", config::swapQuick);
        swap("enter-crypt", "quick-enter", config::swapQuick);
        swap("ring", "quick-start", config::swapQuick);
        swap("pass", "quick-pass", config::swapQuick);
        swap("pass", "quick pass", config::swapQuick);
        swap("open", "quick-open", config::swapQuick);
        swap("climb-down", "quick-start", config::swapQuick);
        swap("climb-down", "pay", config::swapQuick);

        swap("admire", "teleport", config::swapAdmire);
        swap("admire", "spellbook", config::swapAdmire);
        swap("admire", "perks", config::swapAdmire);

        for (String destination : JEWELLERY_BOX) {
            swap("teleport menu", destination, config::swapJewelleryBox);
        }
        for (String location : PORTAL_NEXUS) {
            swap(location, "portal nexus", "teleport menu", config::swapPortalNexus);
        }

        swap("shared", "private", config::swapPrivate);
        swap("pick", "pick-lots", config::swapPick);
        swap("view offer", "abort offer", config::swapGEAbort, ShiftState.HELD);

        swap("value", "buy 1", () -> config.shopBuy() == BuyMode.BUY_1, ShiftState.HELD);
        swap("value", "buy 5", () -> config.shopBuy() == BuyMode.BUY_5, ShiftState.HELD);
        swap("value", "buy 10", () -> config.shopBuy() == BuyMode.BUY_10, ShiftState.HELD);
        swap("value", "buy 50", () -> config.shopBuy() == BuyMode.BUY_50, ShiftState.HELD);
        swap("value", "sell 1", () -> config.shopSell() == SellMode.SELL_1, ShiftState.HELD);
        swap("value", "sell 5", () -> config.shopSell() == SellMode.SELL_5, ShiftState.HELD);
        swap("value", "sell 10", () -> config.shopSell() == SellMode.SELL_10, ShiftState.HELD);
        swap("value", "sell 50", () -> config.shopSell() == SellMode.SELL_50, ShiftState.HELD);

        swap("wear", "tele to poh", config::swapTeleToPoh);
        swap("wear", "rub", config::swapTeleportItem);
        swap("wear", "teleport", config::swapTeleportItem);
        swap("wield", "teleport", config::swapTeleportItem);
        swap("wield", "invoke", config::swapTeleportItem);
        swap("wear", "teleports", config::swapTeleportItem);

        swap("wear", "farm teleport", () -> config.swapArdougneCloakMode() == ArdougneCloakMode.FARM);
        swap("wear", "monastery teleport", () -> config.swapArdougneCloakMode() == ArdougneCloakMode.MONASTERY);
        swap("wear", "gem mine", () -> config.swapKaramjaGlovesMode() == KaramjaGlovesMode.GEM_MINE);
        swap("wear", "slayer master", () -> config.swapKaramjaGlovesMode() == KaramjaGlovesMode.SLAYER_MASTER);
        swap("equip", "kourend woodland", () -> config.swapRadasBlessingMode() == RadasBlessingMode.KOUREND_WOODLAND);
        swap("equip", "mount karuulm", () -> config.swapRadasBlessingMode() == RadasBlessingMode.MOUNT_KARUULM);
        swap("wear", "ecto teleport", () -> config.swapMorytaniaLegsMode() == MorytaniaLegsMode.ECTOFUNTUS);
        swap("wear", "burgh teleport", () -> config.swapMorytaniaLegsMode() == MorytaniaLegsMode.BURGH_DE_ROTT);
        swap("wear", "nardah", () -> config.swapDesertAmuletMode() == DesertAmuletMode.NARDAH);
        swap("wear", "kalphite cave", () -> config.swapDesertAmuletMode() == DesertAmuletMode.KALPHITE_CAVE);

        swap("bury", "use", config::swapBones);
        swap("clean", "use", config::swapHerbs);

        swap("collect-note", "collect-item", () -> config.swapGEItemCollect() == GEItemCollectMode.ITEMS);
        swap("collect-notes", "collect-items", () -> config.swapGEItemCollect() == GEItemCollectMode.ITEMS);
        swap("collect-item", "collect-note", () -> config.swapGEItemCollect() == GEItemCollectMode.NOTES);
        swap("collect-items", "collect-notes", () -> config.swapGEItemCollect() == GEItemCollectMode.NOTES);
        swap("collect to inventory", "collect to bank", () -> config.swapGEItemCollect() == GEItemCollectMode.BANK);
        for (String collect : List.of("collect", "collect-note", "collect-notes", "collect-item", "collect-items")) {
            swap(collect, "bank", () -> config.swapGEItemCollect() == GEItemCollectMode.BANK);
        }

        swap("tan 1", "tan all", config::swapTan);

        swap("climb", "climb-up", () -> config.swapStairsLeftClick() == StairsMode.CLIMB_UP, ShiftState.NOT_HELD);
        swap("climb", "climb-down", () -> config.swapStairsLeftClick() == StairsMode.CLIMB_DOWN, ShiftState.NOT_HELD);
        swap("climb", "climb-up", () -> config.swapStairsShiftClick() == StairsMode.CLIMB_UP, ShiftState.HELD);
        swap("climb", "climb-down", () -> config.swapStairsShiftClick() == StairsMode.CLIMB_DOWN, ShiftState.HELD);
    }

    private void swap(String option, String swapped, BooleanSupplier enabled) {
        swap(option, (TextMatch) null, swapped, enabled, ShiftState.ANY);
    }

    private void swap(String option, String swapped, BooleanSupplier enabled, ShiftState shift) {
        swap(option, (TextMatch) null, swapped, enabled, shift);
    }

    private void swap(String option, String target, String swapped, BooleanSupplier enabled) {
        swap(option, TextMatch.exact(target), swapped, enabled, ShiftState.ANY);
    }

    private void swap(String option, TextMatch target, String swapped, BooleanSupplier enabled, ShiftState shift) {
        swaps.add(new Swap(option, target, TextMatch.exact(swapped), enabled, shift));
    }

    private void swapContains(String option, TextMatch target, String swapped, BooleanSupplier enabled) {
        swaps.add(new Swap(option, target, TextMatch.contains(swapped), enabled, ShiftState.ANY));
    }
}
