package com.kraken.api;

import lombok.Getter;

/**
 * The kind of a chat line. The numbers are the client's own chat types ({@code ChatLine.type}), which the server
 * sends with each message.
 */
public enum ChatMessageType {

    /**
     * A plain game message.
     */
    GAMEMESSAGE(0),

    /**
     * A public chat message from a moderator.
     */
    MODCHAT(1),

    /**
     * A public chat message.
     */
    PUBLICCHAT(2),

    /**
     * A private message from another player.
     */
    PRIVATECHAT(3),

    /**
     * A message from the game engine.
     */
    ENGINE(4),

    /**
     * A friend logging in or out.
     */
    LOGINLOGOUTNOTIFICATION(5),

    /**
     * A private message sent to another player.
     */
    PRIVATECHATOUT(6),

    /**
     * A private message from a moderator.
     */
    MODPRIVATECHAT(7),

    /**
     * A friends chat message.
     */
    FRIENDSCHAT(9),

    /**
     * A notice about the friends chat the player is in.
     */
    FRIENDSCHATNOTIFICATION(11),

    /**
     * A trade request being sent.
     */
    TRADE_SENT(12),

    /**
     * A game broadcast.
     */
    BROADCAST(14),

    /**
     * An abuse report submitted.
     */
    SNAPSHOTFEEDBACK(26),

    /**
     * An item's examine text.
     */
    ITEM_EXAMINE(27),

    /**
     * An NPC's examine text.
     */
    NPC_EXAMINE(28),

    /**
     * An object's examine text.
     */
    OBJECT_EXAMINE(29),

    /**
     * A player added to the friend list.
     */
    FRIENDNOTIFICATION(30),

    /**
     * A player added to the ignore list.
     */
    IGNORENOTIFICATION(31),

    /**
     * A clan chat message.
     */
    CLAN_CHAT(41),

    /**
     * A clan system message.
     */
    CLAN_MESSAGE(43),

    /**
     * A guest clan chat message.
     */
    CLAN_GUEST_CHAT(44),

    /**
     * A guest clan system message.
     */
    CLAN_GUEST_MESSAGE(46),

    /**
     * An autotyper message from a player.
     */
    AUTOTYPER(90),

    /**
     * An autotyper message from a moderator.
     */
    MODAUTOTYPER(91),

    /**
     * A console message, such as a setting being changed.
     */
    CONSOLE(99),

    /**
     * Another player offering a trade.
     */
    TRADEREQ(101),

    /**
     * A trade or duel completing.
     */
    TRADE(102),

    /**
     * Another player offering a duel.
     */
    CHALREQ_TRADE(103),

    /**
     * Another player offering a friends chat challenge.
     */
    CHALREQ_FRIENDSCHAT(104),

    /**
     * A filtered message.
     */
    SPAM(105),

    /**
     * A message about the player.
     */
    PLAYERRELATED(106),

    /**
     * A message that times out after ten seconds.
     */
    TENSECTIMEOUT(107),

    /**
     * The welcome message after logging in.
     */
    WELCOME(108),

    /**
     * An invitation to create a clan.
     */
    CLAN_CREATION_INVITATION(109),

    /**
     * A clan wars challenge.
     */
    CHALREQ_CLANCHAT(110),

    /**
     * An invitation to form a group ironman group.
     */
    CLAN_GIM_FORM_GROUP(111),

    /**
     * A request to join a group ironman group.
     */
    CLAN_GIM_GROUP_WITH(112),

    /**
     * A dialogue line.
     */
    DIALOG(114),

    /**
     * A message box.
     */
    MESBOX(115),

    /**
     * An NPC speaking overhead.
     */
    NPC_SAY(116),

    /**
     * A "Did you know?" tip.
     */
    DIDYOUKNOW(117),

    /**
     * A level-up message.
     */
    LEVELUPMESSAGE(118),

    /**
     * A chat type this enum doesn't know.
     */
    UNKNOWN(-1);

    /**
     * The client's number for this chat type.
     */
    @Getter
    private final int type;

    ChatMessageType(int type) {
        this.type = type;
    }

    /**
     * The chat type with a number.
     *
     * @param type the client's chat type
     * @return the matching type, or {@link #UNKNOWN} for a number this enum doesn't know
     */
    public static ChatMessageType of(int type) {
        for (ChatMessageType chatMessageType : values()) {
            if (chatMessageType.type == type) {
                return chatMessageType;
            }
        }
        return UNKNOWN;
    }
}
