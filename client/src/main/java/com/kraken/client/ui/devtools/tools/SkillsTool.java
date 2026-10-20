package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.Experience;
import com.kraken.api.GameSession;
import com.kraken.api.Skill;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;

import java.util.ArrayList;
import java.util.List;

/**
 * Every skill's level, base level, boost, XP and XP to the next level, as the client holds them. Compare with the
 * in-game skills tab; boosts and drains show up as a non-zero Boost.
 */
final class SkillsTool extends DevTool<List<SkillsTool.Row>> {
    /**
     * One skill's row.
     *
     * @param id      the skill's id (its ordinal)
     * @param skill   the skill's name
     * @param level   the current level
     * @param base    the base level
     * @param boost   the current level minus the base level
     * @param xp      the XP
     * @param toNext  the XP left to the next level, or null at 99
     */
    record Row(int id, String skill, int level, int base, int boost, int xp, Integer toNext) {
    }

    SkillsTool() {
        super("skills", "Skills", Category.STATE, MaterialDesignA.ARM_FLEX_OUTLINE,
                "Every skill's level, base level, boost, XP and XP to the next level.");
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected List<Row> read(GameSession session) {
        Client client = session.client();
        if (client.getRealSkillLevel(Skill.ATTACK) == -1) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        for (Skill skill : Skill.values()) {
            int level = client.getBoostedSkillLevel(skill);
            int base = client.getRealSkillLevel(skill);
            int xp = client.getSkillExperience(skill);
            rows.add(new Row(skill.ordinal(), skill.getName(), level, base, level - base, xp,
                    base >= Experience.MAX_REAL_LEVEL ? null : Experience.getXpForLevel(base + 1) - xp));
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
