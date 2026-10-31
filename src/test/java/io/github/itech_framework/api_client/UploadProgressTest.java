package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.FileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.POST;
import io.github.itech_framework.api_client.annotations.methods.PUT;
import io.github.itech_framework.api_client.annotations.parameters.Body;
import io.github.itech_framework.api_client.exceptions.ApiException;
import io.github.itech_framework.api_client.exceptions.RequestCancelledException;
import io.github.itech_framework.api_client.utils.body.ProgressEntity;
import io.github.itech_framework.api_client.utils.body.ProgressListener;

public class UploadProgressTest extends BaseClientTest {

    public interface UploadApi {
        @POST("/progress")
        String postEntity(@Body HttpEntity entity);

        @POST("/progress")
        String postEntityChecked(@Body HttpEntity entity) throws IOException;

        @PUT("/progress")
        String putEntity(@Body HttpEntity entity);
    }

    @TempDir
    Path tempDir;

    private Path fileOfSize(int size) throws IOException {
        byte[] data = new byte[size];
        Arrays.fill(data, (byte) 'a');
        Path file = tempDir.resolve("upload.bin");
        Files.write(file, data);
        return file;
    }

    private static final class RecordingListener implements ProgressListener {
        final List<long[]> calls = new ArrayList<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final long cancelAfter;

        RecordingListener(long cancelAfter) {
            this.cancelAfter = cancelAfter;
        }

        @Override
        public void onProgress(long transferred, long total) {
            calls.add(new long[] { transferred, total });
            if (cancelAfter >= 0 && transferred >= cancelAfter) {
                cancelled.set(true);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    @Test
    void progressEntityReportsProgressUpToTheTotal() throws Exception {
        TestServer server = server();
        server.route("/progress", (request, call) -> Response.of(200, "stored"));
        UploadApi api = client(UploadApi.class, config(server).build());
        Path file = fileOfSize(200_000);
        RecordingListener listener = new RecordingListener(-1);

        String result = api.postEntity(
                new ProgressEntity(new FileEntity(file.toFile(), ContentType.APPLICATION_OCTET_STREAM), listener));

        assertEquals("stored", result);
        assertEquals(200_000, server.requestsTo("/progress").get(0).body().length());
        assertTrue(listener.calls.size() >= 2, "expected several progress calls");
        long[] last = listener.calls.get(listener.calls.size() - 1);
        assertEquals(200_000, last[0]);
        assertEquals(200_000, last[1]);
        long previous = 0;
        for (long[] call : listener.calls) {
            assertTrue(call[0] >= previous, "progress must not go backwards");
            previous = call[0];
        }
    }

    @Test
    void cancellingAnUploadSurfacesAsAnIoErrorWithTheCancellationAsCause() throws Exception {
        TestServer server = server();
        server.route("/progress", (request, call) -> Response.of(200, "stored"));
        UploadApi api = client(UploadApi.class, config(server).build());
        Path file = fileOfSize(400_000);
        RecordingListener listener = new RecordingListener(65_536);

        ApiException failure = assertThrows(ApiException.class, () -> api.postEntity(
                new ProgressEntity(new FileEntity(file.toFile(), ContentType.APPLICATION_OCTET_STREAM), listener)));

        assertEquals("IO_ERROR", failure.getErrorCode());
        assertInstanceOf(RequestCancelledException.class, failure.getCause());
        long[] last = listener.calls.get(listener.calls.size() - 1);
        assertTrue(last[0] < 400_000, "the upload must stop before the end");
    }

    @Test
    void cancellationIsThrownAsItIsWhenTheMethodDeclaresIoException() throws Exception {
        TestServer server = server();
        server.route("/progress", (request, call) -> Response.of(200, "stored"));
        UploadApi api = client(UploadApi.class, config(server).build());
        Path file = fileOfSize(400_000);
        RecordingListener listener = new RecordingListener(65_536);

        assertThrows(RequestCancelledException.class, () -> api.postEntityChecked(
                new ProgressEntity(new FileEntity(file.toFile(), ContentType.APPLICATION_OCTET_STREAM), listener)));
    }

    @Test
    @DisplayName("A cancelled PUT upload is NOT retried (fails until the transport rethrows RequestCancelledException)")
    void cancelledUploadIsNotRetried() throws Exception {
        TestServer server = server();
        server.route("/progress", (request, call) -> Response.of(200, "stored"));
        UploadApi api = client(UploadApi.class, config(server).maxRetries(2).build());
        Path file = fileOfSize(100_000);
        AtomicInteger attempts = new AtomicInteger();
        ProgressListener cancelledFromTheStart = new ProgressListener() {
            @Override
            public void onProgress(long transferred, long total) {
            }

            @Override
            public boolean isCancelled() {
                attempts.incrementAndGet();
                return true;
            }
        };

        assertThrows(ApiException.class, () -> api.putEntity(new ProgressEntity(
                new FileEntity(file.toFile(), ContentType.APPLICATION_OCTET_STREAM), cancelledFromTheStart)));

        assertEquals(1, attempts.get());
    }
}