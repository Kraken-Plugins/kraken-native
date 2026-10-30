package com.kraken.client.game;

import com.kraken.api.actor.NPC;
import com.kraken.api.definition.NPCComposition;
import com.kraken.api.gameval.NpcID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.Set;

/**
 * Whether an NPC is dying: its health bar has run out and it can no longer be fought, as opposed to an NPC that sits
 * at no health without dying or turns into another form.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NpcUtil {

    /**
     * The NPC param that, set, keeps an NPC from counting as dying.
     */
    private static final int NPC_DEATH_HIDER_EXCLUDE = 1799;

    /**
     * NPCs that reach no health without dying (slayer monsters killed with an item, and the like), or die into a
     * form that can still be fought or used.
     */
    private static final Set<Integer> NEVER_DYING = Set.of(
            NpcID.SLAYER_GARGOYLE_1, NpcID.SLAYER_CAVE_GARGOYLE, NpcID.SUPERIOR_GARGOYLE, NpcID.GARGBOSS_DAWN_PHASE3,
            NpcID.GARGBOSS_DUSK_PHASE4, NpcID.SLAYER_MUTATED_ZYGOMITE_ADOLESCENT, NpcID.SLAYER_MUTATED_ZYGOMITE_ADULT,
            NpcID.FOSSIL_ZYGOMITE, NpcID.SLAYER_ROCKSLUG, NpcID.SLAYER_ROCKSLUG_BABY, NpcID.SUPERIOR_ROCKSLUG,
            NpcID.SLAYER_LIZARD_LARGE1_GREEN, NpcID.SLAYER_LIZARD_LARGE2_SANDY, NpcID.SLAYER_LIZARD_LARGE3_SANDY,
            NpcID.SLAYER_LIZARD_MASSIVE, NpcID.SLAYER_LIZARD_SMALL1_GREEN, NpcID.SLAYER_LIZARD_SMALL2_SANDY,
            NpcID.GB_GROWTHLING, NpcID.COLOSSEUM_MODIFIER_BEES, NpcID.KALPHITE_QUEEN, NpcID.VETION,
            NpcID.VETION_SINGLE, NpcID.SHAPESHIFTERGLOB, NpcID.NZONE_SHAPESHIFTERGLOB_NORMAL,
            NpcID.NZONE_SHAPESHIFTERGLOB_HARD, NpcID.SHAPESHIFTERSPIDER, NpcID.NZONE_SHAPESHIFTERSPIDER_NORMAL,
            NpcID.NZONE_SHAPESHIFTERSPIDER_HARD, NpcID.SHAPESHIFTERBEAR, NpcID.NZONE_SHAPESHIFTERBEAR_NORMAL,
            NpcID.NZONE_SHAPESHIFTERBEAR_HARD, NpcID.ZQ_MAINZOMBIE1, NpcID.ZQ_MAINZOMBIE2,
            NpcID.NZONE_ZQ_MAINZOMBIE1_NORMAL, NpcID.NZONE_ZQ_MAINZOMBIE2_NORMAL, NpcID.NZONE_ZQ_MAINZOMBIE1_HARD,
            NpcID.NZONE_ZQ_MAINZOMBIE2_HARD, NpcID.KOLHUMAN, NpcID.KOLOGRE, NpcID.KOLSPIDER, NpcID.KOLETHEREAL,
            NpcID.LOTR_TRAN_RAZORLOR_MUTANT, NpcID.AKD_XAMPHUR_COMBAT, NpcID.AKD_XAMPHUR_COMBAT_NOHANDS,
            NpcID.VIKING_ENEMY1, NpcID.VIKING_ENEMY2, NpcID.VIKING_ENEMY3, NpcID.FD_DAMIS_NORMAL,
            NpcID.NZONE_FD_DAMIS_NORMAL_NORMAL, NpcID.NZONE_FD_DAMIS_NORMAL_HARD, NpcID.CHOMPYBIRD,
            NpcID._100_JUBBLY_BIRD, NpcID.WILDERNESS_ENT, NpcID.WCGUILD_ENT, NpcID.SOULBANE_HOPE_MONST3,
            NpcID.SOULBANE_HOPE_MONST2, NpcID.BURGH_GADDERANKS_ATTACKABLE, NpcID.SWAMP_WALLBEAST_COMBAT,
            NpcID.WILDERNESS_RUNE_GOLEM, NpcID.WILDERNESS_GOLEM_RUNE_ROCK, NpcID.MUSPAH_FINAL_QUEST,
            NpcID.SHADOW_MAJ_BOUNCER, NpcID.NEWBIEGIANTRAT, NpcID.AGRITH_NAAR);

    /**
     * The dead forms of NPCs that could be fought: they have no attack option, but are dying.
     */
    private static final Set<Integer> ALWAYS_DYING = Set.of(
            NpcID.DRAKE_DEATH, NpcID.SUPERIOR_DRAKE_DEATH, NpcID.HYDRABOSS_FINALDEATH, NpcID.XARPUS_DEATH,
            NpcID.XARPUS_DEATH_STORY, NpcID.XARPUS_DEATH_HARD, NpcID.NIGHTMARE_DYING, NpcID.NIGHTMARE_CHALLENGE_DYING,
            NpcID.SLAYER_GARGOYLE_DEAD, NpcID.SUPERIOR_GARGOYLE_DEAD, NpcID.GARGBOSS_DAWN_DEATH,
            NpcID.GARGBOSS_DUSK_DEATH);

    /**
     * Whether an NPC is dying and can no longer be fought. Most NPCs are dying when they have an Attack option and
     * {@link NPC#isDead()}; the exceptions are listed above. Amoxliatl's health bar is read on its own, as an empty
     * head bar.
     *
     * @param npc the NPC
     * @return true if it is dying
     */
    public static boolean isDying(NPC npc) {
        int id = npc.getId();
        if (NEVER_DYING.contains(id)) {
            return false;
        }
        if (ALWAYS_DYING.contains(id)) {
            return true;
        }
        if (id == NpcID.ZALCANO_WEAK) {
            return npc.isDead();
        }
        if (id == NpcID.AMOXLIATL) {
            return npc.isHeadbarEmpty();
        }
        NPCComposition composition = npc.getTransformedComposition();
        if (composition == null || !npc.isDead()) {
            return false;
        }
        boolean attackable = Arrays.asList(composition.getActions()).contains("Attack");
        // An unset param reads -1
        return attackable && composition.getIntValue(NPC_DEATH_HIDER_EXCLUDE) <= 0;
    }
}
