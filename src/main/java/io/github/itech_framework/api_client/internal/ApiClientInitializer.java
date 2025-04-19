package io.github.itech_framework.api_client.internal;

import io.github.itech_framework.api_client.handlers.ApiClientInvocationHandler;
import io.github.itech_framework.api_client.processor.ApiProcessor;
import io.github.itech_framework.core.module.ComponentInitializer;
import io.github.itech_framework.core.processor.components_processor.ComponentProcessor;
import io.github.itech_framework.core.resourcecs.CleanupRegistry;
import io.github.itech_framework.core.store.ComponentStore;

public class ApiClientInitializer implements ComponentInitializer {

    @Override
    public void initializeComponent(Class<?> aClass) {
        ApiProcessor.processApiClient(aClass);

        // register clean up resources
        String key = aClass.getName();

        CleanupRegistry.register(()->{
            // get from component
            Object handler = ComponentStore.getComponent(key);
            if(handler instanceof ApiClientInvocationHandler){
                // close api handler
                ((ApiClientInvocationHandler) handler).close();
            }
        }, ComponentProcessor.DEFAULT_LEVEL);
    }
}
