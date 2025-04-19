package io.github.itech_framework.api_client.annotations.authentications;

import io.github.itech_framework.api_client.utils.enums.AuthType;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Authenticated {
    AuthType value();
}