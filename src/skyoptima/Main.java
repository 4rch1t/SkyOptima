package skyoptima;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;

public class Main {
    private static final int PORT = 8080;
    private static final String PUBLIC_DIR = "public";

    public static void main(String[] args) throws Exception {
        Simulation simulation = new Simulation();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new StaticFileHandler(PUBLIC_DIR));
        server.createContext("/startSimulation", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { sendText(exchange, 405, "Method Not Allowed"); return; }
            simulation.start();
            sendJson(exchange, 200, "{\"status\":\"started\"}");
        });
        server.createContext("/stopSimulation", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { sendText(exchange, 405, "Method Not Allowed"); return; }
            simulation.stop();
            sendJson(exchange, 200, "{\"status\":\"stopped\"}");
        });
        server.createContext("/reset", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { sendText(exchange, 405, "Method Not Allowed"); return; }
            simulation.reset();
            sendJson(exchange, 200, "{\"status\":\"reset\"}");
        });
        server.createContext("/status", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { sendText(exchange, 405, "Method Not Allowed"); return; }
            sendJson(exchange, 200, simulation.getStatusJson());
        });
        server.createContext("/updateSimulation", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { sendText(exchange, 405, "Method Not Allowed"); return; }
            sendNoStore(exchange);
            sendJson(exchange, 200, simulation.getUpdateJson());
        });
        server.createContext("/getAlerts", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { sendText(exchange, 405, "Method Not Allowed"); return; }
            sendNoStore(exchange);
            sendJson(exchange, 200, simulation.getAlertsJson());
        });
        server.createContext("/configure", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { sendText(exchange, 405, "Method Not Allowed"); return; }
            Map<String, String> q = parseQuery(exchange.getRequestURI());
            Integer n = parseInt(q.get("n"));
            Integer tick = parseInt(q.get("tick"));
            if (n != null) simulation.setAircraftCount(n);
            if (tick != null) simulation.setTickMs(tick);
            sendJson(exchange, 200, simulation.getStatusJson());
        });
        server.createContext("/configureAdvanced", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { sendText(exchange, 405, "Method Not Allowed"); return; }
            Map<String, String> q = parseQuery(exchange.getRequestURI());
            Integer n = parseInt(q.get("n"));
            Integer tick = parseInt(q.get("tick"));
            Integer world = parseInt(q.get("world"));
            Integer sep = parseInt(q.get("sep"));
            if (n != null) simulation.setAircraftCount(n);
            if (tick != null) simulation.setTickMs(tick);
            if (world != null) simulation.setWorldSize(world);
            if (sep != null) simulation.setMinSeparation(sep);
            sendJson(exchange, 200, simulation.getStatusJson());
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("SkyOptima server running on http://localhost:" + PORT);
    }

    static class StaticFileHandler implements HttpHandler {
        private final Path baseDir;

        StaticFileHandler(String publicDir) {
            this.baseDir = Paths.get(publicDir).toAbsolutePath().normalize();
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            if (path.equals("/")) path = "/index.html";

            Path resolved = baseDir.resolve(path.substring(1)).normalize();
            if (!resolved.startsWith(baseDir) || !Files.exists(resolved) || Files.isDirectory(resolved)) {
                sendText(exchange, 404, "Not Found");
                return;
            }
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType(resolved));
            headers.set("Cache-Control", "no-store");
            byte[] bytes = Files.readAllBytes(resolved);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static void sendText(HttpExchange exchange, int code, String text) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, text.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, json.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void sendNoStore(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    private static String contentType(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            String k = urlDecode(kv[0]);
            String v = kv.length > 1 ? urlDecode(kv[1]) : "";
            map.put(k, v);
        }
        return map;
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static Integer parseInt(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s); } catch (Exception e) { return null; }
    }
}


