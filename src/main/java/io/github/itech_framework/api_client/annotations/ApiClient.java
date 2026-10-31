package io.github.itech_framework.api_client.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.itech_framework.api_client.interceptor.Interceptor;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ApiClient {
    String baseUrl() default "";
    Class<? extends Interceptor>[] interceptors() default {};
}
