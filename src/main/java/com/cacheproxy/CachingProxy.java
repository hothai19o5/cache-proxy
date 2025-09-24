package com.cacheproxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ConcurrentHashMap;

public class CachingProxy {
    private static final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>(); // path -> {body, timestamp}
    private static String originUrl;
    private static int port;
    private static final int TTL = 300; // seconds

    public static void main(String[] args) {
//        command line start caching proxy: caching-proxy --port <number> --origin <url>
//        Validate args
        if (args.length != 4 || !args[0].equals("--port") || !args[2].equals("--origin")) {
            System.out.println("Usage: caching-proxy --port <number> --origin <url>");
            return;
        }
        try {
//            parse args
            port = Integer.parseInt(args[1]);
            originUrl = args[3];
//            start server
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new ProxyHandler());
            server.start();
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    static class ProxyHandler implements HttpHandler {
        private final HttpClient client = HttpClient.newHttpClient();
        @Override
        public void handle(HttpExchange exchange) {
            // Handle incoming requests, check cache, forward to origin if necessary
            String path = exchange.getRequestURI().getPath();

            try {
                if (cache.containsKey(path) && !cache.get(path).isExpired()) {
                    CacheEntry entry = cache.get(path);
                    sendResponse(exchange, 200, entry.body, "HIT");
                } else {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(originUrl + path))
                            .build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    cache.put(path, new CacheEntry(response.body(), System.currentTimeMillis()));
                    sendResponse(exchange, response.statusCode(), response.body(), "MISS");
                }
            } catch (Exception e) {
                System.out.println("Error handling request: " + e.getMessage());
                sendResponse(exchange, 500, "Internal Server Error", "");
            }
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String body, String cacheStatus) {
        try {
            exchange.getResponseHeaders().add("X-Cache", cacheStatus);
            exchange.sendResponseHeaders(statusCode, body.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        } catch (Exception e) {
            System.out.println("Error sending response: " + e.getMessage());
        }
    }

    static class CacheEntry {
        String body;
        long timestamp;

        CacheEntry(String body, long timestamp) {
            this.body = body;
            this.timestamp = timestamp;
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - timestamp) / 1000 > TTL;
        }
    }
}
