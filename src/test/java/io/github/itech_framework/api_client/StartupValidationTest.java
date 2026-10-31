package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.methods.POST;
import io.github.itech_framework.api_client.annotations.parameters.Field;
import io.github.itech_framework.api_client.annotations.parameters.Part;
import io.github.itech_framework.api_client.config.ApiClientConfig;

public class StartupValidationTest extends BaseClientTest {

    public interface UnannotatedParamApi {
        @GET("/x")
        String get(String forgotten);
    }

    public interface PartAndFieldApi {
        @POST("/part")
        String part(@Part("a") String a);

        @POST("/field")
        String field(@Field("b") String b);
    }

    @Test
    void aParameterWithoutAnAnnotationFailsWhenTheClientIsCreated() {
        ApiClientConfig config = ApiClientConfig.builder().baseUrl("http://localhost:1").build();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> client(UnannotatedParamApi.class, config));

        assertTrue(failure.getMessage().contains("UnannotatedParamApi.get"), failure.getMessage());
        assertTrue(failure.getMessage().contains("has no request annotation"), failure.getMessage());
    }

    @Test
    void partAndFieldParametersAreAccepted() throws Exception {
        TestServer server = server();
        server.route("/part", (request, call) -> Response.of(200, "part"));
        server.route("/field", (request, call) -> Response.of(200, "field"));

        PartAndFieldApi api = assertDoesNotThrow(() -> client(PartAndFieldApi.class, config(server).build()));

        assertEquals("part", api.part("value"));
        assertEquals("field", api.field("value"));
        assertTrue(server.requestsTo("/part").get(0).header("Content-Type").startsWith("multipart/form-data"));
        assertEquals("b=value", server.requestsTo("/field").get(0).body());
    }
}