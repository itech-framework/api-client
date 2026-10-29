# FlexiTech Fx Framework - API Client Module 🌐

Declarative HTTP client for the FlexiTech Fx Framework. Describe a REST API as a Java
interface with annotations and the framework builds, registers and injects the
implementation for you.

## Features ✨
- Annotation-driven API interfaces (`@ApiClient`, `@GET`, `@POST`, `@PUT`, `@PATCH`, `@DELETE`, `@HEAD`, `@OPTIONS`)
- Automatic proxy registration and dependency injection (`@Use`, shared with the core framework)
- Path, query, header, body and base URL parameters
- Multiple body formats: JSON, form-urlencoded, multipart/form-data, plain text and raw binary
- File uploads (`File`, `Path`, `byte[]`, `InputStream`) with `@Part` or the `MultipartBody` builder
- Per-request `Content-Type` override
- JSON serialization and deserialization with Jackson (generics and `java.time` supported)
- Synchronous and asynchronous (`CompletableFuture`) calls
- Built-in authentication: Basic, Bearer, API key, OAuth2 (client credentials with token caching and refresh) and Digest
- Custom authentication providers per call
- Automatic retries with exponential backoff, jitter and `Retry-After` support
- Typed exceptions for HTTP error statuses
- Property-based configuration per API interface, with `${...}` placeholder support
- Pluggable transport and request interceptors
- Seamless Java 17+ integration

## Getting Started 🚀

### Requirements
- Java 17+

### Installation
Add to your project via Maven:
```xml
<dependencies>
    <dependency>
        <groupId>io.github.itech-framework</groupId>
        <artifactId>core</artifactId>
        <version>${framework-version}</version>
    </dependency>

    <dependency>
        <groupId>io.github.itech-framework</groupId>
        <artifactId>api-client</artifactId>
        <version>${framework-version}</version>
    </dependency>
</dependencies>
```

The module registers itself with the core framework (`ComponentInitializer`), so no bootstrap code is needed.

Multipart support uses Apache `httpmime`, which comes with the module as a transitive dependency. You do not need to add it yourself.

### Explanation Of Example Usage
- Define an API interface: `UserApi.java`
```java
@ApiClient(baseUrl = "https://api.example.com/v1")
public interface UserApi {

    @GET("/users")
    List<User> list(@Query("page") int page, @Query("tag") List<String> tags);

    @GET("/users/{id}")
    User get(@Path("id") long id);

    @POST("/users")
    User create(@Body CreateUserRequest request);

    @PUT("/users/{id}")
    User update(@Path("id") long id, @Body UpdateUserRequest request);

    @DELETE("/users/{id}")
    void delete(@Path("id") long id);

    @GET("/users/{id}")
    CompletableFuture<User> getAsync(@Path("id") long id);
}
```

`@ApiClient` marks an **interface** as an API client. Only interfaces are processed, and each interface is registered once. Registering the same interface twice fails with a `FrameworkException`.

- Inject and use it, exactly like any other framework component:
```java
@FxController
public class UserController implements Routable {
    @Use
    UserApi userApi;

    @OnInit
    public void initialized() {
        userApi.getAsync(1L)
                .thenAccept(user -> Platform.runLater(() -> nameLabel.setText(user.getName())))
                .exceptionally(error -> {
                    Platform.runLater(() -> nameLabel.setText("Failed to load user"));
                    return null;
                });
    }
}
```

Blocking (synchronous) calls should not run on the JavaFX Application Thread. Return a `CompletableFuture` from the interface method, or call the client from a background task.

## Defining Requests

### HTTP method annotations

| Annotation | Description |
|------------|-------------|
| `@GET("/path")` | HTTP GET |
| `@POST("/path")` | HTTP POST |
| `@PUT("/path")` | HTTP PUT |
| `@PATCH("/path")` | HTTP PATCH |
| `@DELETE("/path")` | HTTP DELETE |
| `@HEAD("/path")` | HTTP HEAD |
| `@OPTIONS("/path")` | HTTP OPTIONS |

Every interface method needs exactly one of these. `default` and `static` methods are left alone.

### Parameter annotations

