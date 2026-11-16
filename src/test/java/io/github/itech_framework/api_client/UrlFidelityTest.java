package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Received;
import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.parameters.BaseUrl;

public class UrlFidelityTest extends BaseClientTest {

	public interface UrlApi {
		@GET("")
		String at(@BaseUrl String url);

		@GET("/ping")
		String ping(@BaseUrl String url);
	}

	private TestServer okServer() throws Exception {
		TestServer server = server();
		server.route("/", (request, call) -> Response.of(200, "ok"));
		return server;
	}

	private Received sent(TestServer server) {
		return server.requests().get(0);
	}

	@Test
	void queryStringIsSentExactlyAsTyped() throws Exception {
		TestServer server = okServer();
		UrlApi api = client(UrlApi.class, config(server).maxRetries(0).build());

		api.at(server.url() + "/p?a=1&a=2&flag&x=a+b&y=c%20d");

		assertEquals("/p", sent(server).rawPath());
		assertEquals("a=1&a=2&flag&x=a+b&y=c%20d", sent(server).rawQuery());
	}

	@Test
	void pathEscapesAreKept() throws Exception {
		TestServer server = okServer();
		UrlApi api = client(UrlApi.class, config(server).maxRetries(0).build());

		api.at(server.url() + "/a%2Fb/c%20d/e+f");

		assertEquals("/a%2Fb/c%20d/e+f", sent(server).rawPath());
	}

	@Test
	void trailingQuestionMarkStillReachesThePath() throws Exception {
		TestServer server = okServer();
		UrlApi api = client(UrlApi.class, config(server).maxRetries(0).build());

		api.at(server.url() + "/p?");

		assertEquals("/p", sent(server).rawPath());
	}

	@Test
	void urlWithoutPathSendsRoot() throws Exception {
		TestServer server = okServer();
		UrlApi api = client(UrlApi.class, config(server).maxRetries(0).build());

		api.at(server.url());

		assertEquals("/", sent(server).rawPath());
	}

	@Test
	void dotSegmentsAreSentAsTypedByDefault() throws Exception {
		TestServer server = okServer();
		UrlApi api = client(UrlApi.class, config(server).maxRetries(0).build());

		api.at(server.url() + "/a/../b");

		assertEquals("/a/../b", sent(server).rawPath());
	}

	@Test
	void duplicateSlashesAreCollapsedByDefault() throws Exception {
		TestServer server = okServer();
		UrlApi api = client(UrlApi.class, config(server).maxRetries(0).build());

		api.at(server.url() + "/a//b");

		assertEquals("/a/b", sent(server).rawPath());
	}

	@Test
	void duplicateSlashesAreKeptWhenNormalizeUriIsOff() throws Exception {
		TestServer server = okServer();
		UrlApi api = client(UrlApi.class, config(server).maxRetries(0).normalizeUri(false).build());

		api.at(server.url() + "/a//b");

		assertEquals("/a//b", sent(server).rawPath());
	}

	@Test
	void dotSegmentsAreKeptWhenNormalizeUriIsOff() throws Exception {
		TestServer server = okServer();
		UrlApi api = client(UrlApi.class, config(server).maxRetries(0).normalizeUri(false).build());

		api.at(server.url() + "/a/../b");

		assertEquals("/a/../b", sent(server).rawPath());
	}

	@Test
	void methodPathIsAppendedToABaseUrlWithItsOwnPath() throws Exception {
		TestServer server = okServer();
		UrlApi api = client(UrlApi.class, config(server).maxRetries(0).build());

		api.ping(server.url() + "/base");

		assertEquals("/base/ping", sent(server).rawPath());
	}
}