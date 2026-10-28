package io.github.itech_framework.api_client.utils.body;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

import org.apache.http.HttpEntity;
import org.apache.http.entity.HttpEntityWrapper;

import io.github.itech_framework.api_client.exceptions.RequestCancelledException;

/**
 * Wraps any HttpEntity (FileEntity, ByteArrayEntity, ...) to report upload progress and allow
 * cancellation. Pass it as an {@code @Body HttpEntity} parameter. Progress restarts from 0 if
 * the request is retried.
 */
public class ProgressEntity extends HttpEntityWrapper {

	private static final long REPORT_STEP = 64 * 1024;

	private final ProgressListener listener;

	public ProgressEntity(HttpEntity entity, ProgressListener listener) {
		super(entity);
		this.listener = Objects.requireNonNull(listener, "listener");
	}

	@Override
	public void writeTo(OutputStream out) throws IOException {
		wrappedEntity.writeTo(new CountingOutputStream(out, getContentLength()));
	}

	private final class CountingOutputStream extends FilterOutputStream {
		private final long total;
		private long written;
		private long reported;

		CountingOutputStream(OutputStream out, long total) {
			super(out);
			this.total = total;
		}

		@Override
		public void write(int b) throws IOException {
			checkCancelled();
			out.write(b);
			advance(1);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			checkCancelled();
			out.write(b, off, len);
			advance(len);
		}

		@Override
		public void close() throws IOException {
			flush();
		}

		private void checkCancelled() throws RequestCancelledException {
			if (listener.isCancelled()) {
				throw new RequestCancelledException();
			}
		}

		private void advance(long count) {
			written += count;
			if (written - reported >= REPORT_STEP || (total >= 0 && written >= total)) {
				reported = written;
				listener.onProgress(written, total);
			}
		}
	}
}