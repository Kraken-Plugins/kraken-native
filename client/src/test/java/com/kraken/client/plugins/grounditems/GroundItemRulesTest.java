package com.kraken.client.plugins.grounditems;

import com.kraken.api.eventbus.EventBus;
import com.kraken.api.gameval.ItemID;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.plugins.grounditems.config.HighlightTier;
import com.kraken.client.plugins.grounditems.config.ItemHighlightMode;
import com.kraken.client.plugins.grounditems.config.MenuHighlightMode;
import com.kraken.client.plugins.grounditems.config.OwnershipFilterMode;
import com.kraken.client.plugins.grounditems.config.ValueCalculationMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the settings make of ground items: lists, value tiers and colours, hiding by value, the value modes, the
 * ownership filter, and the notification, lootbeam and menu text an item would get.
 */
class GroundItemRulesTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private ConfigManager manager;
    private GroundItemsConfig config;
    private final Map<Integer, Color> itemColors = new HashMap<>();

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        manager = new ConfigManager(dir.resolve("settings.properties"), scheduler, new EventBus());
        config = manager.getConfig(GroundItemsConfig.class);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void listsAndValueTiersPickTheColour() {
        config.setHighlightedItem("Dragon*, Bones");
        GroundItemRules rules = rules();
        GroundItem bones = item("Bones", 1, 0);
        rules.updateItemColor(bones);
        assertTrue(bones.highlighted, "named on the highlighted list");
        assertEquals(config.highlightedColor(), bones.color);

        GroundItem rune = item("Rune platebody", 1, 23_400);
        rules.updateItemColor(rune);
        assertEquals(config.lowValueColor(), rune.color, "worth more than the low value price");

        GroundItem pricey = item("Twisted bow", 1, 1_500_000);
        rules.updateItemColor(pricey);
        assertEquals(config.highValueColor(), pricey.color, "the highest tier it is above");

        GroundItem plain = item("Logs", 1, 4);
        rules.updateItemColor(plain);
        assertFalse(plain.highlighted);
        assertEquals(config.defaultColor(), plain.color);
    }

    @Test
    void anItemsOwnColourBeatsItsTierButNotTheHiddenList() {
        itemColors.put(100, Color.RED);
        GroundItemRules rules = rules();
        GroundItem own = item("Ruby", 1, 1_000_000_000);
        own.setItemId(100);
        rules.updateItemColor(own);
        assertEquals(Color.RED, own.color);

        GroundItem bones = item("Bones", 1, 0);
        bones.setItemId(100);
        rules.updateItemColor(bones);
        assertTrue(bones.hidden, "Bones is on the default hidden list");
        assertEquals(config.hiddenColor(), bones.color);
    }

    @Test
    void anExactHiddenEntryBeatsAWildcardHighlightAndAnExactHighlightBeatsAll() {
        config.setHighlightedItem("Bone*");
        GroundItemRules rules = rules();
        assertEquals(GroundItemRules.HIDDEN, rules.isHiddenOrHighlighted(item("Bones", 1, 0)));
        assertEquals(GroundItemRules.HIGHLIGHTED, rules.isHiddenOrHighlighted(item("Bone shard", 1, 0)));
        config.setHighlightedItem("Bones");
        assertEquals(GroundItemRules.HIGHLIGHTED, rules().isHiddenOrHighlighted(item("Bones", 1, 0)));
    }

    @Test
    void itemsUnderTheHideValueAreHiddenUnlessUntradeable() {
        manager.setConfiguration(GroundItemsConfig.GROUP, "hideUnderValue", 100);
        GroundItemRules rules = rules();
        assertTrue(rules.isHidden(item("Logs", 1, 4)), "tradeable and worth 4");
        assertFalse(rules.isHidden(item("Logs", 30, 4)), "a stack worth 120");
        GroundItem untradeable = item("Quest thing", 1, 0);
        untradeable.setTradeable(false);
        assertFalse(rules.isHidden(untradeable), "untradeables are kept by default");
        manager.setConfiguration(GroundItemsConfig.GROUP, "dontHideUntradeables", false);
        assertTrue(rules.isHidden(untradeable));
    }

    @Test
    void theValueModesUseTheHighAlchemyValueWhenNoPriceIsKnown() {
        GroundItemRules rules = rules();
        assertEquals(500, rules.getValueByMode(0, 500), "highest of an unknown price and the value");
        manager.setConfiguration(GroundItemsConfig.GROUP, "highlightValueCalculation", ValueCalculationMode.GE);
        assertEquals(0, rules.getValueByMode(0, 500));
        manager.setConfiguration(GroundItemsConfig.GROUP, "highlightValueCalculation", ValueCalculationMode.HA);
        assertEquals(500, rules.getValueByMode(1000, 500));
    }

    @Test
    void onlyCoinsAndPlatinumTokensHaveAPrice() {
        assertEquals(1, GroundItemsPlugin.gePrice(ItemID.COINS));
        assertEquals(1000, GroundItemsPlugin.gePrice(ItemID.PLATINUM));
        assertEquals(0, GroundItemsPlugin.gePrice(ItemID.LOGS));
    }

    @Test
    void theOwnershipFilter() {
        assertTrue(GroundItemRules.shouldDisplayItem(OwnershipFilterMode.ALL, GroundItem.OWNERSHIP_OTHER, 1));
        assertTrue(GroundItemRules.shouldDisplayItem(OwnershipFilterMode.DROPS, GroundItem.OWNERSHIP_GROUP, 0));
        assertFalse(GroundItemRules.shouldDisplayItem(OwnershipFilterMode.DROPS, GroundItem.OWNERSHIP_NONE, 0));
        assertTrue(GroundItemRules.shouldDisplayItem(OwnershipFilterMode.TAKEABLE, GroundItem.OWNERSHIP_OTHER, 0));
        assertFalse(GroundItemRules.shouldDisplayItem(OwnershipFilterMode.TAKEABLE, GroundItem.OWNERSHIP_OTHER, 1));
    }

    @Test
    void notificationsForHighlightedAndValuableDrops() {
        config.setHighlightedItem("Dragon bones");
        manager.setConfiguration(GroundItemsConfig.GROUP, "notifyHighlightedDrops", true);
        manager.setConfiguration(GroundItemsConfig.GROUP, "notifyTier", HighlightTier.HIGH);
        GroundItemRules rules = rules();
        assertEquals("You received a highlighted drop: Dragon bones (25)",
                rules.notification(item("Dragon bones", 25, 1)));
        assertEquals("You received a valuable drop: Twisted bow", rules.notification(item("Twisted bow", 1,
                2_000_000)));
        assertNull(rules.notification(item("Logs", 1, 4)));
    }

    @Test
    void lootbeamsForHighlightedItemsAndTiers() {
        GroundItemRules rules = rules();
        assertEquals(config.highValueColor(), rules.lootbeamColor(List.of(item("Logs", 1, 4),
                item("Twisted bow", 1, 2_000_000)), 0), "the default high tier");
        assertNull(rules.lootbeamColor(List.of(item("Logs", 1, 4)), 0));
        config.setHighlightedItem("Logs");
        manager.setConfiguration(GroundItemsConfig.GROUP, "showLootbeamForHighlighted", true);
        assertEquals(config.highlightedColor(), rules().lootbeamColor(List.of(item("Logs", 1, 4)), 0));
    }

    @Test
    void menuTextRecoloursHighlightedItemsAndAddsTheQuantity() {
        config.setHighlightedItem("Logs");
        GroundItemRules rules = rules();
        GroundItem logs = item("Logs", 3, 4);
        logs.setStackable(true);
        rules.updateItemColor(logs);
        GroundItemRules.MenuText text = rules.menuText("Take", "<col=ff9040>Logs", logs);
        assertEquals("Take", text.option());
        assertEquals("<col=aa00ff>Logs (3)", text.target());
        assertFalse(text.deprioritized());

        manager.setConfiguration(GroundItemsConfig.GROUP, "menuHighlightMode", MenuHighlightMode.BOTH);
        assertEquals("<col=aa00ff>Take", rules.menuText("Take", "<col=ff9040>Logs", logs).option());
        manager.setConfiguration(GroundItemsConfig.GROUP, "itemHighlightMode", ItemHighlightMode.OVERLAY);
        assertEquals("<col=ff9040>Logs (3)", rules.menuText("Take", "<col=ff9040>Logs", logs).target());
    }

    @Test
    void hiddenItemsCanBeDeprioritized() {
        manager.setConfiguration(GroundItemsConfig.GROUP, "deprioritizeHiddenItems", true);
        GroundItemRules rules = rules();
        GroundItem bones = item("Bones", 1, 0);
        rules.updateItemColor(bones);
        assertTrue(rules.menuText("Take", "<col=ff9040>Bones", bones).deprioritized());
    }

    private GroundItemRules rules() {
        return new GroundItemRules(config, itemColors::get);
    }

    /**
     * A tradeable item with no Grand Exchange price.
     *
     * @param name     its name
     * @param quantity how many
     * @param haPrice  the high alchemy value of one
     * @return the item
     */
    private static GroundItem item(String name, int quantity, int haPrice) {
        return GroundItem.builder().name(name).itemId(1).id(1).quantity(quantity).haPrice(haPrice).tradeable(true)
                .build();
    }
}
