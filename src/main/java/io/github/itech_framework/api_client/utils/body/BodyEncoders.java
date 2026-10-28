package io.github.itech_framework.api_client.utils.body;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.ParseException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.message.BasicNameValuePair;

import io.github.itech_framework.api_client.utils.JsonUtils;

/**
 * Turns a body value into an HttpEntity. Custom {@link BodyEncoder}s win over the built-in ones.
 * Requires org.apache.httpcomponents:httpmime on the classpath.
 */
public final class BodyEncoders {

	private static final String FORM_MIME = "application/x-www-form-urlencoded";
	private static final ContentType TEXT_UTF8 = ContentType.create("text/plain", StandardCharsets.UTF_8);

	private final List<BodyEncoder> custom;

	public BodyEncoders(List<BodyEncoder> custom) {
		this.custom = custom == null ? List.of() : List.copyOf(custom);
	}

	public static BodyEncoders defaults() {
		return new BodyEncoders(List.of());
	}

	public HttpEntity encode(Object body, BodyType declared, String contentType) throws IOException {
		if (body instanceof HttpEntity entity) {
			return entity; // escape hatch: full control
		}
		BodyType requested = declared == null ? BodyType.AUTO : declared;
		for (BodyEncoder encoder : custom) {
			if (encoder.supports(body, requested, contentType)) {
				return encoder.encode(body, contentType);
			}
		}
		BodyType type = requested == BodyType.AUTO ? infer(body, contentType) : requested;
		return switch (type) {
		case JSON -> json(body, contentType);
		case FORM -> form(body, contentType);
		case MULTIPART -> multipart(body, contentType);
		case TEXT -> text(body, contentType);
		case BINARY -> binary(body, contentType);
		case AUTO -> throw new IllegalStateException("Body type was not resolved");
		};
	}

	// ---------------------------------------------------------------- inference

	private static BodyType infer(Object body, String contentType) {
		String mime = mimeType(contentType);
		if (mime != null) {
			if (mime.startsWith("multipart/")) {
				return BodyType.MULTIPART;
			}
			if (mime.equals(FORM_MIME)) {
				return BodyType.FORM;
			}
			if (isJson(mime)) {
				return BodyType.JSON;
			}
		}
		if (body instanceof MultipartBody) {
			return BodyType.MULTIPART;
		}
		if (body instanceof byte[] || body instanceof File || body instanceof Path || body instanceof InputStream) {
			return BodyType.BINARY;
		}
		if (body instanceof CharSequence) {
			return BodyType.TEXT;
		}
		return BodyType.JSON;
	}

	private static boolean isJson(String mime) {
		return mime.equals("application/json") || mime.equals("text/json") || mime.endsWith("+json");
	}

	// ---------------------------------------------------------------- encoders

	private static HttpEntity json(Object body, String contentType) {
		ContentType type = parse(contentType, ContentType.APPLICATION_JSON);
		Charset charset = type.getCharset() != null ? type.getCharset() : StandardCharsets.UTF_8;
		byte[] bytes;
		if (body instanceof byte[] raw) {
			bytes = raw;
		} else if (body instanceof CharSequence text) {
			bytes = text.toString().getBytes(charset);
		} else {
			bytes = JsonUtils.toJson(body).getBytes(charset);
		}
		return new ByteArrayEntity(bytes, type);
	}

	private static HttpEntity text(Object body, String contentType) {
		ContentType type = withCharset(parse(contentType, TEXT_UTF8));
		String text = body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : body.toString();
		return new StringEntity(text, type);
	}

	private static HttpEntity binary(Object body, String contentType) {
		Object value = body instanceof Path path ? path.toFile() : body;
		if (value instanceof File file) {
			if (!file.isFile()) {
				throw new IllegalArgumentException("File does not exist or is not a regular file: " + file);
			}
			ContentType type = contentType == null || contentType.isBlank() ? probe(file) : parse(contentType, null);
			return new FileEntity(file, type);
		}
		ContentType type = parse(contentType, ContentType.APPLICATION_OCTET_STREAM);
		if (value instanceof byte[] bytes) {
			return new ByteArrayEntity(bytes, type);
		}
		if (value instanceof InputStream in) {
			return new InputStreamEntity(in, type); // length unknown -> chunked, NOT repeatable
		}
		if (value instanceof CharSequence text) {
			return new ByteArrayEntity(text.toString().getBytes(StandardCharsets.UTF_8), type);
		}
		throw new IllegalArgumentException("A binary body must be byte[], File, Path or InputStream, but was "
				+ body.getClass().getName());
	}

