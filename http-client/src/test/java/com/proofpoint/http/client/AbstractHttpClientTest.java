package com.proofpoint.http.client;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.common.io.ByteStreams;
import com.proofpoint.http.client.DynamicBodySource.Writer;
import com.proofpoint.log.Logging;
import com.proofpoint.testing.Assertions;
import com.proofpoint.units.Duration;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Throwables.propagate;
import static com.google.common.base.Throwables.propagateIfInstanceOf;
import static com.proofpoint.concurrent.Threads.threadsNamed;
import static com.proofpoint.http.client.Request.Builder.prepareDelete;
import static com.proofpoint.http.client.Request.Builder.prepareGet;
import static com.proofpoint.http.client.Request.Builder.preparePost;
import static com.proofpoint.http.client.Request.Builder.preparePut;
import static com.proofpoint.testing.Assertions.assertGreaterThan;
import static com.proofpoint.testing.Assertions.assertLessThan;
import static com.proofpoint.testing.Closeables.closeQuietly;
import static com.proofpoint.tracetoken.TraceTokenManager.createAndRegisterNewRequestToken;
import static com.proofpoint.tracetoken.TraceTokenManager.getCurrentRequestToken;
import static com.proofpoint.units.Duration.nanosSince;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public abstract class AbstractHttpClientTest
{
    protected EchoServlet servlet;
    protected Server server;
    protected URI baseURI;
    private String scheme = "http";
    private String host = "127.0.0.1";
    private String keystore = null;
    protected RequestStats stats;

    protected AbstractHttpClientTest()
    {
    }

    protected AbstractHttpClientTest(String host, String keystore)
    {
        scheme = "https";
        this.host = host;
        this.keystore = keystore;
    }

    public abstract <T, E extends Exception> T executeRequest(Request request, ResponseHandler<T, E> responseHandler)
            throws Exception;

    public abstract <T, E extends Exception> T executeRequest(HttpClientConfig config, Request request, ResponseHandler<T, E> responseHandler)
            throws Exception;

    @BeforeSuite
    public void setupSuite()
    {
        Logging.initialize();
    }

    @BeforeMethod
    public void abstractSetup()
            throws Exception
    {
        servlet = new EchoServlet();

        int port;
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(0));
            port = socket.getLocalPort();
        }
        baseURI = new URI(scheme, null, host, port, null, null, null);

        Server server = new Server();

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.setSendServerVersion(false);
        httpConfiguration.setSendXPoweredBy(false);

        ServerConnector connector;
        if (keystore != null) {
            httpConfiguration.addCustomizer(new SecureRequestCustomizer());

            SslContextFactory sslContextFactory = new SslContextFactory(keystore);
            sslContextFactory.setKeyStorePassword("changeit");
            SslConnectionFactory sslConnectionFactory = new SslConnectionFactory(sslContextFactory, "http/1.1");

            connector = new ServerConnector(server, sslConnectionFactory, new HttpConnectionFactory(httpConfiguration));
        }
        else {
            connector = new ServerConnector(server, new HttpConnectionFactory(httpConfiguration));
        }

        connector.setIdleTimeout(30000);
        connector.setName(scheme);
        connector.setPort(port);

        server.addConnector(connector);

        ServletHolder servletHolder = new ServletHolder(servlet);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.addServlet(servletHolder, "/*");
        HandlerCollection handlers = new HandlerCollection();
        handlers.addHandler(context);
        server.setHandler(handlers);

        this.server = server;
        server.start();
    }

    @AfterMethod
    public void abstractTeardown()
            throws Exception
    {
        if (server != null) {
            server.stop();
        }
    }

    @Test(enabled = false, description = "This takes over a minute to run")
    public void test100kGets()
            throws Exception
    {
        URI uri = baseURI.resolve("/road/to/nowhere?query");
        Request request = prepareGet()
                .setUri(uri)
                .addHeader("foo", "bar")
                .addHeader("dupe", "first")
                .addHeader("dupe", "second")
                .build();

        for (int i = 0; i < 100_000; i++) {
            try {
                int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
                assertEquals(statusCode, 200);
            }
            catch (Exception e) {
                throw new Exception("Error on request " + i, e);
            }
        }
    }

    @Test(timeOut = 2000)
    public void testConnectTimeout()
            throws Exception
    {
        try (BackloggedServer server = new BackloggedServer()) {
            HttpClientConfig config = new HttpClientConfig();
            config.setConnectTimeout(new Duration(5, MILLISECONDS));
            config.setReadTimeout(new Duration(2, SECONDS));

            Request request = prepareGet()
                    .setUri(new URI(scheme, null, host, server.getPort(), "/", null, null))
                    .build();

            long start = System.nanoTime();
            try {
                executeRequest(config, request, new CaptureExceptionResponseHandler());
                fail("expected exception");
            }
            catch (CapturedException e) {
                Throwable t = e.getCause();
                if (!isConnectTimeout(t)) {
                    fail("unexpected exception: " + t);
                }
                assertLessThan(nanosSince(start), new Duration(300, MILLISECONDS));
            }
        }
    }

    @Test(expectedExceptions = {ConnectException.class, SocketTimeoutException.class})
    public void testConnectionRefused()
            throws Exception
    {
        int port = findUnusedPort();

        HttpClientConfig config = new HttpClientConfig();
        config.setConnectTimeout(new Duration(5, MILLISECONDS));

        Request request = prepareGet()
                .setUri(new URI(scheme, null, host, port, "/", null, null))
                .build();

        try {
            executeRequest(config, request, new CaptureExceptionResponseHandler());
            fail("expected exception");
        }
        catch (CapturedException e) {
            propagateIfInstanceOf(e.getCause(), Exception.class);
            propagate(e.getCause());
        }
    }

    @Test
    public void testConnectionRefusedWithDefaultingResponseExceptionHandler()
            throws Exception
    {
        int port = findUnusedPort();

        HttpClientConfig config = new HttpClientConfig();
        config.setConnectTimeout(new Duration(5, MILLISECONDS));

        Request request = prepareGet()
                .setUri(new URI(scheme, null, host, port, "/", null, null))
                .build();

        Object expected = new Object();
        assertEquals(executeRequest(config, request, new DefaultOnExceptionResponseHandler(expected)), expected);
    }


    @Test(expectedExceptions = {UnknownHostException.class, UnresolvedAddressException.class}, timeOut = 10000)
    public void testUnresolvableHost()
            throws Exception
    {
        String invalidHost = "nonexistent.invalid";
        assertUnknownHost(invalidHost);

        HttpClientConfig config = new HttpClientConfig();
        config.setConnectTimeout(new Duration(5, SECONDS));

        Request request = prepareGet()
                .setUri(URI.create("http://" + invalidHost))
                .build();

        try {
            executeRequest(config, request, new CaptureExceptionResponseHandler());
            fail("Expected exception");
        }
        catch (CapturedException e) {
            propagateIfInstanceOf(e.getCause(), Exception.class);
            propagate(e.getCause());
        }
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testBadPort()
            throws Exception
    {
        HttpClientConfig config = new HttpClientConfig();
        config.setConnectTimeout(new Duration(5, MILLISECONDS));

        Request request = prepareGet()
                .setUri(new URI(scheme, null, host, 70_000, "/", null, null))
                .build();

        try {
            executeRequest(config, request, new CaptureExceptionResponseHandler());
            fail("expected exception");
        }
        catch (CapturedException e) {
            propagateIfInstanceOf(e.getCause(), Exception.class);
            propagate(e.getCause());
        }
    }

    @Test
    public void testDeleteMethod()
            throws Exception
    {
        URI uri = baseURI.resolve("/road/to/nowhere");
        Request request = prepareDelete()
                .setUri(uri)
                .addHeader("foo", "bar")
                .addHeader("dupe", "first")
                .addHeader("dupe", "second")
                .build();

        int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
        assertEquals(statusCode, 200);
        assertEquals(servlet.requestMethod, "DELETE");
        assertEquals(servlet.requestUri, uri);
        assertEquals(servlet.requestHeaders.get("foo"), ImmutableList.of("bar"));
        assertEquals(servlet.requestHeaders.get("dupe"), ImmutableList.of("first", "second"));
        assertEquals(servlet.requestHeaders.get("x-custom-filter"), ImmutableList.of("customvalue"));
    }

    @Test
    public void testErrorResponseBody()
            throws Exception
    {
        servlet.responseStatusCode = 500;
        servlet.responseBody = "body text";

        Request request = prepareGet()
                .setUri(baseURI)
                .build();

        String body = executeRequest(request, new ResponseToStringHandler());
        assertEquals(body, "body text");
    }

    @Test
    public void testGetMethod()
            throws Exception
    {
        URI uri = baseURI.resolve("/road/to/nowhere?query");
        Request request = prepareGet()
                .setUri(uri)
                .addHeader("foo", "bar")
                .addHeader("dupe", "first")
                .addHeader("dupe", "second")
                .build();

        int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
        assertEquals(statusCode, 200);
        assertEquals(servlet.requestMethod, "GET");
        if (servlet.requestUri.toString().endsWith("=")) {
            // todo jetty client rewrites the uri string for some reason
            assertEquals(servlet.requestUri, new URI(uri.toString() + "="));
        }
        else {
            assertEquals(servlet.requestUri, uri);
        }
        assertEquals(servlet.requestHeaders.get("foo"), ImmutableList.of("bar"));
        assertEquals(servlet.requestHeaders.get("dupe"), ImmutableList.of("first", "second"));
        assertEquals(servlet.requestHeaders.get("x-custom-filter"), ImmutableList.of("customvalue"));
    }

    @Test
    public void testKeepAlive()
            throws Exception
    {
        URI uri = URI.create(baseURI.toASCIIString() + "/?remotePort=");
        Request request = prepareGet()
                .setUri(uri)
                .build();

        ListMultimap<String, String> headers1 = executeRequest(request, new ResponseHeadersHandler());
        Thread.sleep(1000);
        ListMultimap<String, String> headers2 = executeRequest(request, new ResponseHeadersHandler());
        Thread.sleep(1000);
        ListMultimap<String, String> headers3 = executeRequest(request, new ResponseHeadersHandler());

        assertEquals(headers1.get("remotePort").size(), 1);
        assertEquals(headers2.get("remotePort").size(), 1);
        assertEquals(headers3.get("remotePort").size(), 1);

        int port1 = Integer.parseInt(headers1.get("remotePort").get(0));
        int port2 = Integer.parseInt(headers2.get("remotePort").get(0));
        int port3 = Integer.parseInt(headers3.get("remotePort").get(0));

        assertEquals(port2, port1);
        assertEquals(port3, port1);
        Assertions.assertBetweenInclusive(port1, 1024, 65535);
    }

    @Test
    public void testPostMethod()
            throws Exception
    {
        URI uri = baseURI.resolve("/road/to/nowhere");
        Request request = preparePost()
                .setUri(uri)
                .addHeader("foo", "bar")
                .addHeader("dupe", "first")
                .addHeader("dupe", "second")
                .build();

        int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
        assertEquals(statusCode, 200);
        assertEquals(servlet.requestMethod, "POST");
        assertEquals(servlet.requestUri, uri);
        assertEquals(servlet.requestHeaders.get("foo"), ImmutableList.of("bar"));
        assertEquals(servlet.requestHeaders.get("dupe"), ImmutableList.of("first", "second"));
        assertEquals(servlet.requestHeaders.get("x-custom-filter"), ImmutableList.of("customvalue"));
    }

    @Test
    public void testPutMethod()
            throws Exception
    {
        servlet.responseBody = "response";
        URI uri = baseURI.resolve("/road/to/nowhere");
        Request request = preparePut()
                .setUri(uri)
                .addHeader("foo", "bar")
                .addHeader("dupe", "first")
                .addHeader("dupe", "second")
                .build();

        int statusCode = executeRequest(request, StringResponseHandler.createStringResponseHandler()).getStatusCode();
        assertEquals(statusCode, 200);
        assertEquals(servlet.requestMethod, "PUT");
        assertEquals(servlet.requestUri, uri);
        assertEquals(servlet.requestHeaders.get("foo"), ImmutableList.of("bar"));
        assertEquals(servlet.requestHeaders.get("dupe"), ImmutableList.of("first", "second"));
        assertEquals(servlet.requestHeaders.get("x-custom-filter"), ImmutableList.of("customvalue"));
        assertEquals(stats.getWrittenBytes().getAllTime().getTotal(), 0.0);
    }

    @Test
    public void testPutMethodWithStaticBodyGenerator()
            throws Exception
    {
        URI uri = baseURI.resolve("/road/to/nowhere");
        byte[] body = {1, 2, 5};
        Request request = preparePut()
                .setUri(uri)
                .addHeader("foo", "bar")
                .addHeader("dupe", "first")
                .addHeader("dupe", "second")
                .setBodyGenerator(StaticBodyGenerator.createStaticBodyGenerator(body))
                .build();

        int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
        assertEquals(statusCode, 200);
        assertEquals(servlet.requestMethod, "PUT");
        assertEquals(servlet.requestUri, uri);
        assertEquals(servlet.requestHeaders.get("foo"), ImmutableList.of("bar"));
        assertEquals(servlet.requestHeaders.get("dupe"), ImmutableList.of("first", "second"));
        assertEquals(servlet.requestHeaders.get("x-custom-filter"), ImmutableList.of("customvalue"));
        assertEquals(servlet.requestBytes, body);
        assertEquals(stats.getWrittenBytes().getAllTime().getTotal(), 3.0);
    }

    @Test
    public void testPutMethodWithDynamicBodyGenerator()
            throws Exception
    {
        URI uri = baseURI.resolve("/road/to/nowhere");
        Request request = preparePut()
                .setUri(uri)
                .addHeader("foo", "bar")
                .addHeader("dupe", "first")
                .addHeader("dupe", "second")
                .setBodyGenerator(new BodyGenerator()
                {
                    @Override
                    public void write(OutputStream out)
                            throws Exception
                    {
                        out.write(1);
                        byte[] bytes = {2, 5};
                        out.write(bytes);
                        bytes[0] = 9;
                    }
                })
                .build();

        int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
        assertEquals(statusCode, 200);
        assertEquals(servlet.requestMethod, "PUT");
        assertEquals(servlet.requestUri, uri);
        assertEquals(servlet.requestHeaders.get("foo"), ImmutableList.of("bar"));
        assertEquals(servlet.requestHeaders.get("dupe"), ImmutableList.of("first", "second"));
        assertEquals(servlet.requestHeaders.get("x-custom-filter"), ImmutableList.of("customvalue"));
        assertEquals(servlet.requestBytes, new byte[]{1, 2, 5});
        assertEquals(stats.getWrittenBytes().getAllTime().getTotal(), 3.0);
    }

    @Test
    public void testPutMethodWithInputStreamBodySource()
            throws Exception
    {
        URI uri = baseURI.resolve("/road/to/nowhere");
        Request request = preparePut()
                .setUri(uri)
                .addHeader("foo", "bar")
                .addHeader("dupe", "first")
                .addHeader("dupe", "second")
                .setBodySource(new InputStreamBodySource(new InputStream()
                {
                    AtomicInteger invocation = new AtomicInteger(0);

                    @Override
                    public int read()
                            throws IOException
                    {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public int read(byte[] b)
                            throws IOException
                    {
                        switch (invocation.getAndIncrement()) {
                            case 0:
                                b[0] = 1;
                                return 1;

                            case 1:
                                b[0] = 2;
                                b[1] = 5;
                                return 2;

                            case 2:
                                return -1;

                            default:
                                fail("unexpected invocation of write()");
                                return -1;
                        }
                    }
                }))
                .build();

        int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
        assertEquals(statusCode, 200);
        assertEquals(servlet.requestMethod, "PUT");
        assertEquals(servlet.requestUri, uri);
        assertEquals(servlet.requestHeaders.get("foo"), ImmutableList.of("bar"));
        assertEquals(servlet.requestHeaders.get("dupe"), ImmutableList.of("first", "second"));
        assertEquals(servlet.requestHeaders.get("x-custom-filter"), ImmutableList.of("customvalue"));
        assertEquals(servlet.requestBytes, new byte[]{1, 2, 5});
        assertEquals(stats.getWrittenBytes().getAllTime().getTotal(), 3.0);
    }

    @Test
    public void testPutMethodWithDynamicBodySource()
            throws Exception
    {
        URI uri = baseURI.resolve("/road/to/nowhere");
        Request request = preparePut()
                .setUri(uri)
                .addHeader("foo", "bar")
                .addHeader("dupe", "first")
                .addHeader("dupe", "second")
                .setBodySource(new DynamicBodySource()
                {
                    @Override
                    public Writer start(final OutputStream out)
                    {
                        return new Writer()
                        {
                            AtomicInteger invocation = new AtomicInteger(0);

                            @Override
                            public void write()
                                    throws Exception
                            {
                                switch (invocation.getAndIncrement()) {
                                    case 0:
                                        out.write(1);
                                        break;

                                    case 1:
                                        byte[] bytes = {2, 5};
                                        out.write(bytes);
                                        bytes[0] = 9;
                                        out.close();
                                        break;

                                    default:
                                        fail("unexpected invocation of write()");
                                }
                            }
                        };
                    }
                })
                .build();

        int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
        assertEquals(statusCode, 200);
        assertEquals(servlet.requestMethod, "PUT");
        assertEquals(servlet.requestUri, uri);
        assertEquals(servlet.requestHeaders.get("foo"), ImmutableList.of("bar"));
        assertEquals(servlet.requestHeaders.get("dupe"), ImmutableList.of("first", "second"));
        assertEquals(servlet.requestHeaders.get("x-custom-filter"), ImmutableList.of("customvalue"));
        assertEquals(servlet.requestBytes, new byte[]{1, 2, 5});
        assertEquals(stats.getWrittenBytes().getAllTime().getTotal(), 3.0);
    }

    @Test
    public void testPutMethodWithDynamicBodySourceEdgeCases()
            throws Exception
    {
        URI uri = baseURI.resolve("/road/to/nowhere");
        final AtomicBoolean closed = new AtomicBoolean(false);
        Request request = preparePut()
                .setUri(uri)
                .addHeader("foo", "bar")
                .addHeader("dupe", "first")
                .addHeader("dupe", "second")
                .setBodySource(new DynamicBodySource()
                {
                    @Override
                    public Writer start(final OutputStream out)
                            throws IOException
                    {
                        out.write(1);
                        return new EdgeCaseTestWriter(out, closed);
                    }
                })
                .build();

        int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
        assertEquals(statusCode, 200);
        assertEquals(servlet.requestMethod, "PUT");
        assertEquals(servlet.requestUri, uri);
        assertEquals(servlet.requestHeaders.get("foo"), ImmutableList.of("bar"));
        assertEquals(servlet.requestHeaders.get("dupe"), ImmutableList.of("first", "second"));
        assertEquals(servlet.requestHeaders.get("x-custom-filter"), ImmutableList.of("customvalue"));
        assertEquals(servlet.requestBytes, new byte[]{1, 2, 5});
        assertTrue(closed.get(), "Writer was closed");
        assertEquals(stats.getWrittenBytes().getAllTime().getTotal(), 3.0);
    }

    private static class EdgeCaseTestWriter
            implements Writer, AutoCloseable
    {
        AtomicInteger invocation = new AtomicInteger(0);
        private final OutputStream out;
        private final AtomicBoolean closed;

        EdgeCaseTestWriter(OutputStream out, AtomicBoolean closed)
        {
            this.out = out;
            this.closed = closed;
        }

        @Override
        public void write()
                throws Exception
        {
            switch (invocation.getAndIncrement()) {
                case 0:
                    break;

                case 1:
                    byte[] bytes = {2, 5};
                    out.write(bytes);
                    bytes[0] = 9;
                    break;

                case 2:
                    out.close();
                    break;

                default:
                    fail("unexpected invocation of write()");
            }
        }

        @Override
        public void close()
        {
            closed.set(true);
        }
    }

    @Test
    public void testBodyGeneratorSeesTraceToken()
            throws Exception
    {
        final String token = createAndRegisterNewRequestToken();
        URI uri = baseURI.resolve("/road/to/nowhere");
        Request request = preparePut()
                .setUri(uri)
                .addHeader("foo", "bar")
                .addHeader("dupe", "first")
                .addHeader("dupe", "second")
                .setBodyGenerator(new BodyGenerator()
                {
                    @Override
                    public void write(OutputStream out)
                            throws Exception
                    {
                        assertEquals(getCurrentRequestToken(), token);
                    }
                })
                .build();

        int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
        assertEquals(statusCode, 200);
        assertEquals(servlet.requestMethod, "PUT");
        assertEquals(servlet.requestUri, uri);
        assertEquals(servlet.requestHeaders.get("foo"), ImmutableList.of("bar"));
        assertEquals(servlet.requestHeaders.get("dupe"), ImmutableList.of("first", "second"));
        assertEquals(servlet.requestHeaders.get("x-custom-filter"), ImmutableList.of("customvalue"));
        assertEquals(servlet.requestBytes, new byte[] {});
    }

    @Test
    public void testDynamicBodySourceSeesTraceToken()
            throws Exception
    {
        final String token = createAndRegisterNewRequestToken();
        final AtomicReference<String> writeToken = new AtomicReference<>();
        final AtomicReference<String> closeToken = new AtomicReference<>();
        URI uri = baseURI.resolve("/road/to/nowhere");
        Request request = preparePut()
                .setUri(uri)
                .addHeader("foo", "bar")
                .addHeader("dupe", "first")
                .addHeader("dupe", "second")
                .setBodySource(new DynamicBodySource()
                {
                    @Override
                    public Writer start(final OutputStream out)
                    {
                        assertEquals(getCurrentRequestToken(), token);
                        return new TraceTokenTestWriter(out, writeToken, closeToken);
                    }
                })
                .build();

        int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
        assertEquals(statusCode, 200);
        assertEquals(servlet.requestMethod, "PUT");
        assertEquals(servlet.requestUri, uri);
        assertEquals(servlet.requestHeaders.get("foo"), ImmutableList.of("bar"));
        assertEquals(servlet.requestHeaders.get("dupe"), ImmutableList.of("first", "second"));
        assertEquals(servlet.requestHeaders.get("x-custom-filter"), ImmutableList.of("customvalue"));
        assertEquals(servlet.requestBytes, new byte[] {});
        assertEquals(writeToken.get(), token);
        assertEquals(closeToken.get(), token);
    }

    private static class TraceTokenTestWriter
            implements Writer, AutoCloseable
    {
        private final OutputStream out;
        private final AtomicReference<String> writeToken;
        private final AtomicReference<String> closeToken;

        TraceTokenTestWriter(OutputStream out, AtomicReference<String> writeToken, AtomicReference<String> closeToken)
        {
            this.out = out;
            this.writeToken = writeToken;
            this.closeToken = closeToken;
        }

        @Override
        public void write()
                throws Exception
        {
            writeToken.set(getCurrentRequestToken());
            out.close();
        }

        @Override
        public void close()
        {
            closeToken.set(getCurrentRequestToken());
        }
    }

    @Test
    public void testResponseHandlerExceptionSeesTraceToken()
            throws Exception
    {
        final String token = createAndRegisterNewRequestToken();
        int port = findUnusedPort();

        HttpClientConfig config = new HttpClientConfig();
        config.setConnectTimeout(new Duration(5, MILLISECONDS));

        Request request = prepareGet()
                .setUri(new URI(scheme, null, host, port, "/", null, null))
                .build();

        executeRequest(request, new ResponseHandler<Void, RuntimeException>()
        {
            @Override
            public Void handleException(Request request, Exception exception)
                    throws RuntimeException
            {
                assertEquals(getCurrentRequestToken(), token);
                return null;
            }

            @Override
            public Void handle(Request request, Response response)
                    throws RuntimeException
            {
                fail("unexpected response");
                return null;
            }
        });
    }

    @Test
    public void testResponseHandlerResponseSeesTraceToken()
            throws Exception
    {
        final String token = createAndRegisterNewRequestToken();
        Request request = prepareGet()
                .setUri(baseURI)
                .build();

        executeRequest(request, new ResponseHandler<Void, RuntimeException>()
        {
            @Override
            public Void handleException(Request request, Exception exception)
                    throws RuntimeException
            {
                fail("unexpected request exception", exception);
                return null;
            }

            @Override
            public Void handle(Request request, Response response)
                    throws RuntimeException
            {
                assertEquals(getCurrentRequestToken(), token);
                return null;
            }
        });
    }

    @Test
    public void testNoFollowRedirect()
            throws Exception
    {
        servlet.responseStatusCode = 302;
        servlet.responseBody = "body text";
        servlet.responseHeaders.put("Location", "http://127.0.0.1:1");

        Request request = prepareGet()
                .setUri(baseURI)
                .build();

        int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
        assertEquals(statusCode, 302);
    }

    @Test
    public void testFollowRedirect()
            throws Exception
    {
        EchoServlet destinationServlet = new EchoServlet();

        int port;
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(0));
            port = socket.getLocalPort();
        }

        Server server = new Server();

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.setSendServerVersion(false);
        httpConfiguration.setSendXPoweredBy(false);

        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfiguration));

        connector.setIdleTimeout(30000);
        connector.setName("http");
        connector.setPort(port);

        server.addConnector(connector);

        ServletHolder servletHolder = new ServletHolder(destinationServlet);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.addServlet(servletHolder, "/redirect");
        HandlerCollection handlers = new HandlerCollection();
        handlers.addHandler(context);
        server.setHandler(handlers);

        try {
            server.start();

            servlet.responseStatusCode = 302;
            servlet.responseBody = "body text";
            servlet.responseHeaders.put("Location", format("http://127.0.0.1:%d/redirect", port));

            Request request = prepareGet()
                    .setUri(baseURI)
                    .setFollowRedirects(true)
                    .build();

            int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
            assertEquals(statusCode, 200);
        }
        finally {
            server.stop();
        }
    }

    @Test(expectedExceptions = {SocketTimeoutException.class, TimeoutException.class, ClosedChannelException.class})
    public void testReadTimeout()
            throws Exception
    {
        HttpClientConfig config = new HttpClientConfig()
                .setReadTimeout(new Duration(500, MILLISECONDS));

        URI uri = URI.create(baseURI.toASCIIString() + "/?sleep=1000");
        Request request = prepareGet()
                .setUri(uri)
                .build();

        executeRequest(config, request, new ResponseToStringHandler());
    }

    @Test
    public void testResponseBody()
            throws Exception
    {
        servlet.responseBody = "body text";

        Request request = prepareGet()
                .setUri(baseURI)
                .build();

        String body = executeRequest(request, new ResponseToStringHandler());
        assertEquals(body, "body text");
    }

    @Test
    public void testResponseBodyEmpty()
            throws Exception
    {
        Request request = prepareGet()
                .setUri(baseURI)
                .build();

        String body = executeRequest(request, new ResponseToStringHandler());
        assertEquals(body, "");
    }

    @Test
    public void testResponseHeader()
            throws Exception
    {
        servlet.responseHeaders.put("foo", "bar");
        servlet.responseHeaders.put("dupe", "first");
        servlet.responseHeaders.put("dupe", "second");

        assertEquals(servlet.responseHeaders.get("foo"), ImmutableList.of("bar"));
        assertEquals(servlet.responseHeaders.get("dupe"), ImmutableList.of("first", "second"));

        Request request = prepareGet()
                .setUri(baseURI)
                .build();

        ListMultimap<String, String> headers = executeRequest(request, new ResponseHeadersHandler());

        assertEquals(headers.get("foo"), ImmutableList.of("bar"));
        assertEquals(headers.get("dupe"), ImmutableList.of("first", "second"));
    }

    @Test
    public void testResponseStatusCode()
            throws Exception
    {
        servlet.responseStatusCode = 543;
        Request request = prepareGet()
                .setUri(baseURI)
                .build();

        int statusCode = executeRequest(request, new ResponseStatusCodeHandler());
        assertEquals(statusCode, 543);
    }

    @Test
    public void testResponseStatusMessage()
            throws Exception
    {
        servlet.responseStatusMessage = "message";

        Request request = prepareGet()
                .setUri(baseURI)
                .build();

        String statusMessage = executeRequest(request, new ResponseHandler<String, Exception>()
        {
            @Override
            public String handleException(Request request, Exception exception)
                    throws Exception
            {
                throw exception;
            }

            @Override
            public String handle(Request request, Response response)
                    throws Exception
            {
                return response.getStatusMessage();
            }
        });

        assertEquals(statusMessage, "message");
    }

    @Test(expectedExceptions = UnexpectedResponseException.class)
    public void testThrowsUnexpectedResponseException()
            throws Exception
    {
        servlet.responseStatusCode = 543;
        Request request = prepareGet()
                .setUri(baseURI)
                .build();

        executeRequest(request, new UnexpectedResponseStatusCodeHandler(200));
    }

    @Test
    public void testCompressionIsDisabled()
            throws Exception
    {
        Request request = prepareGet()
                .setUri(baseURI)
                .build();

        String body = executeRequest(request, new ResponseToStringHandler());
        assertEquals(body, "");
        Assert.assertFalse(servlet.requestHeaders.containsKey("Accept-Encoding"));
    }

    private ExecutorService executor;

    @BeforeClass
    public void setup()
            throws Exception
    {
        executor = Executors.newCachedThreadPool(threadsNamed("test-%s"));
    }

    @AfterClass
    public void tearDown()
            throws Exception
    {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test(expectedExceptions = {IOException.class, TimeoutException.class})
    public void testConnectNoRead()
            throws Exception
    {
        try (FakeServer fakeServer = new FakeServer(scheme, host, 0, null, false)) {
            HttpClientConfig config = new HttpClientConfig();
            config.setConnectTimeout(new Duration(5, SECONDS));
            config.setReadTimeout(new Duration(10, MILLISECONDS));

            executeRequest(fakeServer, config);
        }
    }

    @Test(expectedExceptions = IOException.class)
    public void testConnectNoReadClose()
            throws Exception
    {
        try (FakeServer fakeServer = new FakeServer(scheme, host, 0, null, true)) {

            HttpClientConfig config = new HttpClientConfig();
            config.setConnectTimeout(new Duration(5, SECONDS));
            config.setReadTimeout(new Duration(5, SECONDS));

            executeRequest(fakeServer, config);
        }
    }


    @Test(expectedExceptions = {IOException.class, TimeoutException.class})
    public void testConnectReadIncomplete()
            throws Exception
    {
        try (FakeServer fakeServer = new FakeServer(scheme, host, 10, null, false)) {
            HttpClientConfig config = new HttpClientConfig();
            config.setConnectTimeout(new Duration(5, SECONDS));
            config.setReadTimeout(new Duration(10, MILLISECONDS));

            executeRequest(fakeServer, config);
        }
    }


    @Test(expectedExceptions = {IOException.class, TimeoutException.class})
    public void testConnectReadIncompleteClose()
            throws Exception
    {
        try (FakeServer fakeServer = new FakeServer(scheme, host, 10, null, true)) {
            HttpClientConfig config = new HttpClientConfig();
            config.setConnectTimeout(new Duration(500, MILLISECONDS));
            config.setReadTimeout(new Duration(500, MILLISECONDS));

            executeRequest(fakeServer, config);
        }
    }

    @Test(expectedExceptions = IOException.class)
    public void testConnectReadRequestClose()
            throws Exception
    {
        try (FakeServer fakeServer = new FakeServer(scheme, host, Long.MAX_VALUE, null, true)) {
            HttpClientConfig config = new HttpClientConfig();
            config.setConnectTimeout(new Duration(5, SECONDS));
            config.setReadTimeout(new Duration(5, SECONDS));

            executeRequest(fakeServer, config);
        }
    }

    @Test(expectedExceptions = Exception.class)
    public void testConnectReadRequestWriteJunkHangup()
            throws Exception
    {
        try (FakeServer fakeServer = new FakeServer(scheme, host, 10, "THIS\nIS\nJUNK\n\n".getBytes(), false)) {
            HttpClientConfig config = new HttpClientConfig();
            config.setConnectTimeout(new Duration(5, SECONDS));
            config.setReadTimeout(new Duration(5, SECONDS));

            executeRequest(fakeServer, config);
        }
    }

    @Test(expectedExceptions = IOException.class)
    public void testBodyGeneratorConnectWriteRequestClose()
            throws Exception
    {
        try (FakeServer fakeServer = new FakeServer(scheme, host, 1024, null, true)) {
            HttpClientConfig config = new HttpClientConfig();
            config.setConnectTimeout(new Duration(5, SECONDS));
            config.setReadTimeout(new Duration(5, SECONDS));

            // kick the fake server
            executor.execute(fakeServer);

            // timing based check to assure we don't hang
            long start = System.nanoTime();
            final AtomicBoolean bodyGeneratorCalled = new AtomicBoolean(false);
            try {
                Request request = preparePut()
                        .setUri(fakeServer.getUri())
                        .setBodyGenerator(new BodyGenerator()
                        {
                            @Override
                            public void write(OutputStream out)
                                    throws Exception
                            {
                                bodyGeneratorCalled.set(true);
                                try {
                                    for (int i = 0; i < 100; ++i) {
                                        out.write(new byte[1024]);
                                    }
                                }
                                catch (IOException e) {
                                    throw e;
                                }
                            }
                        })
                        .build();
                executeRequest(config, request, new ResponseToStringHandler());
            }
            finally {
                assertTrue(bodyGeneratorCalled.get(), "BodyGenerator called");
                assertLessThan(nanosSince(start), new Duration(1, SECONDS), "Expected request to finish quickly");
            }
        }
    }

    @Test(expectedExceptions = IOException.class)
    public void testDynamicBodySourceConnectWriteRequestClose()
            throws Exception
    {
        try (FakeServer fakeServer = new FakeServer(scheme, host, 1024, null, true)) {
            HttpClientConfig config = new HttpClientConfig();
            config.setConnectTimeout(new Duration(5, SECONDS));
            config.setReadTimeout(new Duration(5, SECONDS));

            // kick the fake server
            executor.execute(fakeServer);

            // timing based check to assure we don't hang
            long start = System.nanoTime();
            final AtomicInteger invocation = new AtomicInteger(0);
            try {
                Request request = preparePut()
                        .setUri(fakeServer.getUri())
                        .setBodySource(new DynamicBodySource()
                        {
                            @Override
                            public Writer start(final OutputStream out)
                            {
                                return new Writer()
                                {
                                    @Override
                                    public void write()
                                            throws Exception
                                    {
                                        if (invocation.getAndIncrement() < 100) {
                                            out.write(new byte[1024]);
                                        }
                                        else {
                                            out.close();
                                        }
                                    }
                                };
                            }
                        })
                        .build();
                executeRequest(config, request, new ResponseToStringHandler());
            }
            finally {
                assertGreaterThan(invocation.get(), 0);
                assertLessThan(nanosSince(start), new Duration(1, SECONDS), "Expected request to finish quickly");
            }
        }
    }

    @Test(expectedExceptions = CustomError.class)
    public void testHandlesUndeclaredThrowable()
            throws Exception
    {
        Request request = prepareGet()
                .setUri(baseURI)
                .build();

        executeRequest(request, new ThrowErrorResponseHandler());
    }

    private void executeRequest(FakeServer fakeServer, HttpClientConfig config)
            throws Exception
    {
        // kick the fake server
        executor.execute(fakeServer);

        // timing based check to assure we don't hang
        long start = System.nanoTime();
        try {
            Request request = prepareGet()
                    .setUri(fakeServer.getUri())
                    .build();
            executeRequest(config, request, new ResponseToStringHandler());
        }
        finally {
            assertLessThan(nanosSince(start), new Duration(1, SECONDS), "Expected request to finish quickly");
        }
    }

    private static class FakeServer
            implements Closeable, Runnable
    {
        private final ServerSocket serverSocket;
        private final long readBytes;
        private final byte[] writeBuffer;
        private final boolean closeConnectionImmediately;
        private final AtomicReference<Socket> connectionSocket = new AtomicReference<>();
        private String scheme;
        private String host;


        private FakeServer(String scheme, String host, long readBytes, byte[] writeBuffer, boolean closeConnectionImmediately)
                throws Exception
        {
            this.scheme = scheme;
            this.host = host;
            this.writeBuffer = writeBuffer;
            this.readBytes = readBytes;
            this.serverSocket = new ServerSocket(0);
            this.closeConnectionImmediately = closeConnectionImmediately;
        }

        public URI getUri()
        {
            try {
                return new URI(scheme, null, host, serverSocket.getLocalPort(), "/", null, null);
            }
            catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void run()
        {
            try {
                Socket connectionSocket = serverSocket.accept();
                this.connectionSocket.set(connectionSocket);
                if (readBytes > 0) {
                    connectionSocket.setSoTimeout(500);
                    long bytesRead = 0;
                    try {
                        InputStream inputStream = connectionSocket.getInputStream();
                        while (bytesRead < readBytes) {
                            inputStream.read();
                            bytesRead++;
                        }
                    }
                    catch (SocketTimeoutException ignored) {
                    }
                }
                if (writeBuffer != null) {
                    connectionSocket.getOutputStream().write(writeBuffer);
                }
                // todo sleep here maybe
            }
            catch (IOException e) {
                throw propagate(e);
            }
            finally {
                if (closeConnectionImmediately) {
                    closeQuietly(connectionSocket.get());
                }
            }
        }

        @Override
        public void close()
                throws IOException
        {
            closeQuietly(connectionSocket.get());
            serverSocket.close();
        }
    }

    public static class ResponseToStringHandler
            implements ResponseHandler<String, Exception>
    {
        @Override
        public String handleException(Request request, Exception exception)
                throws Exception
        {
            throw exception;
        }

        @Override
        public String handle(Request request, Response response)
                throws Exception
        {
            return new String(ByteStreams.toByteArray(response.getInputStream()), Charsets.UTF_8);
        }
    }

    static class UnexpectedResponseStatusCodeHandler
            implements ResponseHandler<Integer, RuntimeException>
    {
        private int expectedStatusCode;

        UnexpectedResponseStatusCodeHandler(int expectedStatusCode)
        {
            this.expectedStatusCode = expectedStatusCode;
        }

        @Override
        public Integer handleException(Request request, Exception exception)
        {
            throw ResponseHandlerUtils.propagate(request, exception);
        }

        @Override
        public Integer handle(Request request, Response response)
                throws RuntimeException
        {
            if (response.getStatusCode() != expectedStatusCode) {
                throw new UnexpectedResponseException(request, response);
            }
            return response.getStatusCode();
        }
    }

    public static class ResponseHeadersHandler
            implements ResponseHandler<ListMultimap<String, String>, Exception>
    {
        @Override
        public ListMultimap<String, String> handleException(Request request, Exception exception)
                throws Exception
        {
            throw exception;
        }

        @Override
        public ListMultimap<String, String> handle(Request request, Response response)
                throws Exception
        {
            return response.getHeaders();
        }
    }

    public static class CaptureExceptionResponseHandler
            implements ResponseHandler<String, CapturedException>
    {
        @Override
        public String handleException(Request request, Exception exception)
                throws CapturedException
        {
            throw new CapturedException(exception);
        }

        @Override
        public String handle(Request request, Response response)
                throws CapturedException
        {
            throw new UnsupportedOperationException();
        }

    }

    public static class ThrowErrorResponseHandler
            implements ResponseHandler<String, Exception>
    {
        @Override
        public String handleException(Request request, Exception exception)
        {
            throw new UnsupportedOperationException("not yet implemented", exception);
        }

        @Override
        public String handle(Request request, Response response)
        {
            throw new CustomError();
        }
    }

    private static class CustomError
            extends Error {
    }

    protected static class CapturedException
            extends Exception
    {
        public CapturedException(Exception exception)
        {
            super(exception);
        }
    }

    private static class DefaultOnExceptionResponseHandler
            implements ResponseHandler<Object, RuntimeException>
    {

        private final Object defaultObject;

        public DefaultOnExceptionResponseHandler(Object defaultObject)
        {
            this.defaultObject = defaultObject;
        }

        @Override
        public Object handleException(Request request, Exception exception)
                throws RuntimeException
        {
            return defaultObject;
        }

        @Override
        public Object handle(Request request, Response response)
                throws RuntimeException
        {
            throw new UnsupportedOperationException();
        }
    }

    private static int findUnusedPort()
            throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @SuppressWarnings("SocketOpenedButNotSafelyClosed")
    private static class BackloggedServer
            implements Closeable
    {
        private final List<Socket> clientSockets = new ArrayList<>();
        private final ServerSocket serverSocket;
        private final SocketAddress localSocketAddress;

        private BackloggedServer()
                throws IOException
        {
            this.serverSocket = new ServerSocket(0, 1);
            localSocketAddress = serverSocket.getLocalSocketAddress();

            // some systems like Linux have a large minimum backlog
            int i = 0;
            while (i <= 40) {
                if (!connect()) {
                    return;
                }
                i++;
            }
            fail(format("socket backlog is too large (%s connections accepted)", i));
        }

        @Override
        public void close()
        {
            for (Socket socket : clientSockets) {
                closeQuietly(socket);
            }
            closeQuietly(serverSocket);
        }

        private int getPort()
        {
            return serverSocket.getLocalPort();
        }

        private boolean connect()
        {
            Socket socket = new Socket();
            clientSockets.add(socket);

            try {
                socket.connect(localSocketAddress, 5);
                return true;
            }
            catch (IOException e) {
                if (isConnectTimeout(e)) {
                    return false;
                }
                throw propagate(e);
            }
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void assertUnknownHost(String host)
    {
        try {
            InetAddress.getByName(host);
            fail("Expected UnknownHostException for host " + host);
        }
        catch (UnknownHostException e) {
            // expected
        }
    }

    private static boolean isConnectTimeout(Throwable t)
    {
        // Linux refuses connections immediately rather than queuing them
        return (t instanceof SocketTimeoutException) || (t instanceof SocketException);
    }
}
