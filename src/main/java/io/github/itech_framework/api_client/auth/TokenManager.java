package io.github.itech_framework.api_client.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.type.TypeReference;

import io.github.itech_framework.api_client.annotations.authentications.OAuth2;
import io.github.itech_framework.api_client.utils.JsonUtils;
import io.github.itech_framework.api_client.utils.PlaceholderResolver;
import io.github.itech_framework.core.exceptions.FrameworkException;

public class TokenManager {
    private static final Logger logger = LogManager.getLogger(TokenManager.class);
    private static final ConcurrentHashMap<String, TokenData> tokenCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private static final CloseableHttpClient httpClient = HttpClients.custom()
            .setMaxConnTotal(20)
            .setMaxConnPerRoute(10)
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setConnectTimeout(10_000)
                    .setSocketTimeout(10_000)
                    .setConnectionRequestTimeout(10_000)
                    .build())
            .build();

    private TokenManager() {
    }

    public record Credentials(String clientId, String clientSecret, String tokenUrl) {
        public static Credentials from(OAuth2 oauth) {
            return new Credentials(
                    PlaceholderResolver.resolve(oauth.clientId()),
                    PlaceholderResolver.resolve(oauth.clientSecret()),
                    PlaceholderResolver.resolve(oauth.tokenUrl()));
        }

        String cacheKey() {
            return clientId + "@" + tokenUrl;
        }

        @Override
        public String toString() {
            return "Credentials[clientId=" + clientId + ", tokenUrl=" + tokenUrl + "]";
        }
    }

    public static String getToken(OAuth2 oauthConfig) {
        return getToken(Credentials.from(oauthConfig));
    }

    public static String getToken(Credentials credentials) {
        String cacheKey = credentials.cacheKey();
        TokenData tokenData = tokenCache.get(cacheKey);
        if (tokenData != null && !isExpired(tokenData)) {
            return tokenData.accessToken();
        }
        synchronized (locks.computeIfAbsent(cacheKey, key -> new Object())) {
            tokenData = tokenCache.get(cacheKey);
            if (tokenData == null || isExpired(tokenData)) {
                tokenData = fetchToken(credentials, tokenData);
                tokenCache.put(cacheKey, tokenData);
            }
            return tokenData.accessToken();
        }
    }

    public static void invalidate(Credentials credentials) {
        tokenCache.remove(credentials.cacheKey());
    }

    private static TokenData fetchToken(Credentials credentials, TokenData previous) {
        if (previous != null && previous.refreshToken() != null) {
            try {
                return requestToken(credentials, previous.refreshToken());
            } catch (FrameworkException e) {
                logger.warn("OAuth2 refresh failed for client {}, falling back to client credentials", credentials.clientId());
            }
        }
        return requestToken(credentials, null);
    }

    private static TokenData requestToken(Credentials credentials, String refreshToken) {
        List<NameValuePair> form = new ArrayList<>();
        form.add(new BasicNameValuePair("client_id", credentials.clientId()));
        form.add(new BasicNameValuePair("client_secret", credentials.clientSecret()));
        if (refreshToken != null) {
            form.add(new BasicNameValuePair("grant_type", "refresh_token"));
            form.add(new BasicNameValuePair("refresh_token", refreshToken));
        } else {
            form.add(new BasicNameValuePair("grant_type", "client_credentials"));
        }

        HttpPost post = new HttpPost(credentials.tokenUrl());
        post.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getStatusLine().getStatusCode();
            String body = response.getEntity() == null ? null
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                throw new FrameworkException("OAuth2 token request failed: " + status);
            }
            return parseTokenResponse(body);
        } catch (IOException e) {
            throw new FrameworkException("OAuth2 token request failed", e);
        }
    }

    private static TokenData parseTokenResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new FrameworkException("OAuth2 token response was empty");
        }
        Map<String, Object> map = JsonUtils.fromJson(body, new TypeReference<Map<String, Object>>() {
        });
        Object tokenValue = map == null ? null : map.get("access_token");
        if (!(tokenValue instanceof String) || ((String) tokenValue).isEmpty()) {
            throw new FrameworkException("OAuth2 token response did not contain access_token");
        }
        Object refreshValue = map.get("refresh_token");
        String refreshToken = refreshValue instanceof String s && !s.isEmpty() ? s : null;
        long expiresIn = readExpiresIn(map.get("expires_in"));
        long skew = Math.min(60L, expiresIn / 2);
        return new TokenData((String) tokenValue, refreshToken, Instant.now().plusSeconds(expiresIn - skew));
    }

    private static long readExpiresIn(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return 3600L;
            }
        }
        return 3600L;
    }

    private static boolean isExpired(TokenData tokenData) {
        return Instant.now().isAfter(tokenData.expiration());
    }

    private record TokenData(String accessToken, String refreshToken, Instant expiration) {
    }
}