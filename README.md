# FlexiTech Fx Framework - API Client Module 🌐

Declarative HTTP client for the FlexiTech Fx Framework. Describe a REST API as a Java
interface with annotations and the framework builds, registers and injects the
implementation for you.

## Features ✨
- Annotation-driven API interfaces (`@ApiClient`, `@GET`, `@POST`, `@PUT`, `@PATCH`, `@DELETE`, `@HEAD`, `@OPTIONS`)
- Automatic proxy registration and dependency injection (`@Use`, shared with the core framework)
- Path, query, header, body and base URL parameters
- Multiple body formats: JSON, form-urlencoded, multipart/form-data, multipart/related, plain text and raw binary
- File uploads (`File`, `Path`, `byte[]`, `InputStream`) with `@Part` or the `MultipartBody` builder
- Upload progress and cancellation with `ProgressEntity`
- Per-request `Content-Type` override
- JSON serialization and deserialization with Jackson (generics and `java.time` supported)
- Synchronous and asynchronous (`CompletableFuture`) calls, with a bounded async queue
- `ApiResponse<T>` for status and headers, and `StreamResponse` for large downloads
- Built-in authentication: Basic, Bearer, API key, OAuth2 (client credentials with token caching and refresh) and Digest
- Custom authentication providers per call
- Automatic retries with exponential backoff, jitter and `Retry-After` support
- Typed exceptions for HTTP error statuses
- Property-based configuration per API interface, with `${...}` placeholder support
- Request interceptors, configured on the interface with `@ApiClient(interceptors = ...)`
- Uses the JVM proxy settings (`http.proxyHost`, `https.proxyHost` and so on)
- Pluggable transport
- Seamless Java 17+ integration

## Getting Started 🚀

### Requirements
- Java 17+

### Installation
Add to your project via Maven. Use the same version for both modules:
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

### Logging

The framework logs through the Log4j 2 API and does not bring a logging implementation. Add one to your application, for example Log4j Core:

```xml
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-core</artifactId>
    <version>2.23.1</version>
</dependency>
```

Without an implementation, Log4j prints `Log4j2 could not find a logging implementation` and the framework logs nothing. If you use Logback, add `log4j-to-slf4j` instead.

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

`@ApiClient` marks an **interface** as an API client. Only interfaces are processed, and each interface is registered once per application context. Registering the same interface twice in one context fails with a `FrameworkException`. `@ApiClient` can also come from your own meta-annotation.

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

Every API client belongs to the application context that created it. Its properties are read when the context starts, and the client is closed together with the context. Calling a client after its context was closed fails with an `IllegalStateException`.

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

**Every parameter of an interface method needs one of the parameter annotations above.** A parameter without one fails as soon as the client is created, with a message that names the method and the position of the parameter. Earlier versions ignored such parameters silently.

