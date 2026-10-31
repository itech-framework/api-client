package io.github.itech_framework.api_client.fixtures;

import io.github.itech_framework.api_client.annotations.ApiClient;
import io.github.itech_framework.api_client.annotations.methods.GET;

@ApiClient(baseUrl = "http://localhost:1")
public interface PingApi {

    @GET("/ping")
    String ping();
}