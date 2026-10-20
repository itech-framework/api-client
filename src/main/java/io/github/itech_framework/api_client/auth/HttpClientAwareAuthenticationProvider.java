package io.github.itech_framework.api_client.auth;

import org.apache.http.impl.client.HttpClientBuilder;

/**
 * Extension of {@link AuthenticationProvider} that allows the provider to configure
 * the underlying Apache HttpClient builder before the HTTP client is created.
 * <p>
 * This interface is useful for authentication schemes that cannot be handled by simply
 * adding an Authorization header per request (e.g., Digest, NTLM, Kerberos) but require
 * the HTTP client to automatically manage challenge‑response handshakes, credential caching,
 * and request retries.
 * </p>
 *
 * <h2>Why use this interface?</h2>
 * <p>
 * The standard {@link AuthenticationProvider#apply(HttpRequest, HttpContext)} method is called
 * for every request, which works well for stateless/preemptive schemes like Basic Auth or Bearer tokens.
 * However, for stateful/challenge‑based schemes like Digest Authentication, the client must:
 * <ul>
 *   <li>Send the first request <b>without</b> an Authorization header</li>
 *   <li>Receive a 401 response with a {@code WWW-Authenticate: Digest ...} header</li>
 *   <li>Parse the nonce, realm, and other parameters</li>
 *   <li>Compute a digest response and resend the request with the correct header</li>
 * </ul>
 * This handshake is automatically handled by Apache HttpClient when you provide a
 * {@link org.apache.http.client.CredentialsProvider} and register the appropriate
 * {@link org.apache.http.client.auth.AuthSchemeProvider}. By implementing this interface,
 * your {@code AuthenticationProvider} can configure the {@link HttpClientBuilder} once,
 * and let the HTTP client manage the entire digest flow.
 * </p>
 *
 * <h2>How to implement</h2>
 * <ol>
 *   <li>Create a class that implements both {@link AuthenticationProvider} and
 *       {@code HttpClientAwareAuthenticationProvider}.</li>
 *   <li>In {@link #configureHttpClient(HttpClientBuilder)}, set up the credentials provider
 *       and any required auth scheme providers.</li>
 *   <li>Leave {@link AuthenticationProvider#apply(HttpRequest, HttpContext)} empty, because
 *       the HTTP client will automatically add the necessary headers after the first challenge.</li>
 *   <li>Optionally implement {@link AuthenticationProvider#refresh()} and
 *       {@link AuthenticationProvider#isExpired()} if your credentials can expire.</li>
 * </ol>
 *
 * <h2>Example: Digest Authentication Provider</h2>
 * <pre>{@code
 * public class DigestAuthProvider implements AuthenticationProvider, HttpClientAwareAuthenticationProvider {
 *     private final String username;
 *     private final String password;
 *
 *     public DigestAuthProvider() {
 *         // read credentials from properties or configuration
 *         this.username = PropertiesLoader.getProperty("api.client.apiclient.auth.username");
 *         this.password = PropertiesLoader.getProperty("api.client.apiclient.auth.password");
 *     }
 *
 *     @Override
 *     public void apply(HttpRequest request, HttpContext context) {
 *         // nothing to do per request – HttpClient handles it
 *     }
 *
 *     @Override
 *     public void refresh() { }
 *
 *     @Override
 *     public boolean isExpired() { return false; }
 *
 *     @Override
 *     public void configureHttpClient(HttpClientBuilder builder) {
 *         CredentialsProvider credsProvider = new BasicCredentialsProvider();
 *         credsProvider.setCredentials(
 *             AuthScope.ANY,
 *             new UsernamePasswordCredentials(username, password)
 *         );
 *         builder.setDefaultCredentialsProvider(credsProvider);
 *         // Optionally register Digest scheme explicitly (usually auto‑registered)
 *         builder.setDefaultAuthSchemeRegistry(registry -> {
 *             registry.register("digest", new DigestSchemeFactory());
 *         });
 *     }
 * }
 * }</pre>
 *
 * <h2>How the framework uses this interface</h2>
 * <p>
 * The {@code DefaultHttpTransportProvider} (or your custom transport) checks whether the
 * configured {@link AuthenticationProvider} implements this interface. If it does,
 * it calls {@link #configureHttpClient(HttpClientBuilder)} before building the
 * {@link org.apache.http.impl.client.CloseableHttpClient}. This gives the provider
 * full control over the HTTP client's authentication settings.
 * </p>
 *
 * <h2>Notes</h2>
 * <ul>
 *   <li>This interface is completely optional – providers that do not implement it will
 *       continue to work as before (per‑request header injection).</li>
 *   <li>Providers implementing this interface may still implement the standard
 *       {@link AuthenticationProvider#apply} method to add additional headers or perform
 *       pre‑request logic.</li>
 *   <li>If you need to support multiple authentication schemes or dynamic credential refresh,
 *       you can store a reference to the {@code HttpClientBuilder} or the built client.</li>
 * </ul>
 *
 * @author SaiZawMyint
 * @see AuthenticationProvider
 * @see org.apache.http.impl.client.HttpClientBuilder
 * @see org.apache.http.client.CredentialsProvider
 * @since 1.1.0
 */
public interface HttpClientAwareAuthenticationProvider {
    /**
     * Configures the Apache HttpClient builder with the necessary authentication components.
     * <p>
     * This method is called exactly once when the HTTP client is being created.
     * Typical configuration includes:
     * <ul>
     *   <li>Setting a {@link org.apache.http.client.CredentialsProvider} with username/password</li>
     *   <li>Registering custom {@link org.apache.http.client.auth.AuthSchemeProvider}s</li>
     *   <li>Adding interceptors that affect authentication</li>
     * </ul>
     * </p>
     *
     * @param builder the {@link HttpClientBuilder} used to create the HTTP client
     *                (never {@code null})
     */
    void configureHttpClient(HttpClientBuilder builder);
}