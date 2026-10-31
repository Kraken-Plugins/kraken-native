package com.kraken.client;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Facts about this build of Kraken and where to find out more, from {@code kraken.properties}. The build fills in the
 * version and the commit; a value the build didn't fill in (as when an IDE copies the resource as it is) reads as
 * missing.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KrakenProperties {
    private static final String RESOURCE = "/kraken.properties";
    private static final Properties PROPERTIES = load();

    /**
     * The version Kraken was built as.
     *
     * @return the version, such as {@code 0.1.0-SNAPSHOT}, or null if the build didn't record it
     */
    public static String getVersion() {
        return value(PROPERTIES, "version");
    }

    /**
     * The commit Kraken was built from.
     *
     * @return the abbreviated commit hash, or null if it wasn't built from a git checkout
     */
    public static String getCommit() {
        return value(PROPERTIES, "commit");
    }

    /**
     * Where to report an issue or make a suggestion.
     *
     * @return the issue tracker's address, or null if there is none
     */
    public static String getIssuesLink() {
        return value(PROPERTIES, "issues.link");
    }

    /**
     * Kraken's website.
     *
     * @return the website's address, or null if there is none
     */
    public static String getWebsiteLink() {
        return value(PROPERTIES, "website.link");
    }

    /**
     * The guides to the client, its plugins and the API.
     *
     * @return the guides' address, or null if there are none
     */
    public static String getDocsLink() {
        return value(PROPERTIES, "docs.link");
    }

    /**
     * One value, treating a blank or unfilled {@code ${...}} placeholder as missing.
     *
     * @param properties the properties to read
     * @param key        the key
     * @return the trimmed value, or null if it's missing
     */
    static String value(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank() || value.contains("${")) {
            return null;
        }
        return value.trim();
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = KrakenProperties.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }
}
