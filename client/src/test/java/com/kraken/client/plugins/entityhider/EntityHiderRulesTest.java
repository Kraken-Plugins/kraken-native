package com.kraken.client.plugins.entityhider;

import com.kraken.api.gameval.NpcID;
import com.kraken.client.plugins.entityhider.EntityHiderRules.NpcFacts;
import com.kraken.client.plugins.entityhider.EntityHiderRules.PlayerFacts;
import com.kraken.client.plugins.entityhider.EntityHiderRules.Relation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which players and NPCs Entity Hider draws, layer by layer, for the settings that decide them.
 */
class EntityHiderRulesTest {

    private static final int GOBLIN = 3029;

    private static PlayerFacts other(Relation relation) {
        return new PlayerFacts(false, true, false, relation);
    }

    private static NpcFacts npc(int id) {
        return new NpcFacts(id, false, false, false);
    }

    @Test
    void theDefaultsHideOtherPlayersAndNothingElse() {
        EntityHiderRules rules = new EntityHiderRules(new EntityHiderConfig() {
        });
        assertFalse(rules.drawPlayer(other(Relation.NONE), false));
        assertFalse(rules.drawPlayer(other(Relation.NONE), true));
        assertTrue(rules.drawPlayer(other(Relation.FRIEND), false), "friends are kept");
        assertTrue(rules.drawPlayer(new PlayerFacts(true, true, false, Relation.NONE), false), "the local player");
        assertTrue(rules.drawNpc(npc(GOBLIN), false));
        assertTrue(rules.drawNpc(npc(GOBLIN), true));
    }

    @Test
    void aPlayerWithoutANameIsAlwaysDrawn() {
        EntityHiderRules rules = new EntityHiderRules(new EntityHiderConfig() {
        });
        assertTrue(rules.drawPlayer(new PlayerFacts(false, false, true, Relation.NONE), false));
    }

    @Test
    void theLocalPlayersLayersFollowTheirOwnSettings() {
        EntityHiderRules rules = new EntityHiderRules(new EntityHiderConfig() {
            @Override
            public boolean hideLocalPlayer() {
                return true;
            }
        });
        PlayerFacts local = new PlayerFacts(true, true, false, Relation.FRIEND);
        assertFalse(rules.drawPlayer(local, false), "its model is hidden");
        assertTrue(rules.drawPlayer(local, true), "its 2D elements stay");
    }

    @Test
    void attackersAreHiddenBeforeTheirRelationIsLookedAt() {
        EntityHiderRules rules = new EntityHiderRules(new EntityHiderConfig() {
            @Override
            public boolean hideAttackers() {
                return true;
            }

            @Override
            public boolean hideOthers() {
                return false;
            }
        });
        assertFalse(rules.drawPlayer(new PlayerFacts(false, true, true, Relation.FRIEND), false));
        assertTrue(rules.drawPlayer(other(Relation.FRIEND), false));
    }

    @Test
    void eachRelationHasItsOwnSetting() {
        EntityHiderRules rules = new EntityHiderRules(new EntityHiderConfig() {
            @Override
            public boolean hideOthers() {
                return false;
            }

            @Override
            public boolean hideFriendsChatMembers() {
                return true;
            }

            @Override
            public boolean hideIgnores() {
                return true;
            }
        });
        assertTrue(rules.drawPlayer(other(Relation.FRIEND), false));
        assertFalse(rules.drawPlayer(other(Relation.FRIENDS_CHAT), false));
        assertTrue(rules.drawPlayer(other(Relation.CLAN), false));
        assertFalse(rules.drawPlayer(other(Relation.IGNORED), true));
        assertTrue(rules.drawPlayer(other(Relation.NONE), false));
    }

    @Test
    void npcsByTheirLayersPetsThrallsAndRandomEvents() {
        EntityHiderRules rules = new EntityHiderRules(new EntityHiderConfig() {
            @Override
            public boolean hideNPCs2D() {
                return true;
            }

            @Override
            public boolean hidePets() {
                return true;
            }

            @Override
            public boolean hideThralls() {
                return true;
            }
        });
        assertTrue(rules.drawNpc(npc(GOBLIN), false));
        assertFalse(rules.drawNpc(npc(GOBLIN), true), "2D only");
        assertFalse(rules.drawNpc(new NpcFacts(GOBLIN, true, false, false), false), "another player's pet");
        assertFalse(rules.drawNpc(npc(NpcID.ARCEUUS_THRALL_GHOST_LESSER), false));
        assertTrue(rules.drawNpc(npc(NpcID.MACRO_GENI), false), "random events are kept");
    }

    @Test
    void dyingNpcsAreHiddenEvenWhileAttacking() {
        EntityHiderRules rules = new EntityHiderRules(new EntityHiderConfig() {
            @Override
            public boolean hideDeadNpcs() {
                return true;
            }
        });
        assertTrue(rules.hidesDeadNpcs());
        assertFalse(rules.drawNpc(new NpcFacts(GOBLIN, false, true, true), false));
        assertTrue(rules.drawNpc(new NpcFacts(GOBLIN, false, false, true), false));
    }

    @Test
    void hidingAttackersOnlyHidesTheLayerNpcsAreHiddenIn() {
        EntityHiderRules everyLayer = new EntityHiderRules(new EntityHiderConfig() {
            @Override
            public boolean hideAttackers() {
                return true;
            }
        });
        NpcFacts attacker = new NpcFacts(GOBLIN, false, false, true);
        assertFalse(everyLayer.drawNpc(attacker, false), "with NPCs shown, attackers are hidden whole");
        assertFalse(everyLayer.drawNpc(attacker, true));

        EntityHiderRules twoDOnly = new EntityHiderRules(new EntityHiderConfig() {
            @Override
            public boolean hideAttackers() {
                return true;
            }

            @Override
            public boolean hideNPCs2D() {
                return true;
            }
        });
        assertTrue(twoDOnly.drawNpc(attacker, false), "the model stays, as other NPCs' do");
        assertFalse(twoDOnly.drawNpc(attacker, true));
    }
}
