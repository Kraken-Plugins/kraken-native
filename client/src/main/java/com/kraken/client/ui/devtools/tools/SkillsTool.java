package com.kraken.client.ui.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.lua.ClientState;
import com.kraken.api.lua.ClientState.Stats;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;

import java.util.ArrayList;
import java.util.List;

/**
 * Every stat as the ClientState stat functions return it: effective and base level, XP and XP to the next level.
 * Compare with the in-game skills tab; boosts and drains show up as a non-zero Boost.
 */
final class SkillsTool extends DevTool<List<SkillsTool.Row>> {
    record Row(int id, String skill, int level, int base, int boost, int xp, Integer toNext) {
    }

    SkillsTool() {
        super("skills", "Skills", Category.STATE, MaterialDesignA.ARM_FLEX_OUTLINE,
                "Every skill's level, base level, boost, XP and XP to the next level (getStat* functions).");
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected List<Row> read(KrakenApi api) {
        ClientState state = api.lua().clientState();
        if (!state.areStatsTransmitted()) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        for (Stats stat : Stats.values()) {
            int level = state.getStatEffectiveLevel(stat);
            int base = state.getStatBaseLevel(stat);
            int xp = state.getStatXP(stat);
            int toNext = state.getStatRemainingXPUntilLevel(stat);
            rows.add(new Row(stat.id(), stat.displayName(), level, base, level - base, xp, base >= 99 ? null : toNext));
        }
        return rows;
    }

    @Override
    protected ToolView<List<Row>> createView() {
        return TableView.<Row>builder("skills")
                .column("Id", Integer.class, 36, Row::id)
                .column("Skill", String.class, 110, Row::skill)
                .column("Level", Integer.class, 52, Row::level)
                .column("Base", Integer.class, 52, Row::base)
                .column("Boost", Integer.class, 52, Row::boost)
                .column("XP", Integer.class, 90, Row::xp)
                .column("To next", Integer.class, 80, Row::toNext)
                .key(Row::id)
                .build();
    }
}
