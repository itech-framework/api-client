package org.itech.framework.fx.api_client.annotations.authentications;

import org.itech.framework.fx.api_client.utils.enums.AuthType;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Authenticated {
    AuthType value();
}