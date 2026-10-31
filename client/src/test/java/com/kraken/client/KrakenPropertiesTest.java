package com.kraken.client;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build facts and links read from {@code kraken.properties}.
 */
class KrakenPropertiesTest {

    @Test
    void theBuildFillsInTheVersion() {
        String version = KrakenProperties.getVersion();
        assertNotNull(version, "the build fills in the version");
        assertFalse(version.contains("$"), version);
    }

    @Test
    void linksAreWebAddresses() {
        for (String link : new String[]{KrakenProperties.getIssuesLink(), KrakenProperties.getWebsiteLink(),
                KrakenProperties.getDocsLink()}) {
            assertNotNull(link);
            assertTrue(link.startsWith("https://"), link);
        }
    }

    @Test
    void blankAndUnfilledValuesReadAsMissing() {
        Properties properties = new Properties();
        properties.setProperty("version", "${version}");
        properties.setProperty("commit", " ");
        properties.setProperty("docs.link", " https://example.com ");
        assertNull(KrakenProperties.value(properties, "version"), "a placeholder the build didn't fill in");
        assertNull(KrakenProperties.value(properties, "commit"), "a blank value");
        assertNull(KrakenProperties.value(properties, "missing"));
        assertEquals("https://example.com", KrakenProperties.value(properties, "docs.link"));
    }
}
