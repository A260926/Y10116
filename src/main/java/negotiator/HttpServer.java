package negotiator;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDK 内置 HTTP 服务器：JSON API + 单页 Web UI。 */
public final class HttpServer {

    private final com.sun.net.httpserver.HttpServer server;
    private final Service service;

    public HttpServer(int port, Service service) {
        this.service = service;
        try {
            this.server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
            this.server.createContext("/", new RootHandler());
            this.server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); }
    public int port() { return server.getAddress().getPort(); }

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                route(ex);
            } catch (Service.ApiException e) {
                writeJson(ex, e.status, Map.of("error", Map.of(
                        "code", e.code, "message", e.getMessage())));
            } catch (Json.BadRequest e) {
                writeJson(ex, 400, Map.of("error", Map.of("code", "BAD_REQUEST", "message", e.getMessage())));
            } catch (Exception e) {
                e.printStackTrace();
                writeJson(ex, 500, Map.of("error", Map.of("code", "INTERNAL", "message", String.valueOf(e.getMessage()))));
            } finally {
                ex.close();
            }
        }
    }

    private void route(HttpExchange ex) throws IOException, SQLException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            byte[] html = Resources.read("/static/index.html");
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, html.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(html); }
            return;
        }
        if ("GET".equals(method) && path.startsWith("/static/")) {
            serveStatic(ex, path);
            return;
        }
        if ("GET".equals(method) && path.equals("/api/health")) {
            writeJson(ex, 200, Map.of("status", "ok", "service", "协议协商演练器"));
            return;
        }

        // scenarios
        if ("GET".equals(method) && path.equals("/api/scenarios")) {
            writeJson(ex, 200, Map.of("scenarios", service.db().listScenarios().stream().map(this::scenarioJson).toList()));
            return;
        }
        if ("POST".equals(method) && path.equals("/api/scenarios")) {
            JsonNode body = readBody(ex);
            Db.ScenarioRow row = service.createScenario(body);
            writeJson(ex, 201, scenarioJson(row));
            return;
        }
        if (method.equals("PUT") && path.matches("/api/scenarios/[^/]+/draft")) {
            String id = path.split("/")[3];
            Db.ScenarioRow row = service.saveDraft(id, readBody(ex));
            writeJson(ex, 200, scenarioJson(row));
            return;
        }
        if (method.equals("POST") && path.matches("/api/scenarios/[^/]+/publish")) {
            String id = path.split("/")[3];
            Db.ScenarioRow row = service.publish(id);
            writeJson(ex, 200, scenarioJson(row));
            return;
        }
        if ("GET".equals(method) && path.matches("/api/scenarios/[^/]+")) {
            String id = path.split("/")[3];
            Db.ScenarioRow row = service.db().findScenario(id);
            if (row == null) throw new Service.ApiException(404, "SCENARIO_NOT_FOUND", "场景不存在: " + id);
            writeJson(ex, 200, scenarioJson(row));
            return;
        }

        // sessions
        if ("POST".equals(method) && path.equals("/api/sessions")) {
            byte[] raw = ex.getRequestBody().readAllBytes();
            JsonNode body = Json.parse(raw);
            SessionView v = service.offer(body, new String(raw, StandardCharsets.UTF_8));
            writeJson(ex, v.idempotentReplay ? 200 : 201, v);
            return;
        }
        if ("GET".equals(method) && path.equals("/api/sessions")) {
            writeJson(ex, 200, Map.of("sessions", service.listViews()));
            return;
        }
        if (method.equals("POST") && path.matches("/api/sessions/[^/]+/confirm")) {
            byte[] raw = ex.getRequestBody().readAllBytes();
            JsonNode body = Json.parse(raw);
            SessionView v = service.confirm(body, new String(raw, StandardCharsets.UTF_8));
            writeJson(ex, 200, v);
            return;
        }
        if (method.equals("POST") && path.matches("/api/sessions/[^/]+/active")) {
            byte[] raw = ex.getRequestBody().readAllBytes();
            JsonNode body = Json.parse(raw);
            SessionView v = service.activate(body, new String(raw, StandardCharsets.UTF_8));
            writeJson(ex, 200, v);
            return;
        }
        if (method.equals("POST") && path.matches("/api/sessions/[^/]+/renegotiate")) {
            byte[] raw = ex.getRequestBody().readAllBytes();
            JsonNode body = Json.parse(raw);
            SessionView v = service.renegotiate(body, new String(raw, StandardCharsets.UTF_8));
            writeJson(ex, 200, v);
            return;
        }
        if (method.equals("GET") && path.matches("/api/sessions/[^/]+/export")) {
            String id = path.split("/")[3];
            writeJson(ex, 200, service.exportTranscript(id));
            return;
        }
        if ("POST".equals(method) && path.equals("/api/transcripts/import")) {
            JsonNode body = readBody(ex);
            Map<String, Object> result = service.importTranscript(body, false);
            writeJson(ex, 201, result);
            return;
        }
        if ("GET".equals(method) && path.matches("/api/sessions/[^/]+")) {
            String id = path.split("/")[3];
            writeJson(ex, 200, service.getView(id));
            return;
        }
        writeJson(ex, 404, Map.of("error", Map.of("code", "NOT_FOUND", "message", "无此路径: " + path)));
    }

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        try {
            byte[] data = Resources.read(path.startsWith("/") ? path : "/" + path);
            String type = path.endsWith(".css") ? "text/css; charset=utf-8"
                    : path.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : "application/octet-stream";
            ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(data); }
        } catch (IllegalArgumentException e) {
            writeJson(ex, 404, Map.of("error", Map.of("code", "NOT_FOUND", "message", path)));
        }
    }

    private Map<String, Object> scenarioJson(Db.ScenarioRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.id);
        m.put("name", row.name);
        m.put("description", row.description);
        m.put("revision", row.revision);
        m.put("draft", Json.MAPPER.valueToTree(Db.read(row.draftJson, Models.Draft.class)));
        m.put("published", row.publishedJson == null ? null : Json.MAPPER.valueToTree(
                Service.parsePublished(row.publishedJson).values().stream().toList()));
        return m;
    }

    private JsonNode readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return Json.parse(is.readAllBytes());
        }
    }

    private void writeJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] data = Json.bytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }
}
