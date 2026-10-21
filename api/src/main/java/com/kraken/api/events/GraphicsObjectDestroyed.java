package com.kraken.api.events;

import com.kraken.api.scene.GraphicsObject;
import lombok.Value;

/**
 * A graphics object has left the top-level world's list: its animation ended, the world's plane changed, or the
 * player logged out. Posted once per object, the first poll that no longer sees it, including objects that were
 * already playing when tracking started (which had no {@link GraphicsObjectCreated}).
 */
@Value
public class GraphicsObjectDestroyed {

    /**
     * The graphics object that left, as last read (its frame is the last one seen). It equals the object
     * {@link GraphicsObjectCreated} carried for it.
     */
    GraphicsObject graphicsObject;
}