	private static HttpEntity form(Object body, String contentType) {
		ContentType type = parse(contentType, ContentType.APPLICATION_FORM_URLENCODED);
		Charset charset = type.getCharset() != null ? type.getCharset() : StandardCharsets.UTF_8;
		List<NameValuePair> fields = new ArrayList<>();
		if (body instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() != null) {
					addField(fields, entry.getKey().toString(), entry.getValue());
				}
			}
		} else if (body instanceof Iterable<?> items) {
			for (Object item : items) {
				if (!(item instanceof NameValuePair pair)) {
					throw new IllegalArgumentException("A form body list must contain NameValuePair items");
				}
				fields.add(pair);
			}
		} else {
			throw new IllegalArgumentException(
					"A form body must be a Map or a List<NameValuePair>, but was " + body.getClass().getName());
		}
		return new UrlEncodedFormEntity(fields, charset);
	}

	private static void addField(List<NameValuePair> fields, String name, Object value) {
		if (value == null) {
			return;
		}
		if (value instanceof Iterable<?> items && !(value instanceof Path)) {
			for (Object item : items) {
				addField(fields, name, item);
			}
		} else if (value instanceof Object[] array) {
			for (Object item : array) {
				addField(fields, name, item);
			}
		} else {
			fields.add(new BasicNameValuePair(name, value.toString()));
		}
	}

	private static HttpEntity multipart(Object body, String contentType) {
		MultipartBody multipart = toMultipart(body);
		MultipartEntityBuilder builder = MultipartEntityBuilder.create().setMode(HttpMultipartMode.RFC6532);
		String mime = mimeType(contentType);
		if (mime != null && mime.startsWith("multipart/") && !mime.equals("multipart/form-data")) {
			builder.setMimeSubtype(mime.substring("multipart/".length()));
		}
		for (MultipartBody.Part part : multipart.parts()) {
			addPart(builder, part);
		}
		return builder.build(); // boundary is generated here and ends up in the entity's Content-Type
	}

	private static MultipartBody toMultipart(Object body) {
		if (body instanceof MultipartBody multipart) {
			return multipart;
		}
		if (body instanceof Map<?, ?> map) {
			MultipartBody multipart = MultipartBody.create();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() != null) {
					multipart.part(entry.getKey().toString(), entry.getValue(), null, null);
				}
			}
			return multipart;
		}
		throw new IllegalArgumentException(
				"A multipart body must be a MultipartBody or a Map, but was " + body.getClass().getName());
	}

	private static void addPart(MultipartEntityBuilder builder, MultipartBody.Part part) {
		Object content = part.content() instanceof Path path ? path.toFile() : part.content();
		String name = part.name();
		String filename = blankToNull(part.filename());
		String contentType = blankToNull(part.contentType());

		if (content instanceof File file) {
			if (!file.isFile()) {
				throw new IllegalArgumentException("Multipart part '" + name + "': file not found: " + file);
			}
			ContentType type = contentType == null ? probe(file) : parse(contentType, null);
			builder.addBinaryBody(name, file, type, filename != null ? filename : file.getName());
		} else if (content instanceof byte[] bytes) {
			builder.addBinaryBody(name, bytes, parse(contentType, ContentType.APPLICATION_OCTET_STREAM),
					filename != null ? filename : name);
		} else if (content instanceof InputStream in) {
			builder.addBinaryBody(name, in, parse(contentType, ContentType.APPLICATION_OCTET_STREAM),
					filename != null ? filename : name);
		} else if (isScalar(content)) {
			ContentType type = withCharset(parse(contentType, TEXT_UTF8));
			String text = content.toString();
			if (filename != null) {
				builder.addBinaryBody(name, text.getBytes(type.getCharset()), type, filename);
			} else {
				builder.addTextBody(name, text, type);
			}
		} else {
			ContentType type = withCharset(parse(contentType, ContentType.APPLICATION_JSON));
			builder.addTextBody(name, JsonUtils.toJson(content), type);
		}
	}

	// ---------------------------------------------------------------- helpers

	private static boolean isScalar(Object value) {
		return value instanceof CharSequence || value instanceof Number || value instanceof Boolean
				|| value instanceof Character || value instanceof Enum<?>;
	}

	private static ContentType withCharset(ContentType type) {
		// httpmime falls back to ASCII for text parts without a charset, which mangles non-ASCII text
		return type.getCharset() == null ? type.withCharset(StandardCharsets.UTF_8) : type;
	}

	private static ContentType parse(String value, ContentType fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return ContentType.parse(value.trim());
		} catch (ParseException | UnsupportedCharsetException e) {
			throw new IllegalArgumentException("Invalid content type: " + value, e);
		}
	}

	private static ContentType probe(File file) {
		try {
			String mime = Files.probeContentType(file.toPath());
			if (mime != null) {
				return ContentType.parse(mime);
			}
		} catch (IOException | RuntimeException ignored) {
			// fall through to octet-stream
		}
		return ContentType.APPLICATION_OCTET_STREAM;
	}

	private static String mimeType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return null;
		}
		int semi = contentType.indexOf(';');
		String mime = semi < 0 ? contentType : contentType.substring(0, semi);
		return mime.trim().toLowerCase(Locale.ROOT);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}