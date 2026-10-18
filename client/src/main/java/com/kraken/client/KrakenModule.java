package com.kraken.client;

import com.google.inject.AbstractModule;
import com.google.inject.name.Names;
import lombok.AllArgsConstructor;

import java.io.File;

/**
 * Bindings for the client. The frame, its panels and the launcher are {@link com.google.inject.Singleton}s that Guice
 * creates on first use, so request the frame on the EDT before anything else.
 */
@AllArgsConstructor
public final class KrakenModule extends AbstractModule {
    private final File exe;

    @Override
    protected void configure() {
        bind(File.class).annotatedWith(Names.named("exe")).toInstance(exe);
    }
}
