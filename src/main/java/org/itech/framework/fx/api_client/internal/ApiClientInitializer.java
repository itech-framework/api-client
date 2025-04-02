package org.itech.framework.fx.api_client.internal;

import org.itech.framework.fx.api_client.handlers.ApiClientInvocationHandler;
import org.itech.framework.fx.api_client.processor.ApiProcessor;
import org.itech.framework.fx.core.module.ComponentInitializer;
import org.itech.framework.fx.core.processor.components_processor.ComponentProcessor;
import org.itech.framework.fx.core.resourcecs.CleanupRegistry;
import org.itech.framework.fx.core.store.ComponentStore;

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
