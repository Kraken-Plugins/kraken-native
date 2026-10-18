package com.kraken.api;

import com.kraken.api.mapping.Mapping;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared test helpers: locate and load the committed seed-build mapping. */
public final class TestSupport {

    public static final String SEED_VERSION = "241.3";
    public static final String SEED_SHA256 = "166231c8f6bd05c7542b8f0d35eb64b0aad701a87ba6ba41f60c4706de946944";

    private TestSupport() {
    }

    public static Mapping seedMapping() throws IOException {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path file = dir.resolve("mappings").resolve(SEED_VERSION + ".json");
            if (Files.isRegularFile(file)) {
                return Mapping.load(file);
            }
        }
        Assumptions.abort("Seed mapping " + SEED_VERSION + ".json not found above " + start);
        throw new AssertionError("unreachable");
    }
}
