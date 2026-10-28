package io.github.itech_framework.api_client.utils.body;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.ContentType;

import io.github.itech_framework.api_client.utils.JsonUtils;

/**
 * multipart/related body: every part has only a Content-Type header (no Content-Disposition),
 * which is what Google Drive's multipart upload expects (metadata part first, media part second).
 */
final class MultipartRelatedEntity extends AbstractHttpEntity {

	private static final byte[] CRLF = { '\r', '\n' };

	private record Segment(byte[] head, Object source, long length) {
	}

	private final List<Segment> segments = new ArrayList<>();
	private final byte[] closing;
	private final boolean repeatable;
	private final long contentLength;

	MultipartRelatedEntity(List<MultipartBody.Part> parts) {
		String boundary = "fx-" + UUID.randomUUID();
		boolean canRepeat = true;
		long total = 0;
		for (MultipartBody.Part part : parts) {
			Segment segment = toSegment(boundary, part);
			segments.add(segment);
			if (segment.source() instanceof InputStream) {
				canRepeat = false;
			}
			total = total < 0 || segment.length() < 0 ? -1
					: total + segment.head().length + segment.length() + CRLF.length;
		}
		this.closing = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
		this.repeatable = canRepeat;
		this.contentLength = total < 0 ? -1 : total + closing.length;
		setContentType("multipart/related; boundary=" + boundary);
	}

	private static Segment toSegment(String boundary, MultipartBody.Part part) {
		Object content = part.content() instanceof Path path ? path.toFile() : part.content();
		String declared = BodyEncoders.blankToNull(part.contentType());
		ContentType type;
		Object source;
		long length;

		if (content instanceof File file) {
			if (!file.isFile()) {
				throw new IllegalArgumentException("Multipart part '" + part.name() + "': file not found: " + file);
			}
			type = declared == null ? BodyEncoders.probe(file) : BodyEncoders.parse(declared, null);
			source = file;
			length = file.length();
		} else if (content instanceof byte[] data) {
			type = BodyEncoders.parse(declared, ContentType.APPLICATION_OCTET_STREAM);
			source = data;
			length = data.length;
		} else if (content instanceof InputStream in) {
			type = BodyEncoders.parse(declared, ContentType.APPLICATION_OCTET_STREAM);
			source = in;
			length = -1;
		} else {
			boolean scalar = BodyEncoders.isScalar(content);
			type = BodyEncoders.withCharset(
					BodyEncoders.parse(declared, scalar ? BodyEncoders.TEXT_UTF8 : ContentType.APPLICATION_JSON));
			byte[] encoded = (scalar ? content.toString() : JsonUtils.toJson(content)).getBytes(type.getCharset());
			source = encoded;
			length = encoded.length;
		}
		byte[] head = ("--" + boundary + "\r\nContent-Type: " + type + "\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII);
		return new Segment(head, source, length);
	}

	@Override
	public boolean isRepeatable() {
		return repeatable;
	}

	@Override
	public long getContentLength() {
		return contentLength;
	}

	@Override
	public boolean isStreaming() {
		return !repeatable;
	}

	@Override
	public InputStream getContent() throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		writeTo(buffer);
		return new ByteArrayInputStream(buffer.toByteArray());
	}

	@Override
	public void writeTo(OutputStream out) throws IOException {
		for (Segment segment : segments) {
			out.write(segment.head());
			Object source = segment.source();
			if (source instanceof byte[] data) {
				out.write(data);
			} else if (source instanceof File file) {
				try (InputStream in = Files.newInputStream(file.toPath())) {
					in.transferTo(out);
				}
			} else if (source instanceof InputStream in) {
				in.transferTo(out);
			}
			out.write(CRLF);
		}
		out.write(closing);
		out.flush();
	}
}