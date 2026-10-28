package io.github.itech_framework.api_client.utils.response;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;

/**
 * Return type for large or streamed responses. The body is NOT read into memory and the
 * connection stays open until {@link #close()} is called, so always use try-with-resources.
 * Closing early aborts the connection without draining the rest of the body.
 */
public final class StreamResponse implements Closeable {

	private final CloseableHttpResponse response;
	private InputStream stream;

	public StreamResponse(CloseableHttpResponse response) {
		this.response = response;
	}

	public int status() {
		return response.getStatusLine().getStatusCode();
	}

	public Optional<String> header(String name) {
		Header header = response.getFirstHeader(name);
		return header == null ? Optional.empty() : Optional.ofNullable(header.getValue());
	}

	/** Content-Length, or -1 when unknown (chunked or transparently decompressed). */
	public long contentLength() {
		HttpEntity entity = response.getEntity();
		return entity == null ? 0 : entity.getContentLength();
	}

	public synchronized InputStream inputStream() throws IOException {
		if (stream == null) {
			HttpEntity entity = response.getEntity();
			stream = entity == null ? InputStream.nullInputStream() : entity.getContent();
		}
		return stream;
	}

	@Override
	public void close() throws IOException {
		response.close();
	}
}