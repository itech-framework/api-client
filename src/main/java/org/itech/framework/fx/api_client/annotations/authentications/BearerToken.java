package org.itech.framework.fx.api_client.annotations.authentications;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface BearerToken {
    String token();
}