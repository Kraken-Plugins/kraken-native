package com.kraken.api;

import com.kraken.api.mapping.Mapping;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared test helpers: locate and load the committed seed-build mapping. */
public final class TestSupport {

    public static final String SEED_SHA16 = "166231c8f6bd05c7";

    private TestSupport() {
    }

    public static Mapping seedMapping() throws IOException {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path file = dir.resolve("mappings").resolve(SEED_SHA16 + ".json");
            if (Files.isRegularFile(file)) {
                return Mapping.load(file, SEED_SHA16);
            }
        }
        Assumptions.abort("Seed mapping " + SEED_SHA16 + ".json not found above " + start);
        throw new AssertionError("unreachable");
    }
}
