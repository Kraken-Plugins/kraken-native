package com.kraken.api;

import com.kraken.api.mapping.Mapping;

import java.io.IOException;

/** Shared test helpers: load the bundled seed-build mapping. */
public final class TestSupport {

    public static final String SEED_VERSION = "241.3";
    public static final String SEED_SHA256 = "166231c8f6bd05c7542b8f0d35eb64b0aad701a87ba6ba41f60c4706de946944";

    private TestSupport() {
    }

    public static Mapping seedMapping() throws IOException {
        Mapping mapping = Mapping.bundled(SEED_VERSION);
        if (mapping == null) {
            throw new AssertionError("No bundled " + Mapping.RESOURCE_DIR + SEED_VERSION + ".json on the classpath");
        }
        return mapping;
    }
}
