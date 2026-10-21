package com.kraken.api.events;

import com.kraken.api.scene.GraphicsObject;
import lombok.Value;

/**
 * A graphics object has appeared in the top-level world's list: the server started a spot anim on a tile. Posted once
 * per object, the first poll that sees it, which may be before its start cycle. Objects already playing when tracking
 * starts aren't posted.
 */
@Value
public class GraphicsObjectCreated {

    /**
     * The new graphics object, as read when it appeared.
     */
    GraphicsObject graphicsObject;
}
