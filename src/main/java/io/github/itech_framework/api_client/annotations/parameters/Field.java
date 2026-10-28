package io.github.itech_framework.api_client.annotations.parameters;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One field of an application/x-www-form-urlencoded request. Any method with {@code @Field} parameters is sent
 * as a form. A Collection or array adds one entry per item. Null values are skipped.
 * Cannot be combined with {@code @Body} or {@code @Part}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Field {

	String value();
}