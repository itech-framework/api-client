package io.github.itech_framework.api_client.annotations.parameters;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.itech_framework.api_client.utils.body.BodyType;

/**
 * The request body.
 * <ul>
 * <li>{@code @Body Dto dto} - JSON</li>
 * <li>{@code @Body(type = BodyType.FORM) Map<String,String> form}</li>
 * <li>{@code @Body MultipartBody body}</li>
 * <li>{@code @Body(contentType = "image/png") byte[] image} - raw bytes</li>
 * <li>{@code @Body Path file} - streamed from disk</li>
 * <li>{@code @Body(contentType = "application/vnd.api+json") Doc doc} - JSON with a custom content type</li>
 * </ul>
 * Content type precedence: {@code contentType} here, then a Content-Type header
 * ({@code @Header} / {@code @Headers}), then the default of the resolved body type.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Body {

	/** Encoding of the value. AUTO picks one from the content type and the value's runtime type. */
	BodyType type() default BodyType.AUTO;

	/** Overrides the Content-Type of this request. Empty means "not overridden". */
	String contentType() default "";
}