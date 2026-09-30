package proto;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import proto.Models.*;
import proto.Service.ApiError;
import proto.Service.CrashBeforeReturn;
import proto.Storage.ConflictException;

/** JSON API + static web assets, backed by the JDK's built-in HTTP server. */
public final class HttpServer {
    private final int port;
    private final Storage storage;
    private final Service service;
    private com.sun.net.httpserver.HttpServer server;
    private Path assetDir;

    public HttpServer(int port, Storage storage) {
        this.port = port;
        this.storage = storage;
        this.service = new Service(storage);
    }

    public void start() throws IOException {
        assetDir = Assets.extract();
        server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port), 0);
        server.createContext("/api/", this::api);
        server.createContext("/", this::staticFile);
        server.start();
    }

    public int boundPort() { return server.getAddress().getPort(); }
    public void stop() { if (server != null) server.stop(0); }

    // ---- routing ----

    private void api(HttpExchange ex) throws IOException {
        try {
            Json.Value body = readJson(ex);
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            route(ex, method, path, body);
        } catch (ApiError e) {
            writeJson(ex, e.code, Json.Value.obj().put("error", e.getMessage()));
        } catch (ConflictException e) {
            writeJson(ex, 409, Json.Value.obj().put("error", e.getMessage()));
        } catch (CrashBeforeReturn e) {
            // State was durably committed; no response reaches the client (process
            // would have exited). Signal the simulator with a 599 status.
            writeJson(ex, 599, Json.Value.obj()
                    .put("error", "SIMULATED_PROCESS_EXIT")
                    .put("detail", e.getMessage())
                    .put("committed", e.body));
        } catch (Json.JsonException e) {
            writeJson(ex, 400, Json.Value.obj().put("error", "bad JSON: " + e.getMessage()));
        } catch (Exception e) {
            writeJson(ex, 500, Json.Value.obj().put("error", String.valueOf(e.getMessage())));
        }
    }

    private void route(HttpExchange ex, String method, String path, Json.Value body) throws IOException {
        // scenarios
        if (path.equals("/api/scenarios") && method.equals("GET")) {
            Json.Value arr = Json.Value.arr();
            for (Storage.ScenarioRow r : storage.listScenarios()) {
                Json.Value o = Json.Value.obj();
                o.put("id", r.id); o.put("name", r.name); o.put("serverId", r.serverId);
                o.put("revision", r.revision);
                arr.add(o);
            }
            writeJson(ex, 200, arr);
            return;
        }
        if (path.equals("/api/scenarios") && method.equals("POST")) {
            String name = body.reqStr("name");
            String serverId = body.get("serverId") != null ? body.get("serverId").asStr() : "server";
            ScenarioConfig cfg = body.has("config")
                    ? ScenarioConfig.from(body.get("config")) : new ScenarioConfig();
            long id = storage.createScenario(name, serverId, cfg);
            Json.Value out = Json.Value.obj().put("id", id).put("name", name)
                    .put("draftVersion", 1);
            writeJson(ex, 201, out);
            return;
        }

        if (path.startsWith("/api/scenarios/")) {
            String rest = path.substring("/api/scenarios/".length());
            String[] parts = rest.split("/");
            long sid;
            try { sid = Long.parseLong(parts[0]); } catch (NumberFormatException e) {
                throw new ApiError(400, "bad scenario id");
            }
            if (parts.length == 2 && parts[1].equals("draft") && method.equals("GET")) {
                Storage.Draft d = storage.getDraft(sid);
                if (d == null) throw new ApiError(404, "no draft");
                Json.Value o = Json.Value.obj();
                o.put("config", Json.parse(d.configJson));
                o.put("version", d.version);
                writeJson(ex, 200, o);
                return;
            }
            if (parts.length == 2 && parts[1].equals("draft") && method.equals("PUT")) {
                int expected = (int) body.get("baseVersion").asLong();
                ScenarioConfig cfg = ScenarioConfig.from(body.get("config"));
                storage.saveDraft(sid, cfg, expected);
                Storage.Draft d = storage.getDraft(sid);
                Json.Value o = Json.Value.obj().put("version", d.version);
                writeJson(ex, 200, o);
                return;
            }
            if (parts.length == 2 && parts[1].equals("publish") && method.equals("POST")) {
                ScenarioConfig cfg = storage.publish(sid); // validates; rejects cycles
                int rev = storage.latestRevision(sid);
                Json.Value o = Json.Value.obj().put("revision", rev).put("config", cfg.toJson());
                writeJson(ex, 200, o);
                return;
            }
            if (parts.length >= 2 && parts[1].equals("published") && method.equals("GET")) {
                int rev;
                if (parts.length == 3) {
                    try { rev = Integer.parseInt(parts[2]); }
                    catch (NumberFormatException e) { throw new ApiError(400, "bad revision"); }
                } else {
                    rev = storage.latestRevision(sid);
                }
                ScenarioConfig cfg = storage.getPublishedConfig(sid, rev);
                if (cfg == null) throw new ApiError(404, "no published revision " + rev);
                writeJson(ex, 200, Json.Value.obj().put("revision", rev).put("config", cfg.toJson()));
                return;
            }
            if (parts.length == 2 && parts[1].equals("offer") && method.equals("POST")) {
                writeJson(ex, 200, service.offer(sid, body));
                return;
            }
            if (parts.length == 2 && parts[1].equals("transcript") && method.equals("POST")) {
                writeJson(ex, 200, service.importTranscript(sid, body));
                return;
            }
        }

        if (path.startsWith("/api/sessions/")) {
            String rest = path.substring("/api/sessions/".length());
            String[] parts = rest.split("/");
            long id;
            try { id = Long.parseLong(parts[0]); } catch (NumberFormatException e) {
                throw new ApiError(400, "bad session id");
            }
            if (parts.length == 1 && method.equals("GET")) {
                Storage.SessionRow s = storage.getSession(id);
                if (s == null) throw new ApiError(404, "session not found");
                Json.Value v = Json.Value.obj();
                v.put("sessionId", s.id); v.put("scenarioId", s.scenarioId);
                v.put("revision", s.revision); v.put("idemKey", s.idemKey);
                v.put("stage", s.stage); v.put("generation", s.generation);
                v.put("offer", Json.parse(s.offerJson));
                if (s.resultJson != null) v.put("select", Json.parse(s.resultJson));
                if (s.confirmedJson != null) v.put("confirmed", Json.parse(s.confirmedJson));
                Json.Value msgs = Json.Value.arr();
                for (Json.Value m : storage.listMessages(id)) msgs.add(m);
                v.put("messages", msgs);
                writeJson(ex, 200, v);
                return;
            }
            if (parts.length == 2 && parts[1].equals("select") && method.equals("POST")) {
                writeJson(ex, 200, service.select(id, body));
                return;
            }
            if (parts.length == 2 && parts[1].equals("confirm") && method.equals("POST")) {
                writeJson(ex, 200, service.confirm(id, body));
                return;
            }
            if (parts.length == 2 && parts[1].equals("reoffer") && method.equals("POST")) {
                writeJson(ex, 200, service.reoffer(id, body));
                return;
            }
            if (parts.length == 2 && parts[1].equals("transcript") && method.equals("GET")) {
                writeJson(ex, 200, service.exportTranscript(id));
                return;
            }
            if (parts.length == 2 && parts[1].equals("transcript") && method.equals("POST")) {
                long scenarioId = body.get("scenarioId") != null ? body.get("scenarioId").asLong() : id;
                writeJson(ex, 200, service.importTranscript(scenarioId, body));
                return;
            }
        }
        throw new ApiError(404, "no route for " + method + " " + path);
    }

    // ---- static assets ----

    private void staticFile(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            ex.sendResponseHeaders(405, -1); ex.close(); return;
        }
        String p = ex.getRequestURI().getPath();
        if (p.equals("/") || p.isBlank()) p = "/index.html";
        if (p.contains("..")) { ex.sendResponseHeaders(400, -1); ex.close(); return; }
        Path f = assetDir.resolve(p.substring(1));
        if (!Files.exists(f)) {
            f = assetDir.resolve("index.html"); // SPA fallback
        }
        byte[] data = Files.readAllBytes(f);
        String mime = mime(f.toString());
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private static String mime(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private Json.Value readJson(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (raw.isBlank()) return Json.Value.obj();
            return Json.parse(raw);
        }
    }

    private void writeJson(HttpExchange ex, int code, Json.Value v) throws IOException {
        byte[] data = v.dump().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
}
