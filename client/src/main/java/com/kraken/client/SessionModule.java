package com.kraken.client;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import com.kraken.api.Client;
import com.kraken.api.GameSession;
import com.kraken.api.mapping.Mapping;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.callback.NativeEventServer;
import com.kraken.client.input.KeyManager;
import com.kraken.client.worldhopper.WorldHopper;
import lombok.AllArgsConstructor;

/**
 * Bindings for the attached game. {@link ProcessAttacher} installs this in a child of the client's injector (the
 * session injector) once the API attaches. Kraken runs one game per launch, so each binding here is a singleton for
 * the rest of it. Every plugin's injector is a child of the session injector.
 *
 * <p>Binds the {@link GameSession}, its {@link Client} and {@link Mapping}, the game's process id as
 * {@code @Named("pid") long}, and the services that only work once the game is attached ({@link ClientThread},
 * {@link NativeEventServer}, {@link KeyManager}, {@link WorldHopper}). Bind such services here explicitly, so every
 * plugin shares one.
 */
@AllArgsConstructor
public final class SessionModule extends AbstractModule {
    private final GameSession session;
    private final long pid;

    @Override
    protected void configure() {
        bind(GameSession.class).toInstance(session);
        bind(Client.class).toInstance(session.client());
        bind(Mapping.class).toInstance(session.mapping());
        bindConstant().annotatedWith(Names.named("pid")).to(pid);
        bind(ClientThread.class).in(Singleton.class);
        bind(NativeEventServer.class).in(Singleton.class);
        bind(KeyManager.class).in(Singleton.class);
        bind(WorldHopper.class).in(Singleton.class);
    }
}
