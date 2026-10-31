package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.POST;
import io.github.itech_framework.api_client.annotations.parameters.Body;
import io.github.itech_framework.api_client.utils.body.BodyType;
import io.github.itech_framework.api_client.utils.body.MultipartBody;

public class MultipartRelatedTest extends BaseClientTest {

    public interface UploadApi {
        @POST("/related")
        String related(@Body(type = BodyType.MULTIPART, contentType = "multipart/related") MultipartBody body);
    }

    @Test
    void multipartRelatedSendsOnlyContentTypeHeadersInOrder() throws Exception {
        TestServer server = server();
        server.route("/related", (request, call) -> Response.of(200, "done"));
        UploadApi api = client(UploadApi.class, config(server).build());

        api.related(MultipartBody.create().json("metadata", Map.of("name", "contract")).file("media",
                "contract.pdf", "PDFDATA".getBytes(StandardCharsets.UTF_8), "application/pdf"));

        TestServer.Received received = server.requestsTo("/related").get(0);
        assertTrue(received.header("Content-Type").startsWith("multipart/related; boundary="),
                received.header("Content-Type"));
        String body = received.body();
        assertFalse(body.contains("Content-Disposition"), body);
        assertTrue(body.contains("{\"name\":\"contract\"}"), body);
        assertTrue(body.contains("PDFDATA"), body);
        assertTrue(body.indexOf("application/json") < body.indexOf("application/pdf"), body);
    }
}