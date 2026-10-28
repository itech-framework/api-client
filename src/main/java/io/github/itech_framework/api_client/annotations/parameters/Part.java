package io.github.itech_framework.api_client.annotations.parameters;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One part of a multipart/form-data request. Any method with {@code @Part} parameters is sent as multipart.
 * Supported values: File, Path, byte[], InputStream, text/number values and POJOs (sent as JSON).
 * A Collection or array adds one part per item under the same name. Null values are skipped.
 * Cannot be combined with {@code @Body} or {@code @Field}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Part {

	/** The form field name. */
	String value();

	/** File name sent for this part. Defaults to the file's own name (File/Path) or the part name. */
	String filename() default "";

	/** Content type of this part. Defaults: probed from the file, octet-stream for bytes, text/plain, application/json for POJOs. */
	String contentType() default "";
}