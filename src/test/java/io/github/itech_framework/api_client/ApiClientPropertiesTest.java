package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.ApiClient;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.processor.DynamicApiProcessor;
import io.github.itech_framework.core.exceptions.FrameworkException;
import io.github.itech_framework.core.module.ComponentRegistry;
import io.github.itech_framework.core.resourcecs.CleanupRegistry;
import io.github.itech_framework.core.store.ComponentStore;
import io.github.itech_framework.core.utils.PropertiesLoader;

public class ApiClientPropertiesTest extends BaseClientTest {

    @ApiClient
    public interface PropsApi {
        @GET("/ping")
        String ping();
    }

    @ApiClient
    public interface BadApi {
        @GET("/ping")
        String ping();
    }

    @ApiClient
    public interface InfoApi {
        @GET("/ping")
        String ping();
    }

    @ApiClient(baseUrl = "http://localhost:1")
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface MetaClient {
    }

    @MetaClient
    public interface MetaApi {
        @GET("/ping")
        String ping();
    }

    private PropertiesLoader.Snapshot originalProperties;
    private Locale originalLocale;

    @BeforeEach
    void saveState() {
        originalProperties = PropertiesLoader.snapshot();
        originalLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreState() {
        PropertiesLoader.restore(originalProperties);
        Locale.setDefault(originalLocale);
    }

    private static void useProperties(String... keysAndValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            properties.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        PropertiesLoader.restore(new PropertiesLoader.Snapshot(Set.of(), Map.of(), properties, "test"));
    }

    private static String pingThroughANewContext() {
        ComponentStore store = new ComponentStore();
        CleanupRegistry cleanup = new CleanupRegistry();
        try {
            DynamicApiProcessor.processApiClient(PropsApi.class, new ComponentRegistry(store), cleanup);
            return ((PropsApi) store.getComponent(PropsApi.class.getName())).ping();
        } finally {
            cleanup.cleanup();
        }
    }

    private static void processOnly(Class<?> api) {
        ComponentStore store = new ComponentStore();
        CleanupRegistry cleanup = new CleanupRegistry();
        try {
            DynamicApiProcessor.processApiClient(api, new ComponentRegistry(store), cleanup);
        } finally {
            cleanup.cleanup();
        }
    }

    @Test
    void eachContextReadsItsOwnProperties() throws Exception {
        TestServer first = server();
        first.route("/ping", (request, call) -> Response.of(200, "first"));
        TestServer second = server();
        second.route("/ping", (request, call) -> Response.of(200, "second"));

        useProperties("api.client.propsapi.base.url", first.url());
        assertEquals("first", pingThroughANewContext());

        useProperties("api.client.propsapi.base.url", second.url());
        assertEquals("second", pingThroughANewContext());
    }

    @Test
    void legacyKeysStillWork() throws Exception {
        TestServer server = server();
        server.route("/ping", (request, call) -> Response.of(200, "legacy"));

        useProperties("api.client.propsapi.baseUrl", server.url());

        assertEquals("legacy", pingThroughANewContext());
    }

    @Test
    void dottedKeyWinsOverLegacyKey() throws Exception {
        TestServer dotted = server();
        dotted.route("/ping", (request, call) -> Response.of(200, "dotted"));
        TestServer legacy = server();
        legacy.route("/ping", (request, call) -> Response.of(200, "legacy"));

        useProperties("api.client.propsapi.base.url", dotted.url(), "api.client.propsapi.baseUrl", legacy.url());

        assertEquals("dotted", pingThroughANewContext());
    }

    @Test
    void acceptsAnApiClientAnnotationThatComesFromAMetaAnnotation() {
        ComponentStore store = new ComponentStore();
        CleanupRegistry cleanup = new CleanupRegistry();
        try {
            DynamicApiProcessor.processApiClient(MetaApi.class, new ComponentRegistry(store), cleanup);
            assertTrue(store.hasComponent(MetaApi.class.getName()));
        } finally {
            cleanup.cleanup();
        }
    }

    @Test
    void propertyKeysDoNotDependOnTheDefaultLocale() throws Exception {
        TestServer server = server();
        server.route("/ping", (request, call) -> Response.of(200, "ok"));
        useProperties("api.client.infoapi.base.url", server.url());
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        ComponentStore store = new ComponentStore();
        CleanupRegistry cleanup = new CleanupRegistry();
        try {
            DynamicApiProcessor.processApiClient(InfoApi.class, new ComponentRegistry(store), cleanup);
            assertEquals("ok", ((InfoApi) store.getComponent(InfoApi.class.getName())).ping());
        } finally {
            cleanup.cleanup();
        }
    }

    @Test
    void invalidNumberNamesTheProperty() {
        useProperties("api.client.badapi.retry.max", "abc");

        FrameworkException failure = assertThrows(FrameworkException.class, () -> processOnly(BadApi.class));

        assertTrue(failure.getMessage().contains("api.client.badapi.retry.max"), failure.getMessage());
        assertTrue(failure.getMessage().contains("abc"), failure.getMessage());
    }

    @Test
    void invalidBooleanNamesThePropertyAndTheAllowedValues() {
        useProperties("api.client.badapi.async.enabled", "yes");

        FrameworkException failure = assertThrows(FrameworkException.class, () -> processOnly(BadApi.class));

        assertTrue(failure.getMessage().contains("api.client.badapi.async.enabled"), failure.getMessage());
        assertTrue(failure.getMessage().contains("true or false"), failure.getMessage());
    }

    @Test
    void invalidQueuePolicyNamesThePropertyAndTheAllowedValues() {
        useProperties("api.client.badapi.async.queue.full", "wait");

        FrameworkException failure = assertThrows(FrameworkException.class, () -> processOnly(BadApi.class));

        assertTrue(failure.getMessage().contains("api.client.badapi.async.queue.full"), failure.getMessage());
        assertTrue(failure.getMessage().contains("reject or caller-runs"), failure.getMessage());
    }

    @Test
    void queuePolicyAcceptsBothSpellingsOfCallerRuns() {
        useProperties("api.client.badapi.async.queue.full", "CALLER_RUNS");
        processOnly(BadApi.class);

        useProperties("api.client.badapi.async.queue.full", "caller-runs");
        processOnly(BadApi.class);
    }
}