Every `{placeholder}` in a path must have a matching `@Path` parameter. A missing one also fails as soon as the client is created.

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
| `ApiResponse<T>` | The status, the headers and the body. `T` follows the rules in this table. See [ApiResponse](#apiresponse). |
| `StreamResponse` | The response as a stream that is not read into memory. See [Large downloads](#large-downloads-with-streamresponse). |
| Any other type (including generics such as `List<User>`) | Parsed from JSON with Jackson. |
| `CompletableFuture<T>` | Runs the call asynchronously. `T` follows the rules above. |

An empty body returns `null`. Unknown JSON properties are ignored and dates are read and written in ISO format. An invalid JSON response throws `ApiException` with error code `JSON_PARSE_ERROR`.

Apart from `StreamResponse`, responses are read fully into memory. For large downloads, use `StreamResponse`.

#### ApiResponse

Use `ApiResponse<T>` when you need more than the body, for example the `Location` header of a created resource:

```java
@POST("/files")
ApiResponse<FileInfo> create(@Body CreateFileRequest request);
```

```java
ApiResponse<FileInfo> response = api.create(request);
FileInfo info = response.body();
int status = response.status();
String location = response.header("Location").orElse(null);
boolean ok = response.isSuccessful(); // status 200-299
```

| Method | Description |
|--------|-------------|
| `status()` | The HTTP status code. |
| `headers()` | All headers as a `Map<String, List<String>>`, with case-insensitive names. |
| `header(name)` | The first value of a header as an `Optional<String>`. |
| `body()` | The parsed body, or `null` when it is empty. |
| `isSuccessful()` | `true` for status 200 to 299. |

Error statuses (400 and higher) still throw an `ApiException`. Use `ApiResponse<Void>` when you only need the status and headers. It also works inside a `CompletableFuture`.

#### Large downloads with StreamResponse

`StreamResponse` does not read the body into memory. The connection stays open until you close it, so **always use try-with-resources**:

```java
@GET("/files/{id}/content")
StreamResponse download(@Path("id") String id);
```

```java
try (StreamResponse response = api.download("42")) {
    long size = response.contentLength(); // -1 when unknown
    Files.copy(response.inputStream(), target, StandardCopyOption.REPLACE_EXISTING);
}
```

| Method | Description |
|--------|-------------|
| `status()` | The HTTP status code. |
| `header(name)` | The first value of a header as an `Optional<String>`. |
| `contentLength()` | The `Content-Length`, or `-1` when it is unknown. |
| `inputStream()` | The response body as a stream. |
| `close()` | Releases the connection. Closing early aborts the transfer without reading the rest. |

With `CompletableFuture<StreamResponse>` the caller still has to close the response. If an interceptor reads the body in `onResponse`, the stream is already consumed.

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

You can also pass an Apache `HttpEntity` as the body to take full control. It is sent as is. This is also how you add [upload progress](#upload-progress-and-cancellation).

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

#### multipart/related

Some APIs (for example the Google Drive upload endpoint) expect `multipart/related`: every part has only a `Content-Type` header and no `Content-Disposition`. Ask for it with the content type. The first part is usually the metadata and the second the file content:

```java
@POST("/upload/files")
FileInfo upload(@Body(type = BodyType.MULTIPART, contentType = "multipart/related") MultipartBody body);
```

```java
api.upload(MultipartBody.create()
        .json("metadata", new FileMeta("contract", 2024))
        .file("media", new File("contract.pdf")));
```

Part names are not sent in this format, so only the order of the parts matters. The client generates the boundary.

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

### Upload progress and cancellation

Wrap any `HttpEntity` in a `ProgressEntity` and pass it as the body to get progress callbacks and a way to cancel the upload:

```java
@PUT("/blobs/{id}")
void upload(@Path("id") String id, @Body HttpEntity body);
```

```java
AtomicBoolean cancelled = new AtomicBoolean();

ProgressListener listener = new ProgressListener() {
    @Override
    public void onProgress(long transferred, long total) {
        // total is -1 when the length is unknown
        Platform.runLater(() -> progressBar.setProgress(total > 0 ? (double) transferred / total : -1));
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }
};

api.upload("42", new ProgressEntity(new FileEntity(file, ContentType.APPLICATION_OCTET_STREAM), listener));
```

- `onProgress` is called about every 64 KB and once at the end. It runs on the thread that sends the request, so use `Platform.runLater` to touch the UI.
- `isCancelled()` is optional (it returns `false` by default). When it returns `true`, the upload stops with a `RequestCancelledException`. It is an `IOException`, so it follows the rules in [Error Handling](#error-handling-): you get an `ApiException` with error code `IO_ERROR` and the `RequestCancelledException` as the cause, unless the interface method declares a compatible checked exception.
- When a request is retried, the progress starts again from 0.

## Base URL

The base URL is resolved in this order, and the first non-empty value wins:

1. A `@BaseUrl String` parameter on the call
2. `@ApiClient(baseUrl = "...")`
3. The property `api.client.<interfacename>.base.url`
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
Every value in `@ApiClient(baseUrl)` and the authentication annotations may use `${property.name}`. The value is read from `application.properties`, so secrets stay out of source code. A placeholder whose property does not exist fails with a `FrameworkException` that names the missing key. A property that is present but empty is allowed and gives an empty string. Placeholders are not resolved in the paths of `@GET`, `@POST` and the other method annotations.

### OAuth2 details
- Tokens are requested with `grant_type=client_credentials`, and the client id and secret are sent in the request body. They are cached per `clientId` and `tokenUrl`, so all calls share one token.
- A token is refreshed shortly before it expires (up to 60 seconds early). When `expires_in` is missing, 3600 seconds is assumed.
- When the token response includes a `refresh_token`, it is used first. If that fails, the client falls back to client credentials.
- On `401 Unauthorized` the cached token is dropped and the request is retried once with a new token. This only happens when the request body can be sent again (see [Retries](#retries-)).
- A failed token request throws a `FrameworkException` with the status code and, when the server sends them, its `error` and `error_description`.
- Token requests use their own HTTP client with a fixed 10 second timeout. The `ssl.verification` setting and the proxy settings of the API interface do **not** apply to them.
- The annotation has no `scope` or `audience` attribute yet. If your token server needs them, or needs HTTP Basic client authentication, write an `AuthenticationProvider` and pass it with `@Auth` or through `ApiClientConfig`.

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

Keys are per API interface: `api.client.<name>.<setting>`, where `<name>` is the **simple class name of the interface in lowercase**. For `UserApi` the prefix is `api.client.userapi.`. Properties are read when the application context starts.

_Two interfaces with the same simple name (in different packages) read the same keys, so give every API interface a unique name._

```properties
flexi.api.baseUrl=https://api.example.com

api.client.userapi.base.url=https://api.example.com/v1
api.client.userapi.connection.timeout=30000
api.client.userapi.socket.timeout=30000
api.client.userapi.max.connections=10
api.client.userapi.follow.redirects=true
api.client.userapi.ssl.verification=true
api.client.userapi.async.enabled=true
api.client.userapi.async.pool.size=10
api.client.userapi.async.queue.full=reject
api.client.userapi.logging.enabled=true
api.client.userapi.logging.level=INFO
api.client.userapi.retry.max=2
api.client.userapi.retry.backoff=200
api.client.userapi.retry.max.backoff=5000
```

A value that cannot be read (text where a number is expected, or anything other than `true` or `false` for a flag) fails at startup with a `FrameworkException` that names the property and the value. An empty value counts as "not set".

> **Upgrade note:** the keys used to be written in camelCase (`baseUrl`, `maxConnections`, `followRedirects`, `sslVerification`, `enableAsync`, `asyncThreadPoolSize`, `enableLogging`, `loggingLevel`, `retry.maxBackoff`). The old keys still work and log a deprecation warning that names the new key. When both are set, the dotted key wins.

### Connection

| Key | Default | Description |
|-----|---------|-------------|
| `flexi.api.baseUrl` | *(empty)* | Global fallback base URL for every API interface. |
| `api.client.<name>.base.url` | *(empty)* | Base URL for one interface. Used when `@ApiClient(baseUrl)` is empty. |
| `api.client.<name>.connection.timeout` | `30000` | Connect timeout in milliseconds. |
| `api.client.<name>.socket.timeout` | `30000` | Read timeout in milliseconds. Raise it for large uploads. |
| `api.client.<name>.max.connections` | `10` | Maximum pooled connections for the interface. |
| `api.client.<name>.follow.redirects` | `true` | Follow HTTP redirects. When `false`, a `3xx` response is returned like a normal response (the body is parsed, which is usually empty, so you get `null`). Use `ApiResponse<T>` to read the status and the `Location` header. See also [Security Notes](#security-notes-). |
| `api.client.<name>.ssl.verification` | `true` | Verify TLS certificates and host names. TLS 1.2 and 1.3 are used. When set to `false`, a warning is logged at startup. |

### Proxy
The client uses the JVM default `ProxySelector`, so the standard system properties work, for example `-Dhttps.proxyHost=proxy.corp -Dhttps.proxyPort=8080` and `-Dhttp.nonProxyHosts=...`. This is what a desktop application on a corporate network usually needs. The client does not configure proxy credentials, and the OAuth2 token requests do not use the proxy (see [OAuth2 details](#oauth2-details)).

### Async and Logging

| Key | Default | Description |
|-----|---------|-------------|
| `api.client.<name>.async.enabled` | `true` | Run `CompletableFuture` methods on a dedicated thread pool. When `false`, they run on the calling thread and return an already completed future. |
| `api.client.<name>.async.pool.size` | `10` | Size of the async thread pool. Threads are daemon threads. |
| `api.client.<name>.async.queue.full` | `reject` | What happens when the queue of waiting async calls is full: `reject` or `caller-runs`. See below. |
| `api.client.<name>.logging.enabled` | `true` | Log client startup and, at debug level, each request line. |
| `api.client.<name>.logging.level` | `INFO` | Logging level kept in the client configuration. |

The async pool queues up to 100 waiting calls per pool thread. When the queue is full:

- `reject` (default): the returned future fails with an `ApiException` with error code `QUEUE_FULL`.
- `caller-runs`: the call runs on the thread that made it, which slows the caller down instead of failing. Do not use this for clients that are called from the JavaFX Application Thread, because the UI would block.

Cancelling a returned `CompletableFuture` does not stop a request that is already running.

### Retry

| Key | Default | Description |
|-----|---------|-------------|
| `api.client.<name>.retry.max` | `2` | Extra attempts after the first one. `0` turns retries off. |
| `api.client.<name>.retry.backoff` | `200` | Base delay in milliseconds. It doubles on every attempt, plus a random jitter. |
| `api.client.<name>.retry.max.backoff` | `5000` | Upper limit of the delay in milliseconds. It also limits `Retry-After`. |

## Retries 🔁

- Only idempotent methods are retried: `GET`, `HEAD`, `OPTIONS`, `PUT` and `DELETE`. `POST` and `PATCH` are sent once.
- A retry happens on I/O errors and on status `429`, `502`, `503` and `504`.
- A `Retry-After` header with a number of seconds is honored, but never longer than `retry.max.backoff`. A server that asks for 30 seconds is retried after 5 seconds with the default settings, so raise `retry.max.backoff` if you want to wait longer. The HTTP-date form of the header is ignored, and the normal backoff is used instead.
- An OAuth2 `401` refresh is a separate, single retry and does not use up the retry count.
- **A request body that can only be read once is sent once.** A body made from an `InputStream` (as `@Body` or as a `@Part`) cannot be re-sent, so such a request is never retried, and an OAuth2 `401` is returned as an `UnauthorizedException` instead of being retried. Use `File`, `Path` or `byte[]` when you want retries for uploads.
- **A cancelled upload is never retried.** When `ProgressListener.isCancelled()` returns `true`, the `RequestCancelledException` is thrown at once, even for `PUT` and `DELETE`.

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

Every `ApiException` provides `getStatusCode()`, `getResponseBody()` and `getErrorCode()`. The exceptions do not carry the response headers.

The error code is read from the JSON error body, from the fields `code`, `errorCode`, `error` (text) or `error.code`. When none is found it is `UNKNOWN_ERROR`. Some codes come from the client itself:

| Error code | Status | When |
|------------|--------|------|
| `IO_ERROR` | `0` | A network failure, unless the interface method declares a compatible checked exception. In that case the exception is thrown as it is. |
| `QUEUE_FULL` | `0` | The async queue is full and `async.queue.full` is `reject`. |
| `JSON_PARSE_ERROR` | response status | The response body is not valid JSON for the return type. |

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

## Interceptors

An interceptor gets a look at every call: before the request is sent, after the response arrives and when the call fails. Write a class with a public or private **no-argument constructor** and list it on the interface:

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
}
```

```java
@ApiClient(baseUrl = "https://api.example.com",
           interceptors = { AuditInterceptor.class, TimingInterceptor.class })
public interface UserApi {

    @GET("/users/{id}")
    User get(@Path("id") long id);
}
```

All three methods of `Interceptor` are optional (`default` methods), so implement only the ones you need.

| Method | Called |
|--------|--------|
| `onRequest(request, context)` | Before the request is sent. |
| `onResponse(response, context)` | When a response arrives, for every status, before the response is turned into a result or an exception. |
| `onError(exception, context)` | When the call fails: a network error or an error status. |


The `HttpContext` carries the invoked method (`Interceptor.METHOD`, a `java.lang.reflect.Method`), the client configuration (`Interceptor.CONFIG`) and the start time in milliseconds (`Interceptor.TIMESTAMP`):

```java
Method method = (Method) context.getAttribute(Interceptor.METHOD);
```

How interceptors run:

- They run in the order of the array. The framework creates **one instance per interface**, and it is used by all calls (also from different threads), so keep it thread-safe. A class without a no-argument constructor fails at startup with a message that names it.
- They run **once per call**, not once per retry or redirect. `onRequest` runs before the authentication headers are added, so you do not see `Authorization` there.
- Async calls run the interceptors on the async pool thread.
- An exception thrown by a hook is not caught by the client, so it fails the call. In `onRequest` the call stops before anything is sent, and `onError` is not called. An exception from `onResponse` is passed to `onError` and then thrown. Avoid throwing from `onResponse` and `onError`.
- `onRequest` can stop a call by throwing. The exception reaches the caller before anything is sent, and `onError` is not called.
- `onResponse` and `onError` are observers. If one of them throws, the client logs a warning, still calls the remaining interceptors and does not change the result of the call. A failure inside `onError` is added to the original exception as a suppressed exception, so the real error is never hidden.
- Reading the body of the response in `onResponse` consumes it. Wrap the entity in a `BufferedHttpEntity` first if the client still has to read it. This also applies to `StreamResponse`.
- To treat methods differently, read the method from the context and check your own marker annotation. No extra API is needed.

If you create the client yourself with an `ApiClientConfig` (see below), `@ApiClient(interceptors = ...)` is not read. Add them with `addInterceptor(...)`.

## Programmatic Configuration

Properties cover the common settings. For options that have no property key (default headers, a default authentication provider, a custom transport, interceptor instances you create yourself), build an `ApiClientConfig` and create the client yourself:

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

Call `handler.close()` when you are done, to stop the async pool and the connection pool. Clients created by the framework are closed automatically together with the application context.

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
| `asyncQueueFullPolicy(QueueFullPolicy)` | `REJECT` | `REJECT` or `CALLER_RUNS`. |
| `enableLogging(boolean)` / `loggingLevel(String)` | `true` / `INFO` | Logging settings. |
| `maxRetries(int)` / `retryBackoffMillis(long)` / `retryMaxBackoffMillis(long)` | `2` / `200` / `5000` | Retry settings. |

Default headers never override a `Content-Type` that the request body has already set.

## Security Notes 🔒
- Keep secrets in `application.properties` and reference them with `${...}` placeholders.
- Set `ssl.verification=false` only for local development. It disables certificate and host name checks, and the client logs a warning at startup when it is off. It does not apply to OAuth2 token requests.
- **Redirects:** when a redirect goes to a different scheme, host or port, the client sends only `Accept`, `Accept-Charset`, `Accept-Language`, `Content-Type` and `User-Agent`, and drops every other header. That includes `Authorization`, `Proxy-Authorization`, `Cookie`, an API key sent in a header (for example `X-Api-Key`) and headers added by a custom `AuthenticationProvider`. Redirects to the same origin keep all headers. If you need a header to reach a different origin, set `follow.redirects=false` and follow the redirect yourself with `ApiResponse<T>`.
- `@BaseUrl` sends the configured credentials to whatever URL you pass. Only pass values you trust.
- Digest authentication without a `realm` offers its credentials to any host that sends a Digest challenge. Set the `realm` in `@DigestAuth`.
- The startup log prints the base URL. Do not put `user:password@` in it.
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

## Upgrading to 1.1.0 ⬆️

**Removed**
- `ApiInterceptor` and `RequestHook` are gone. Nothing used them. `Interceptor` is the only hook, and you can now set it on the interface with `@ApiClient(interceptors = ...)`.

**Behavior changes**
- A parameter without a request annotation now fails at startup. Earlier versions ignored it.
- A `${...}` placeholder whose property does not exist now fails with a `FrameworkException`. The old text said it resolved to an empty string.
- A non-repeatable body (an `InputStream`) is sent once. Retries and the OAuth2 `401` retry are skipped for it.
- Invalid values in configuration properties (text for a number, or a flag that is not `true` or `false`) now fail at startup with a clear message.
- The client uses the JVM proxy settings.
- A warning is logged at startup when `ssl.verification=false`.
- Redirects to another origin forward only `Accept`, `Accept-Charset`, `Accept-Language`, `Content-Type` and `User-Agent`. Every other header is dropped, including `Authorization`, `Cookie` and API keys sent in a header. This applies to every client, including the ones that use `@BearerToken`, `@BasicAuth` and `@ApiKey`.
- A cancelled upload is no longer retried.
- When the async queue is full, the returned future now fails with the error code `QUEUE_FULL` (the new default is `async.queue.full=reject`). Version 1.0.12 ran the call on the calling thread. Set `async.queue.full=caller-runs` to keep the old behavior.
- The framework no longer brings `log4j-core`. Add a Log4j 2 implementation to your application (see [Logging](#logging)).

**Compatible changes**
- The property keys are now dotted (`max.connections`, `follow.redirects`, `retry.max.backoff` and so on). The old camelCase keys still work, with a deprecation warning.
- The methods of `Interceptor` are `default` methods, so existing implementations still compile.
- New: `ApiResponse<T>`, `StreamResponse`, `ProgressEntity`, `multipart/related` bodies and the `async.queue.full` setting.

## 🤝 Support
For assistance:  
📧 Email: `itech.saizawmyint@gmail.com`  
📑 [Open an Issue](https://github.com/itech-framework/api-client/issues)

---

*Made with ❤️ by the FlexiTech Team*
