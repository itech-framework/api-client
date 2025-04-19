package io.github.itech_framework.api_client.annotations.authentications;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface BasicAuth {
    String username();
    String password();
}
