package io.github.itech_framework.api_client.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.itech_framework.api_client.annotations.authentications.OAuth2;
import io.github.itech_framework.api_client.utils.JsonUtils;
import io.github.itech_framework.core.exceptions.FrameworkException;
import okhttp3.*;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TokenManager {
    private static final ConcurrentHashMap<String, TokenData> tokenCache = new ConcurrentHashMap<>();
    private static final OkHttpClient httpClient = new OkHttpClient();

    private TokenManager() {
        // Private constructor to prevent instantiation
    }

    public static synchronized String getToken(OAuth2 oauthConfig) {
        String cacheKey = buildCacheKey(oauthConfig);
        TokenData tokenData = tokenCache.get(cacheKey);

        if (tokenData == null || isTokenExpired(tokenData)) {
            tokenData = refreshToken(oauthConfig, tokenData);
            tokenCache.put(cacheKey, tokenData);
        }

        return tokenData.accessToken();
    }

    private static TokenData refreshToken(OAuth2 oauthConfig, TokenData previousToken) {
        try {
            RequestBody body = buildTokenRequestBody(oauthConfig, previousToken);
            Request request = new Request.Builder()
                    .url(oauthConfig.tokenUrl())
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new FrameworkException("OAuth2 token request failed: " + response.code());
                }

                String responseBody = response.body().string();
                return parseTokenResponse(responseBody);
            }
        } catch (IOException e) {
            throw new FrameworkException("OAuth2 token request failed", e);
        }
    }

    private static RequestBody buildTokenRequestBody(OAuth2 config, TokenData previousToken) {
        FormBody.Builder formBuilder = new FormBody.Builder()
                .add("client_id", config.clientId())
                .add("client_secret", config.clientSecret());

        if (previousToken != null && previousToken.refreshToken() != null) {
            formBuilder.add("grant_type", "refresh_token")
                    .add("refresh_token", previousToken.refreshToken());
        } else {
            formBuilder.add("grant_type", "client_credentials");
        }

        return formBuilder.build();
    }

    private static TokenData parseTokenResponse(String responseBody) {
        Map<String, Object> responseMap = JsonUtils.fromJson(responseBody, new TypeReference<>() {});

        String accessToken = (String) responseMap.get("access_token");
        String refreshToken = (String) responseMap.get("refresh_token");
        int expiresIn = (Integer) responseMap.getOrDefault("expires_in", 3600);

        return new TokenData(
                accessToken,
                refreshToken,
                Instant.now().plusSeconds(expiresIn - 60)  // 60-second buffer
        );
    }

    private static boolean isTokenExpired(TokenData tokenData) {
        return Instant.now().isAfter(tokenData.expiration());
    }

    private static String buildCacheKey(OAuth2 config) {
        return config.clientId() + "@" + config.tokenUrl();
    }

    private record TokenData(
            String accessToken,
            String refreshToken,
            Instant expiration
    ) {
        TokenData {
            if (accessToken == null || accessToken.isEmpty()) {
                throw new IllegalArgumentException("Invalid token data");
            }
        }
    }
}