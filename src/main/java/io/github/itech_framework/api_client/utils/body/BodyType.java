package io.github.itech_framework.api_client.utils.body;

/**
 * How a request body is encoded.
 */
public enum BodyType {
	/** Decide from the Content-Type (annotation or header) and the runtime type of the value. */
	AUTO,
	/** JSON via JsonUtils. A String/byte[] value is assumed to already be JSON and is sent as is. */
	JSON,
	/** application/x-www-form-urlencoded. Value must be a Map or a List of NameValuePair. */
	FORM,
	/** multipart/form-data. Value must be a MultipartBody or a Map. */
	MULTIPART,
	/** Plain text (text/plain; charset=UTF-8 unless overridden). */
	TEXT,
	/** Raw bytes: byte[], File, Path, InputStream (application/octet-stream unless overridden). */
	BINARY
}