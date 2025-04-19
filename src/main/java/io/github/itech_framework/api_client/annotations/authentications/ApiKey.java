package io.github.itech_framework.api_client.annotations.authentications;
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ApiKey {
    String name();
    String value();
    boolean inHeader() default true; // true=header, false=query param
}
