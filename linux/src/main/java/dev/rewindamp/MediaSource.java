package dev.rewindamp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

record MediaSource(String url, String authorization, Path offline) {
    HttpURLConnection open(String range) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Authorization", authorization);
        if (range != null) connection.setRequestProperty("Range", range);
        return connection;
    }

    /** FFmpeg sees only a temporary loopback URL. The server token stays in the Java process. */
    static final class Relay implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService workers = Executors.newCachedThreadPool();
        private final ConcurrentHashMap<String, Route> routes = new ConcurrentHashMap<>();

        static final class Route {
            final MediaSource source;
            final String url;
            volatile boolean interrupted;

            Route(MediaSource source, String url) {
                this.source = source;
                this.url = url;
            }

            void copy(InputStream input, OutputStream output, long remaining) throws IOException {
                byte[] buffer = new byte[16 * 1024];
                while (true) {
                    int read;
                    try {
                        read = input.read(buffer);
                    } catch (IOException e) {
                        interrupted = true;
                        throw e;
                    }
                    if (read < 0) {
                        if (remaining > 0) interrupted = true;
                        return;
                    }
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        }

        Relay() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            server.setExecutor(workers);
            server.createContext("/", this::serve);
            server.start();
        }

        Route add(MediaSource source) {
            String path = "/" + UUID.randomUUID();
            Route route = new Route(source, "http://127.0.0.1:" + server.getAddress().getPort() + path);
            routes.put(path, route);
            return route;
        }

        void remove(Route route) { routes.remove(URI.create(route.url).getPath()); }

        private void serve(HttpExchange exchange) throws IOException {
            try (exchange) {
                Route route = routes.get(exchange.getRequestURI().getPath());
                if (route == null || !exchange.getRequestMethod().equals("GET")) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                HttpURLConnection upstream = route.source.open(exchange.getRequestHeaders().getFirst("Range"));
                try {
                    int code;
                    try {
                        code = upstream.getResponseCode();
                    } catch (IOException e) {
                        route.interrupted = true;
                        throw e;
                    }
                    if (code != 200 && code != 206) {
                        if (code == 429 || code >= 500) route.interrupted = true;
                        exchange.sendResponseHeaders(code >= 400 && code <= 599 ? code : 502, -1);
                        return;
                    }
                    for (String header : List.of("Content-Type", "Content-Range", "Accept-Ranges")) {
                        String value = upstream.getHeaderField(header);
                        if (value != null) exchange.getResponseHeaders().set(header, value);
                    }
                    long length = upstream.getContentLengthLong();
                    exchange.sendResponseHeaders(code, Math.max(0, length));
                    try (var input = upstream.getInputStream()) { route.copy(input, exchange.getResponseBody(), length); }
                } finally {
                    upstream.disconnect();
                }
            }
        }

        @Override public void close() {
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
