package io.github.itech_framework.api_client.utils.body;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Builder for multipart/form-data bodies.
 *
 * <pre>
 * MultipartBody.create()
 *     .field("description", "Quarterly report")
 *     .json("meta", new Meta(...))
 *     .file("file", new File("report.pdf"))
 *     .file("thumb", "thumb.png", bytes, "image/png");
 * </pre>
 *
 * Null values are skipped. Collections and Object[] add one part per item.
 */
public final class MultipartBody {

	public record Part(String name, Object content, String filename, String contentType) {
	}

	private final List<Part> parts = new ArrayList<>();

	private MultipartBody() {
	}

	public static MultipartBody create() {
		return new MultipartBody();
	}

	/** A text part (or a JSON part when the value is not a simple type). */
	public MultipartBody field(String name, Object value) {
		return part(name, value, null, null);
	}

	/** A part serialized as application/json. */
	public MultipartBody json(String name, Object value) {
		return part(name, value, null, "application/json");
	}

	public MultipartBody file(String name, File file) {
		return part(name, file, null, null);
	}

	public MultipartBody file(String name, Path path) {
		return part(name, path, null, null);
	}

	public MultipartBody file(String name, String filename, byte[] data, String contentType) {
		return part(name, data, filename, contentType);
	}

	public MultipartBody file(String name, String filename, InputStream data, String contentType) {
		return part(name, data, filename, contentType);
	}

	/** Generic entry point. Supports File, Path, byte[], InputStream, text/number values and POJOs (as JSON). */
	public MultipartBody part(String name, Object content, String filename, String contentType) {
		Objects.requireNonNull(name, "part name");
		if (content == null) {
			return this;
		}
		if (content instanceof Iterable<?> items && !(content instanceof Path)) {
			for (Object item : items) {
				part(name, item, filename, contentType);
			}
			return this;
		}
		if (content instanceof Object[] array) {
			for (Object item : array) {
				part(name, item, filename, contentType);
			}
			return this;
		}
		parts.add(new Part(name, content, filename, contentType));
		return this;
	}

	public List<Part> parts() {
		return Collections.unmodifiableList(parts);
	}

	public boolean isEmpty() {
		return parts.isEmpty();
	}
}