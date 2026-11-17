package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;

public class ApacheContextAssumptionTest extends BaseClientTest {

    @Test
    void finalRequestInContextHasHeadersAddedByApache() throws Exception {
        TestServer server = server();
        server.route("/echo", (request, call) -> Response.of(200, "ok"));

        HttpPost post = new HttpPost(server.url() + "/echo");
        post.setEntity(new StringEntity("hello", ContentType.TEXT_PLAIN));
        HttpClientContext context = HttpClientContext.create();

        try (CloseableHttpClient client = HttpClients.createDefault();
                CloseableHttpResponse response = client.execute(post, context)) {
            assertEquals(200, response.getStatusLine().getStatusCode());
        }

        assertNotNull(context.getRequest());
        assertNotNull(context.getRequest().getFirstHeader("Host"));
        assertNotNull(context.getRequest().getFirstHeader("User-Agent"));
        assertNotNull(context.getRequest().getFirstHeader("Content-Length"));
        assertNotNull(context.getRequest().getFirstHeader("Accept-Encoding"));
        assertNull(post.getFirstHeader("Host"));
    }
}