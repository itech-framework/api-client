package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.utils.PlaceholderResolver;
import io.github.itech_framework.core.exceptions.FrameworkException;
import io.github.itech_framework.core.utils.PropertiesLoader;

class PlaceholderResolverTest {

    private PropertiesLoader.Snapshot original;

    @BeforeEach
    void saveState() {
        original = PropertiesLoader.snapshot();
    }

    @AfterEach
    void restoreState() {
        PropertiesLoader.restore(original);
    }

    private static void useProperties(String... keysAndValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            properties.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        PropertiesLoader.restore(new PropertiesLoader.Snapshot(Set.of(), Map.of(), properties, "test"));
    }

    @Test
    void resolvesKnownPlaceholders() {
        useProperties("api.host", "example.com", "api.version", "v2");

        assertEquals("https://example.com/v2", PlaceholderResolver.resolve("https://${api.host}/${api.version}"));
    }

    @Test
    void anEmptyPropertyValueIsAllowed() {
        useProperties("api.token", "");

        assertEquals("Bearer ", PlaceholderResolver.resolve("Bearer ${api.token}"));
    }

    @Test
    void aMissingPropertyThrowsAndNamesTheKey() {
        useProperties();

        FrameworkException failure = assertThrows(FrameworkException.class,
                () -> PlaceholderResolver.resolve("${no.such.key}"));

        assertTrue(failure.getMessage().contains("no.such.key"), failure.getMessage());
    }

    @Test
    void theErrorMessageDoesNotRepeatTheSurroundingText() {
        useProperties();

        FrameworkException failure = assertThrows(FrameworkException.class,
                () -> PlaceholderResolver.resolve("my-secret-prefix-${missing.one}"));

        assertFalse(failure.getMessage().contains("my-secret-prefix"), failure.getMessage());
    }

    @Test
    void textWithoutPlaceholdersIsReturnedAsIs() {
        useProperties();

        assertEquals("plain text", PlaceholderResolver.resolve("plain text"));
    }

    @Test
    void nullAndEmptyInputGiveAnEmptyString() {
        assertEquals("", PlaceholderResolver.resolve(null));
        assertEquals("", PlaceholderResolver.resolve(""));
    }
}