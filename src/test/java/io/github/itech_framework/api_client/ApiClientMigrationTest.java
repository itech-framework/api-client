package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.annotations.ApiClient;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.fixtures.ApiApp;
import io.github.itech_framework.api_client.fixtures.PingApi;
import io.github.itech_framework.api_client.processor.DynamicApiProcessor;
import io.github.itech_framework.core.exceptions.FrameworkException;
import io.github.itech_framework.core.module.ComponentRegistry;
import io.github.itech_framework.core.processor.components_processor.ApplicationContext;
import io.github.itech_framework.core.processor.components_processor.ComponentProcessor;
import io.github.itech_framework.core.resourcecs.CleanupRegistry;
import io.github.itech_framework.core.store.ComponentStore;

class ApiClientMigrationTest {

    @ApiClient(baseUrl = "http://localhost:1")
    public interface UnreachableApi {
        @GET("/ping")
        String ping();
    }

    @ApiClient(baseUrl = "http://localhost:1")
    static class NotAnInterface {
    }

    interface NotAnnotated {
    }

    private ApplicationContext started;

    @AfterEach
    void closeContext() {
        if (started != null) {
            started.close();
            started = null;
        }
    }

    private static Throwable failureOf(Runnable call) {
        try {
            call.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    @Test
    void registersTheProxyInTheGivenStoreAndNotTheGlobalOne() {
        ComponentStore store = new ComponentStore();
        CleanupRegistry cleanup = new CleanupRegistry();

        DynamicApiProcessor.processApiClient(UnreachableApi.class, new ComponentRegistry(store), cleanup);

        assertTrue(store.hasComponent(UnreachableApi.class.getName()));
        assertFalse(ComponentStore.current().hasComponent(UnreachableApi.class.getName()));
        assertEquals(1, cleanup.pendingCount());
        cleanup.cleanup();
    }

    @Test
    void ignoresTypesThatAreNotAnnotatedInterfaces() {
        ComponentStore store = new ComponentStore();
        CleanupRegistry cleanup = new CleanupRegistry();

        DynamicApiProcessor.processApiClient(NotAnInterface.class, new ComponentRegistry(store), cleanup);
        DynamicApiProcessor.processApiClient(NotAnnotated.class, new ComponentRegistry(store), cleanup);

        assertEquals(0, store.getAllComponents().size());
        assertEquals(0, cleanup.pendingCount());
    }

    @Test
    void rejectsRegisteringTheSameClientTwice() {
        ComponentStore store = new ComponentStore();
        CleanupRegistry cleanup = new CleanupRegistry();
        DynamicApiProcessor.processApiClient(UnreachableApi.class, new ComponentRegistry(store), cleanup);

        FrameworkException failure = assertThrows(FrameworkException.class,
                () -> DynamicApiProcessor.processApiClient(UnreachableApi.class, new ComponentRegistry(store), cleanup));

        assertTrue(rootCause(failure).getMessage().contains("Duplicate"), failure.toString());
        cleanup.cleanup();
    }

    @Test
    void clientStaysUsableWhileBeansAreDestroyedAndIsClosedAfterwards() {
        ComponentStore store = new ComponentStore();
        CleanupRegistry cleanup = new CleanupRegistry();
        DynamicApiProcessor.processApiClient(UnreachableApi.class, new ComponentRegistry(store), cleanup);
        UnreachableApi api = (UnreachableApi) store.getComponent(UnreachableApi.class.getName());
        AtomicReference<Throwable> duringBeanDestroy = new AtomicReference<>();
        cleanup.register(() -> duringBeanDestroy.set(failureOf(api::ping)), CleanupRegistry.BEAN_PRIORITY);

        cleanup.cleanup();
        Throwable afterCleanup = failureOf(api::ping);

        assertInstanceOf(ConnectException.class, rootCause(duringBeanDestroy.get()));
        assertInstanceOf(IllegalStateException.class, rootCause(afterCleanup));
    }

    @Test
    void startsThroughTheFrameworkAndClosesClientsWithTheContext() throws Exception {
        started = ComponentProcessor.initialize(ApiApp.class);

        Object client = started.store().getComponent(PingApi.class.getName());
        assertTrue(Proxy.isProxyClass(client.getClass()));
        PingApi api = (PingApi) client;
        assertInstanceOf(ConnectException.class, rootCause(failureOf(api::ping)));

        started.close();

        assertInstanceOf(IllegalStateException.class, rootCause(failureOf(api::ping)));
        assertNull(failureOf(() -> started.close()));
    }
}