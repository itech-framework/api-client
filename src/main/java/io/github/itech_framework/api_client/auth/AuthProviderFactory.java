package io.github.itech_framework.api_client.auth;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.http.HttpRequest;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;

import io.github.itech_framework.api_client.annotations.authentications.ApiKey;
import io.github.itech_framework.api_client.annotations.authentications.Authenticated;
import io.github.itech_framework.api_client.annotations.authentications.BasicAuth;
import io.github.itech_framework.api_client.annotations.authentications.BearerToken;
import io.github.itech_framework.api_client.annotations.authentications.DigestAuth;
import io.github.itech_framework.api_client.annotations.authentications.OAuth2;
import io.github.itech_framework.api_client.utils.PlaceholderResolver;
import io.github.itech_framework.core.exceptions.FrameworkException;

public final class AuthProviderFactory {
    private final Class<?> apiInterface;
    private final ConcurrentHashMap<Annotation, AuthenticationProvider> cache = new ConcurrentHashMap<>();

    public AuthProviderFactory(Class<?> apiInterface) {
        this.apiInterface = apiInterface;
    }

    public AuthenticationProvider resolve(Method method) {
        Authenticated authenticated = method.getAnnotation(Authenticated.class);
        if (authenticated == null) {
            authenticated = apiInterface.getAnnotation(Authenticated.class);
        }
        if (authenticated == null) {
            return null;
        }
        Annotation annotation = switch (authenticated.value()) {
        case BASIC -> lookup(method, BasicAuth.class);
        case API_KEY -> lookup(method, ApiKey.class);
        case BEARER -> lookup(method, BearerToken.class);
        case OAUTH2 -> lookup(method, OAuth2.class);
        case DIGEST -> lookup(method, DigestAuth.class);
        };
        return cache.computeIfAbsent(annotation, this::create);
    }

    private <A extends Annotation> A lookup(Method method, Class<A> type) {
        A annotation = method.getAnnotation(type);
        if (annotation == null) {
            annotation = apiInterface.getAnnotation(type);
        }
        if (annotation == null) {
            throw new FrameworkException("@Authenticated requires @" + type.getSimpleName() + " on "
                    + method.getName() + " or on " + apiInterface.getName());
        }
        return annotation;
    }

    private AuthenticationProvider create(Annotation annotation) {
        if (annotation instanceof BasicAuth basic) {
            return new BasicProvider(basic);
        }
        if (annotation instanceof ApiKey apiKey) {
            return new ApiKeyProvider(apiKey);
        }
        if (annotation instanceof BearerToken bearer) {
            return new BearerProvider(bearer);
        }
        if (annotation instanceof OAuth2 oauth) {
            return new OAuth2Provider(oauth);
        }
        if (annotation instanceof DigestAuth digest) {
            return new DigestProvider(digest);
        }
        throw new FrameworkException("Unsupported authentication annotation: " + annotation);
    }

    private static final class BasicProvider implements AuthenticationProvider {
        private final String headerValue;

        BasicProvider(BasicAuth auth) {
            String credentials = PlaceholderResolver.resolve(auth.username()) + ":"
                    + PlaceholderResolver.resolve(auth.password());
            this.headerValue = "Basic "
                    + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void apply(HttpRequest request, HttpContext context) {
            request.setHeader("Authorization", headerValue);
        }

        @Override
        public void refresh() {
        }

        @Override
        public boolean isExpired() {
            return false;
        }
    }

    private static final class BearerProvider implements AuthenticationProvider {
        private final String headerValue;

        BearerProvider(BearerToken bearer) {
            this.headerValue = "Bearer " + PlaceholderResolver.resolve(bearer.token());
        }

        @Override
        public void apply(HttpRequest request, HttpContext context) {
            request.setHeader("Authorization", headerValue);
        }

        @Override
        public void refresh() {
        }

        @Override
        public boolean isExpired() {
            return false;
        }
    }

    private static final class ApiKeyProvider implements AuthenticationProvider {
        private final String name;
        private final String value;
        private final boolean inHeader;

        ApiKeyProvider(ApiKey apiKey) {
            this.name = PlaceholderResolver.resolve(apiKey.name());
            this.value = PlaceholderResolver.resolve(apiKey.value());
            this.inHeader = apiKey.inHeader();
        }

        @Override
        public void apply(HttpRequest request, HttpContext context) {
            if (inHeader) {
                request.setHeader(name, value);
                return;
            }
            if (!(request instanceof HttpRequestBase base)) {
                throw new FrameworkException("API key in query requires an HttpRequestBase request");
            }
            try {
                URIBuilder builder = new URIBuilder(base.getURI());
                builder.setParameter(name, value);
                base.setURI(builder.build());
            } catch (URISyntaxException e) {
                throw new FrameworkException("Error adding API key to query parameters", e);
            }
        }

        @Override
        public void refresh() {
        }

        @Override
        public boolean isExpired() {
            return false;
        }
    }

    private static final class OAuth2Provider implements AuthenticationProvider {
        private final TokenManager.Credentials credentials;

        OAuth2Provider(OAuth2 oauth) {
            this.credentials = TokenManager.Credentials.from(oauth);
        }

        @Override
        public void apply(HttpRequest request, HttpContext context) {
            request.setHeader("Authorization", "Bearer " + TokenManager.getToken(credentials));
        }

        @Override
        public void refresh() {
            TokenManager.invalidate(credentials);
        }

        @Override
        public boolean isExpired() {
            return false;
        }

        @Override
        public boolean retryOnUnauthorized() {
            return true;
        }
    }

    private static final class DigestProvider implements AuthenticationProvider, HttpClientAwareAuthenticationProvider {
        private final String username;
        private final String password;
        private final String realm;

        DigestProvider(DigestAuth digest) {
            this.username = PlaceholderResolver.resolve(digest.username());
            this.password = PlaceholderResolver.resolve(digest.password());
            this.realm = PlaceholderResolver.resolve(digest.realm());
        }

        @Override
        public void apply(HttpRequest request, HttpContext context) {
        }

        @Override
        public void refresh() {
        }

        @Override
        public boolean isExpired() {
            return false;
        }

        @Override
        public void configureHttpClient(HttpClientBuilder builder) {
            AuthScope scope = realm.isEmpty() ? AuthScope.ANY
                    : new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT, realm);
            CredentialsProvider provider = new BasicCredentialsProvider();
            provider.setCredentials(scope, new UsernamePasswordCredentials(username, password));
            builder.setDefaultCredentialsProvider(provider);
        }
    }
}