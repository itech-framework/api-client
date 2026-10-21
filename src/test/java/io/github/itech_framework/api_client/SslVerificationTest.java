package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.exceptions.ApiException;

public class SslVerificationTest extends BaseClientTest {

    public interface SecureApi {
        @GET("/secure")
        String secure();
    }

    private static SSLContext selfSignedContext() throws Exception {
        Path directory = Files.createTempDirectory("api-client-ssl");
        Path keystore = directory.resolve("test.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA",
                "-keysize", "2048", "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass",
                "changeit", "-keypass", "changeit", "-dname", "CN=localhost", "-validity", "2", "-ext",
                "SAN=dns:localhost,ip:127.0.0.1").redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor());

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keystore)) {
            store.load(input, "changeit".toCharArray());
        }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);
        return context;
    }

    @Test
    void rejectsSelfSignedCertificateWhenVerificationIsEnabled() throws Exception {
        TestServer server = httpsServer(selfSignedContext());
        server.route("/secure", (request, call) -> Response.of(200, "secure-ok"));
        SecureApi api = client(SecureApi.class, config(server).maxRetries(0).build());

        ApiException error = assertThrows(ApiException.class, api::secure);

        assertEquals("IO_ERROR", error.getErrorCode());
    }

    @Test
    void acceptsSelfSignedCertificateWhenVerificationIsDisabled() throws Exception {
        TestServer server = httpsServer(selfSignedContext());
        server.route("/secure", (request, call) -> Response.of(200, "secure-ok"));
        SecureApi api = client(SecureApi.class, config(server).sslVerification(false).maxRetries(0).build());

        assertEquals("secure-ok", api.secure());
    }
}