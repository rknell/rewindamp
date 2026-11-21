package dev.rewindamp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
        private final ConcurrentHashMap<String, MediaSource> routes = new ConcurrentHashMap<>();

        Relay() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            server.setExecutor(workers);
            server.createContext("/", this::serve);
            server.start();
        }

        String add(MediaSource source) {
            String path = "/" + UUID.randomUUID();
            routes.put(path, source);
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        void remove(String url) { routes.remove(URI.create(url).getPath()); }

        private void serve(HttpExchange exchange) throws IOException {
            try (exchange) {
                MediaSource source = routes.get(exchange.getRequestURI().getPath());
                if (source == null || !exchange.getRequestMethod().equals("GET")) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                HttpURLConnection upstream = source.open(exchange.getRequestHeaders().getFirst("Range"));
                try {
                    int code = upstream.getResponseCode();
                    if (code != 200 && code != 206) {
                        exchange.sendResponseHeaders(code >= 400 && code <= 599 ? code : 502, -1);
                        return;
                    }
                    for (String header : List.of("Content-Type", "Content-Range", "Accept-Ranges")) {
                        String value = upstream.getHeaderField(header);
                        if (value != null) exchange.getResponseHeaders().set(header, value);
                    }
                    exchange.sendResponseHeaders(code, Math.max(0, upstream.getContentLengthLong()));
                    try (var input = upstream.getInputStream()) { input.transferTo(exchange.getResponseBody()); }
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
