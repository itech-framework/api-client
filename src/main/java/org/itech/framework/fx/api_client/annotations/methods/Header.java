package org.itech.framework.fx.api_client.annotations.methods;
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Headers.class)
public @interface Header {
    String name();
    String value();
}

