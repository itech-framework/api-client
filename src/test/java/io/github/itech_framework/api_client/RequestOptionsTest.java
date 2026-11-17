package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.utils.RequestOptions;

public class RequestOptionsTest {

    @Test
    void unsetValuesAreNull() {
        RequestOptions options = RequestOptions.builder().build();

        assertNull(options.getConnectTimeoutMillis());
        assertNull(options.getSocketTimeoutMillis());
        assertNull(options.getConnectionRequestTimeoutMillis());
        assertNull(options.getFollowRedirects());
        assertNull(options.getMaxRedirects());
        assertNull(options.getProxy());
    }

    @Test
    void valuesAreKept() {
        RequestOptions options = RequestOptions.builder().connectTimeoutMillis(100).socketTimeoutMillis(200)
                .connectionRequestTimeoutMillis(300).followRedirects(false).maxRedirects(5).build();

        assertEquals(100, options.getConnectTimeoutMillis());
        assertEquals(200, options.getSocketTimeoutMillis());
        assertEquals(300, options.getConnectionRequestTimeoutMillis());
        assertFalse(options.getFollowRedirects());
        assertEquals(5, options.getMaxRedirects());
    }

    @Test
    void timeoutMillisSetsConnectAndSocket() {
        RequestOptions options = RequestOptions.builder().timeoutMillis(750).build();

        assertEquals(750, options.getConnectTimeoutMillis());
        assertEquals(750, options.getSocketTimeoutMillis());
        assertNull(options.getConnectionRequestTimeoutMillis());
    }

    @Test
    void proxyDefaultsToHttpScheme() {
        RequestOptions options = RequestOptions.builder().proxy(" proxy.local ", 8080).build();

        assertEquals("proxy.local", options.getProxy().getHostName());
        assertEquals(8080, options.getProxy().getPort());
        assertEquals("http", options.getProxy().getSchemeName());
    }

    @Test
    void proxySchemeCanBeSet() {
        RequestOptions options = RequestOptions.builder().proxy("proxy.local", 8443, "https").build();

        assertEquals("https", options.getProxy().getSchemeName());
    }

    @Test
    void negativeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RequestOptions.builder().connectTimeoutMillis(-1));
        assertThrows(IllegalArgumentException.class, () -> RequestOptions.builder().socketTimeoutMillis(-1));
        assertThrows(IllegalArgumentException.class,
                () -> RequestOptions.builder().connectionRequestTimeoutMillis(-1));
        assertThrows(IllegalArgumentException.class, () -> RequestOptions.builder().timeoutMillis(-5));
        assertThrows(IllegalArgumentException.class, () -> RequestOptions.builder().maxRedirects(-1));
    }

    @Test
    void invalidProxyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RequestOptions.builder().proxy(null, 8080));
        assertThrows(IllegalArgumentException.class, () -> RequestOptions.builder().proxy("  ", 8080));
        assertThrows(IllegalArgumentException.class, () -> RequestOptions.builder().proxy("proxy.local", 0));
        assertThrows(IllegalArgumentException.class, () -> RequestOptions.builder().proxy("proxy.local", 70000));
    }
}