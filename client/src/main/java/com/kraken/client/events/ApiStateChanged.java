package com.kraken.client.events;

import com.kraken.client.ApiConnection;
import lombok.Value;

/**
 * The client's link to the game changed state: attaching, attached, an unsupported build, or a failure. Kraken
 * specific; RuneLite's API is always attached.
 *
 * <p>Posted on the {@code kraken-api-attach} thread. {@link ApiConnection.State#ATTACHED} is posted before the API's
 * first poll, so it comes before the replayed game state and spawns.
 */
@Value
public class ApiStateChanged {

    /**
     * The new state.
     */
    ApiConnection.State state;

    /**
     * One line describing it, as the Kraken panel's status shows.
     */
    String detail;
}
