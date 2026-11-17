package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.utils.response.ApiExchange;
import io.github.itech_framework.api_client.utils.response.ApiResponse;
import io.github.itech_framework.api_client.utils.response.RedirectHop;
import io.github.itech_framework.api_client.utils.response.SentRequest;
import io.github.itech_framework.api_client.utils.response.Timing;

public class ApiExchangeTest {

    @Test
    void sentRequestHeaderLookupIgnoresCase() {
        SentRequest sent = new SentRequest("GET", "http://x/a", Map.of("User-Agent", List.of("test")));
        assertEquals("test", sent.header("user-agent").orElseThrow());
        assertTrue(sent.header("Host").isEmpty());
    }

    @Test
    void sentRequestHeadersAreImmutable() {
        SentRequest sent = new SentRequest("GET", "http://x/a", Map.of("A", List.of("1")));
        assertThrows(UnsupportedOperationException.class, () -> sent.headers().put("B", List.of("2")));
    }

    @Test
    void exchangeCopiesRedirectsAndExposesResponse() {
        List<RedirectHop> hops = new ArrayList<>();
        hops.add(new RedirectHop(302, "http://x/a", "http://x/b"));
        ApiExchange<String> exchange = new ApiExchange<>(new ApiResponse<>(200, Map.of(), "ok"),
                new SentRequest("GET", "http://x/b", Map.of()), new Timing(5, 9), hops, 1);
        hops.clear();

        assertEquals(1, exchange.redirects().size());
        assertEquals(200, exchange.status());
        assertEquals("ok", exchange.body());
        assertEquals(9, exchange.timing().totalMillis());
    }
}