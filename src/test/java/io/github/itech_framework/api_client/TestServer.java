package io.github.itech_framework.api_client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

public final class TestServer implements AutoCloseable {

    public record Received(String method, String rawPath, String rawQuery, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name);
        }
    }

    public record Response(int status, String body, Map<String, String> headers) {
        public static Response of(int status, String body) {
            return new Response(status, body, Map.of());
        }

        public static Response json(int status, String body) {
            return new Response(status, body, Map.of("Content-Type", "application/json"));
        }

        public static Response empty(int status) {
            return new Response(status, "", Map.of());
        }

        public Response withHeader(String name, String value) {
            Map<String, String> merged = new TreeMap<>(headers);
            merged.put(name, value);
            return new Response(status, body, merged);
        }
    }

    @FunctionalInterface
    public interface Responder {
        Response respond(Received request, int call) throws Exception;
    }

    private record Route(String prefix, Responder responder, AtomicInteger calls) {
    }

    private final HttpServer server;
    private final String scheme;
    private final ExecutorService executor = Executors.newFixedThreadPool(32);
    private final List<Route> routes = new CopyOnWriteArrayList<>();
    private final List<Received> requests = new CopyOnWriteArrayList<>();

    private TestServer(HttpServer server, String scheme) {
        this.server = server;
        this.scheme = scheme;
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public static TestServer start(int port) throws IOException {
        return new TestServer(HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0), "http");
    }

    public static TestServer startHttps(SSLContext context) throws IOException {
        HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(context));
        return new TestServer(httpsServer, "https");
    }

    public TestServer route(String prefix, Responder responder) {
        routes.add(new Route(prefix, responder, new AtomicInteger()));
        return this;
    }

    public List<Received> requests() {
        return requests;
    }

    public List<Received> requestsTo(String prefix) {
        return requests.stream().filter(request -> request.rawPath().startsWith(prefix)).toList();
    }

    public String url() {
        return scheme + "://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.get(0)));
        Received received = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(), headers, new String(bodyBytes, StandardCharsets.UTF_8));
        requests.add(received);

        Response response = Response.of(404, "no route");
        for (Route route : routes) {
            if (received.rawPath().startsWith(route.prefix())) {
                int call = route.calls().incrementAndGet();
                try {
                    response = route.responder().respond(received, call);
                } catch (Exception e) {
                    response = Response.of(500, String.valueOf(e));
                }
                break;
            }
        }

        byte[] out = response.body().getBytes(StandardCharsets.UTF_8);
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(response.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(out);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}