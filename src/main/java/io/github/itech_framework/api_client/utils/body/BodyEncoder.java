package io.github.itech_framework.api_client.utils.body;

import java.io.IOException;

import org.apache.http.HttpEntity;

/**
 * Extension point for custom body formats (XML, protobuf, CSV, ...).
 * Custom encoders are consulted before the built-in ones.
 */
public interface BodyEncoder {

	/**
	 * @param body         the non-null value of the body parameter
	 * @param declaredType the type from {@code @Body(type = ...)}, AUTO when not set
	 * @param contentType  the effective content type override (annotation first, then header), may be null
	 */
	boolean supports(Object body, BodyType declaredType, String contentType);

	/** The returned entity must carry its own Content-Type. */
	HttpEntity encode(Object body, String contentType) throws IOException;
}