| Annotation | Target | Description |
|------------|--------|-------------|
| `@Path("name")` | parameter | Fills the `{name}` placeholder in the path. The value is URL-encoded and must not be `null`. |
| `@Query("name")` | parameter | Adds a query parameter. `null` values are skipped. Collections and arrays add one parameter per item. |
| `@Body` | parameter | The request body. Format chosen automatically, or set with `@Body(type = ..., contentType = "...")`. See [Request bodies](#request-bodies). |
| `@Part("name")` | parameter | One part of a `multipart/form-data` request (files, text, JSON). |
| `@Field("name")` | parameter | One field of an `application/x-www-form-urlencoded` request. |
| `@Headers` | parameter | A `Map<String, String>` of extra headers for this call. |
| `@BaseUrl` | parameter | A `String` that replaces the base URL for this call. |
| `@Auth` | parameter | An `AuthenticationProvider` used for this call only. |
| `@Header(name, value)` | method | A fixed header. Repeatable. |

Every `{placeholder}` in a path must have a matching `@Path` parameter. A missing one fails as soon as the client is created.

```java
@ApiClient(baseUrl = "https://api.example.com")
public interface ReportApi {

    @GET("/reports/{year}/{month}")
    @Header(name = "Accept", value = "application/json")
    @Header(name = "X-Client", value = "planner")
    Report get(@Path("year") int year,
               @Path("month") int month,
               @Query("fields") String[] fields,
               @Headers Map<String, String> extraHeaders);

    @GET("/status")
    String status(@BaseUrl String serverUrl);
}
```

### Return types

| Return type | Behavior |
|-------------|----------|
| `void` / `Void` | The response body is discarded. |
| `String` | The raw response body. |
| `byte[]` | The raw response bytes. |
| `InputStream` | The response bytes as an in-memory stream. |
| Any other type (including generics such as `List<User>`) | Parsed from JSON with Jackson. |
| `CompletableFuture<T>` | Runs the call asynchronously. `T` follows the rules above. |

An empty body returns `null`. Unknown JSON properties are ignored and dates are read and written in ISO format. An invalid JSON response throws `ApiException` with error code `JSON_PARSE_ERROR`.

Responses are read fully into memory, so avoid very large downloads for now.

## Request bodies

A request body comes from exactly one of these:

- one `@Body` parameter, or
- one or more `@Part` parameters (sent as multipart), or
- one or more `@Field` parameters (sent as a form).

`@Body` cannot be combined with `@Part` or `@Field`, and `@Part` cannot be combined with `@Field`. A wrong combination fails as soon as the client is created. A `null` body is simply not sent, and bodies are only sent for methods that support them (POST, PUT, PATCH).

### Body types

`@Body(type = ...)` selects how the value is encoded. The default is `AUTO`.

| `BodyType` | Accepted values | Default Content-Type |
|------------|-----------------|----------------------|
| `AUTO` | Decided from the content type and the value (see below) | depends |
| `JSON` | Any object. A `String` or `byte[]` is assumed to be JSON already and is sent as is. | `application/json` |
| `FORM` | `Map` or `List<NameValuePair>`. Collection values add one entry per item. | `application/x-www-form-urlencoded; charset=UTF-8` |
| `MULTIPART` | `MultipartBody` or `Map` | `multipart/form-data` with a generated boundary |
| `TEXT` | `String` | `text/plain; charset=UTF-8` |
| `BINARY` | `byte[]`, `File`, `Path`, `InputStream` | `application/octet-stream` (probed from the file name for `File` and `Path`) |

With `AUTO` the format is chosen in this order:

1. From the content type: `multipart/*` → multipart, `application/x-www-form-urlencoded` → form, `application/json` or `*+json` → JSON.
2. From the value: `MultipartBody` → multipart, `byte[]` / `File` / `Path` / `InputStream` → binary, `String` → text.
3. Anything else → JSON.

You can also pass an Apache `HttpEntity` as the body to take full control. It is sent as is.

### Content-Type override

The `Content-Type` of a request is the first of these that is set:

1. `@Body(contentType = "...")`
2. A `Content-Type` header from `@Header` or `@Headers`
3. The default of the resolved body type

```java
@POST("/articles")
Article create(@Body(contentType = "application/vnd.api+json") ArticleDoc doc);

@POST("/images")
FileInfo image(@Body(contentType = "image/png") byte[] png);
```

For multipart requests the client always generates the boundary and sets the final `Content-Type` itself. A hand-written `multipart/form-data` header without a boundary would produce a broken request, so it is replaced.

### JSON

Objects are serialized with Jackson and sent as `application/json`:

```java
@POST("/users")
User create(@Body CreateUserRequest request);

// A String is sent as is, not serialized a second time
@POST("/raw")
String raw(@Body(type = BodyType.JSON) String json);
```

> **Upgrade note:** a `String` body used to be serialized as a JSON string (with quotes). It is now sent as is: as `text/plain`, or as raw JSON when you set `type = BodyType.JSON` or `contentType = "application/json"`.

### Forms (`application/x-www-form-urlencoded`)

Use `@Field` parameters:

```java
@POST("/login")
Token login(@Field("username") String username,
            @Field("password") String password,
            @Field("scope") List<String> scopes);
```

Or pass a `Map` as the body:

```java
@POST("/oauth/token")
TokenResponse token(@Body(type = BodyType.FORM) Map<String, String> form);
```

Setting the header `Content-Type: application/x-www-form-urlencoded` with a `Map` body also works. `null` values are skipped.

### Multipart uploads (`multipart/form-data`)

Use `@Part` parameters. Any method with `@Part` parameters is sent as multipart:

```java
@POST("/files")
FileInfo upload(@Part("file") File file,
                @Part("description") String description,
                @Part("meta") FileMeta meta);

@POST("/files/batch")
List<FileInfo> uploadMany(@Part("files") List<File> files);

@POST("/avatar")
void avatar(@Part(value = "image", filename = "me.png", contentType = "image/png") byte[] data);
```

| `@Part` value | Sent as |
|---------------|---------|
| `File` / `Path` | A file part. The file name comes from the file, and the content type is probed from it. |
| `byte[]` / `InputStream` | A file part. The file name defaults to the part name, and the content type to `application/octet-stream`. |
| `String`, numbers, booleans, enums | A text part (`text/plain; charset=UTF-8`). |
| Any other object | A JSON part (`application/json`). |
| `Collection` / array | One part per item, all with the same name. |
| `null` | Skipped. |

`@Part` has two optional attributes: `filename` and `contentType`.

For dynamic cases (custom file names, in-memory data, parts decided at runtime) pass a `MultipartBody`:

```java
@POST("/files")
FileInfo upload(@Body MultipartBody body);
```

```java
api.upload(MultipartBody.create()
        .field("description", "Scanned contract")
        .file("file", new File("contract.pdf"))
        .file("thumbnail", "thumb.png", thumbnailBytes, "image/png")
        .json("meta", new FileMeta("contract", 2024)));
```

| `MultipartBody` method | Description |
|------------------------|-------------|
| `field(name, value)` | A text part (or a JSON part for objects). |
| `json(name, value)` | A part serialized as `application/json`. |
| `file(name, File)` / `file(name, Path)` | A file part. |
| `file(name, filename, byte[], contentType)` | An in-memory file part. |
| `file(name, filename, InputStream, contentType)` | A streamed file part. |
| `part(name, content, filename, contentType)` | The general form of all of the above. |

A `Map<String, Object>` body with `type = BodyType.MULTIPART` is also accepted and follows the same rules.

### Raw and binary bodies

```java
// Streamed from disk, content type probed from the file name
@PUT("/blobs/{id}")
void put(@Path("id") String id, @Body Path file);

// Raw bytes with an explicit content type
@POST("/images")
FileInfo image(@Body(contentType = "image/png") byte[] png);
```

`File` and `Path` bodies are streamed from disk and are not loaded into memory. A missing file fails with an `IllegalArgumentException`.

## Base URL

The base URL is resolved in this order, and the first non-empty value wins:

1. A `@BaseUrl String` parameter on the call
2. `@ApiClient(baseUrl = "...")`
3. The property `api.client.<interfacename>.baseUrl`
4. The global property `flexi.api.baseUrl`

If none of them is set, the call fails with a `FrameworkException` that lists these options. Any path in the base URL is kept and the method path is appended to it.

## Authentication 🔐

Add `@Authenticated(AuthType.X)` to the interface or to a single method, together with the matching annotation. A method-level setting overrides the interface-level one.

| `AuthType` | Annotation | Behavior |
|------------|-----------|----------|
| `BASIC` | `@BasicAuth(username, password)` | Sends an `Authorization: Basic ...` header. |
| `BEARER` | `@BearerToken(token)` | Sends an `Authorization: Bearer ...` header. |
| `API_KEY` | `@ApiKey(name, value, inHeader)` | Sends the key in a header (default) or as a query parameter (`inHeader = false`). |
| `OAUTH2` | `@OAuth2(clientId, clientSecret, tokenUrl)` | Client-credentials flow with token caching, refresh and automatic retry on `401`. |
| `DIGEST` | `@DigestAuth(username, password, realm)` | Challenge-response handshake handled by the HTTP client. `realm` is optional. |

A missing matching annotation fails with a `FrameworkException` that names the method or interface.

```java
@ApiClient(baseUrl = "${app.api.url}")
@Authenticated(AuthType.BEARER)
@BearerToken(token = "${app.api.token}")
public interface SecureApi {

    @GET("/me")
    User me();

    @GET("/public/info")
    String info(@Auth AuthenticationProvider override);
}
```

```java
@ApiClient(baseUrl = "${app.api.url}")
@Authenticated(AuthType.OAUTH2)
@OAuth2(clientId = "${oauth.client-id}", clientSecret = "${oauth.client-secret}", tokenUrl = "${oauth.token-url}")
public interface ReportingApi {

    @GET("/reports")
    List<Report> reports();
}
```

### Placeholders
Every value in `@ApiClient(baseUrl)` and the authentication annotations may use `${property.name}`. The value is read from `application.properties`, so secrets stay out of source code. Unknown placeholders resolve to an empty string. Placeholders are not resolved in the paths of `@GET`, `@POST` and the other method annotations.

### OAuth2 details
- Tokens are requested with `grant_type=client_credentials` and cached per `clientId` and `tokenUrl`, so all calls share one token.
- A token is refreshed shortly before it expires (up to 60 seconds early). When `expires_in` is missing, 3600 seconds is assumed.
- When the token response includes a `refresh_token`, it is used first. If that fails, the client falls back to client credentials.
- On `401 Unauthorized` the cached token is dropped and the request is retried once with a new token.

### Which authentication wins
For each call the client uses the first of these that exists:
1. An `@Auth` parameter
2. The `@Authenticated` setting on the method, then on the interface
3. The authentication provider set on `ApiClientConfig` (see [Programmatic Configuration](#programmatic-configuration))

### Custom authentication
Implement `AuthenticationProvider` and pass it through an `@Auth` parameter:

```java
public class ApiTokenProvider implements AuthenticationProvider {
    private volatile String token;

    public ApiTokenProvider(String token) {
        this.token = token;
    }

    @Override
    public void apply(HttpRequest request, HttpContext context) {
        request.setHeader("X-Auth-Token", token);
    }

    @Override
    public void refresh() {
    }

    @Override
    public boolean isExpired() {
        return false;
    }
}
```

| Method | Description |
|--------|-------------|
| `apply(request, context)` | Adds credentials to the request. |
| `refresh()` | Called when `isExpired()` is true, and after a `401` when `retryOnUnauthorized()` is true. |
| `isExpired()` | Whether credentials must be refreshed before the request. |
| `retryOnUnauthorized()` | Optional (default `false`). Return `true` to retry once after a `401`. |

For schemes that need the HTTP client itself to manage the handshake (Digest, NTLM, Kerberos), also implement `HttpClientAwareAuthenticationProvider` and configure the `HttpClientBuilder` in `configureHttpClient`.

## Configuration ⚙️

All keys go in `application.properties` (or a profile override such as `application-dev.properties`). Every key has a default, so you only need to set the ones you want to change.

Keys are per API interface: `api.client.<name>.<setting>`, where `<name>` is the **simple class name of the interface in lowercase**. For `UserApi` the prefix is `api.client.userapi.`.

```properties
flexi.api.baseUrl=https://api.example.com

api.client.userapi.baseUrl=https://api.example.com/v1
api.client.userapi.connection.timeout=30000
api.client.userapi.socket.timeout=30000
api.client.userapi.maxConnections=10
api.client.userapi.followRedirects=true
api.client.userapi.sslVerification=true
api.client.userapi.enableAsync=true
api.client.userapi.asyncThreadPoolSize=10
api.client.userapi.enableLogging=true
api.client.userapi.loggingLevel=INFO
api.client.userapi.retry.max=2
api.client.userapi.retry.backoff=200
api.client.userapi.retry.maxBackoff=5000
```

### Connection

| Key | Default | Description |
|-----|---------|-------------|
| `flexi.api.baseUrl` | *(empty)* | Global fallback base URL for every API interface. |
| `api.client.<name>.baseUrl` | *(empty)* | Base URL for one interface. Used when `@ApiClient(baseUrl)` is empty. |
| `api.client.<name>.connection.timeout` | `30000` | Connect timeout in milliseconds. |
| `api.client.<name>.socket.timeout` | `30000` | Read timeout in milliseconds. Raise it for large uploads. |
| `api.client.<name>.maxConnections` | `10` | Maximum pooled connections for the interface. |
| `api.client.<name>.followRedirects` | `true` | Follow HTTP redirects. |
| `api.client.<name>.sslVerification` | `true` | Verify TLS certificates and host names. TLS 1.2 and 1.3 are used. |

### Async and Logging

| Key | Default | Description |
|-----|---------|-------------|
| `api.client.<name>.enableAsync` | `true` | Run `CompletableFuture` methods on a dedicated thread pool. When `false`, they run on the calling thread and return an already completed future. |
| `api.client.<name>.asyncThreadPoolSize` | `10` | Size of the async thread pool. Threads are daemon threads. |
| `api.client.<name>.enableLogging` | `true` | Log client startup and, at debug level, each request line. |
| `api.client.<name>.loggingLevel` | `INFO` | Logging level kept in the client configuration. |

### Retry

| Key | Default | Description |
|-----|---------|-------------|
| `api.client.<name>.retry.max` | `2` | Extra attempts after the first one. `0` turns retries off. |
| `api.client.<name>.retry.backoff` | `200` | Base delay in milliseconds. It doubles on every attempt, plus a random jitter. |
| `api.client.<name>.retry.maxBackoff` | `5000` | Upper limit of the delay in milliseconds. |

## Retries 🔁

- Only idempotent methods are retried: `GET`, `HEAD`, `OPTIONS`, `PUT` and `DELETE`. `POST` and `PATCH` are sent once.
- A retry happens on I/O errors and on status `429`, `502`, `503` and `504`.
- A `Retry-After` header (in seconds) is honored, up to `retry.maxBackoff`.
- An OAuth2 `401` refresh is a separate, single retry and does not use up the retry count.
- A body made from an `InputStream` (as `@Body` or as a `@Part`) can only be read once and cannot be re-sent. For `PUT` and `DELETE` methods with retries enabled, prefer `File`, `Path` or `byte[]`.

## Error Handling 🚨

Responses with status `400` or higher throw an unchecked `ApiException` (or a subclass):

| Status | Exception |
|--------|-----------|
| 400 | `BadRequestException` |
| 401 | `UnauthorizedException` |
| 403 | `ForbiddenException` |
| 404 | `NotFoundException` |
| 409 | `ConflictException` |
| 429 | `TooManyRequestsException` |
| 500 | `InternalServerErrorException` |
| 503 | `ServiceUnavailableException` |
| other 4xx | `ClientErrorException` |
| other 5xx | `ServerErrorException` |

Every `ApiException` provides `getStatusCode()`, `getResponseBody()` and `getErrorCode()`.

The error code is read from the JSON error body, from the fields `code`, `errorCode`, `error` (text) or `error.code`. When none is found it is `UNKNOWN_ERROR`. Network failures throw `ApiException` with status `0` and error code `IO_ERROR`, unless the interface method declares a compatible checked exception, in which case that exception is thrown as it is.

Invalid body input, such as a missing upload file, a wrong body type for the chosen format or an invalid content type, fails with an `IllegalArgumentException` before the request is sent.

```java
try {
    User user = userApi.get(42L);
} catch (NotFoundException e) {
    showMessage("User not found");
} catch (UnauthorizedException e) {
    router.to("login");
} catch (ApiException e) {
    log.error("Request failed: {} {} {}", e.getStatusCode(), e.getErrorCode(), e.getResponseBody());
}
```

For `CompletableFuture` methods the exception arrives wrapped in a `CompletionException`:

```java
userApi.getAsync(42L).exceptionally(error -> {
    if (error.getCause() instanceof NotFoundException) {
        return null;
    }
    throw new CompletionException(error);
});
```

## Programmatic Configuration

Properties cover the common settings. For options that have no property key (default headers, interceptors, a default authentication provider, a custom transport), build an `ApiClientConfig` and create the client yourself:

```java
ApiClientConfig config = ApiClientConfig.builder()
        .baseUrl("https://api.example.com")
        .defaultHeader("Accept", "application/json")
        .addInterceptor(new AuditInterceptor())
        .connectTimeout(10_000)
        .maxRetries(3)
        .build();

DynamicApiClientInvocationHandler handler = new DynamicApiClientInvocationHandler(UserApi.class, config);

UserApi userApi = (UserApi) Proxy.newProxyInstance(
        UserApi.class.getClassLoader(),
        new Class<?>[] { UserApi.class },
        handler);
```

Call `handler.close()` when you are done, to stop the async pool and the connection pool. Clients created by the framework are closed automatically on shutdown.

### ApiClientConfig builder

| Method | Default | Description |
|--------|---------|-------------|
| `baseUrl(String)` | *(empty)* | Base URL. |
| `connectTimeout(int)` / `socketTimeout(int)` | `30000` | Timeouts in milliseconds. |
| `maxConnections(int)` | `10` | Connection pool size. |
| `followRedirects(boolean)` | `true` | Follow redirects. |
| `sslVerification(boolean)` | `true` | TLS verification. |
| `defaultHeader(name, value)` / `defaultHeaders(Map)` | *(none)* | Headers added to every request that does not already set them. |
| `addInterceptor(Interceptor)` | *(none)* | Request, response and error hooks. |
| `authenticationProvider(AuthenticationProvider)` | *(none)* | Fallback authentication for calls without their own. |
| `httpTransportProvider(HttpTransportProvider)` | built-in | Replace the HTTP transport. |
| `enableAsync(boolean)` / `asyncThreadPoolSize(int)` | `true` / `10` | Async settings. |
| `enableLogging(boolean)` / `loggingLevel(String)` | `true` / `INFO` | Logging settings. |
| `maxRetries(int)` / `retryBackoffMillis(long)` / `retryMaxBackoffMillis(long)` | `2` / `200` / `5000` | Retry settings. |

Default headers never override a `Content-Type` that the request body has already set.

### Interceptors

```java
public class AuditInterceptor implements Interceptor {
    @Override
    public void onRequest(HttpRequest request, HttpContext context) {
        log.info("-> {}", request.getRequestLine());
    }

    @Override
    public void onResponse(HttpResponse response, HttpContext context) {
        log.info("<- {}", response.getStatusLine());
    }

    @Override
    public void onError(Exception exception, HttpContext context) {
        log.error("Request failed", exception);
    }
}
```

The `HttpContext` carries the invoked `method`, the client `config` and the start `timestamp`.

## Security Notes 🔒
- Keep secrets in `application.properties` and reference them with `${...}` placeholders.
- Set `sslVerification=false` only for local development. It disables certificate and host name checks.
- Do not build upload file paths from untrusted input. `@Part` and `@Body` read exactly the `File` or `Path` you pass in.

## Using with the JavaFX Module
The API client works with the [java-fx](https://github.com/itech-framework/java-fx) module through the shared `@Use` injection. Inject your API interface into an `@FxController` or `@FxComponent`, call it asynchronously, and update the UI with `Platform.runLater`. Use the FX toast and dialog components to report `ApiException` errors to the user.

```java
@FxController
public class UploadController implements Routable {
    @Use
    FileApi fileApi;

    void upload(File selected) {
        fileApi.uploadAsync(selected, "Profile photo")
                .thenAccept(info -> Platform.runLater(() -> status.setText("Uploaded " + info.getName())))
                .exceptionally(error -> {
                    Platform.runLater(() -> status.setText("Upload failed"));
                    return null;
                });
    }
}
```

## 🤝 Support
For assistance:  
📧 Email: `itech.saizawmyint@gmail.com`  
📑 [Open an Issue](https://github.com/itech-framework/api-client/issues)

---

*Made with ❤️ by the FlexiTech Team*
