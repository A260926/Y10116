package negotiator;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 HTTP 栈端到端：页面标题、JSON API、注入与错误码。 */
class HttpApiTest {

    static HttpServer server;
    static Db db;
    static int port;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path tmp;

    @BeforeAll
    static void up() throws Exception {
        Path dbPath = tmp.resolve("http.db");
        db = new Db(dbPath.toString());
        Service svc = new Service(db, sid -> { throw new Service.CrashSignal(sid); });
        if (db.countScenarios() == 0) Seed.install(svc);
        server = new HttpServer(0, svc);
        server.start();
        port = server.port();
    }

    @AfterAll
    static void down() {
        server.stop();
        db.close();
    }

    static HttpResponse<String> post(String path, Object body) throws Exception {
        return send(path, body, "POST");
    }

    static HttpResponse<String> put(String path, Object body) throws Exception {
        return send(path, body, "PUT");
    }

    static HttpResponse<String> send(String path, Object body, String httpMethod) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(httpMethod, HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode j(String body) { return Json.read(body); }

    @Test
    void indexPageContainsTitle() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("协议协商演练器"));
    }

    @Test
    void fullLifecycleViaHttpWithInjections() throws Exception {
        // 预置会话列表中已有 v3->v2 active 会话
        HttpResponse<String> list = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/sessions")).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode sessions = j(list.body()).get("sessions");
        assertTrue(sessions.size() >= 1);
        JsonNode preset = sessions.get(0);
        assertEquals("v2", preset.path("selection").path("version").asText());

        // 新会话
        var offerBody = j("""
                {"idemKey":"http-k1","scenarioId":"tls-layered",
                 "client":{"name":"client","versions":["v3","v2","v1"],
                   "caps":["record-tls","sni","quic-transport"],
                   "extensions":[{"name":"quic-0rtt","ignorable":true},{"name":"alpn"}],
                   "params":{"mtu":{"min":1400,"max":1500},"cipher":{"values":["AES-GCM"]}}},
                 "server":{"name":"server","versions":["v3","v2","v1"],"caps":["record-tls","sni"],
                   "extensions":[{"name":"alpn"}],
                   "params":{"mtu":{"min":1280,"max":1480},"cipher":{"values":["AES-GCM"]}}}}
                """);
        HttpResponse<String> created = post("/api/sessions", offerBody);
        assertEquals(201, created.statusCode(), created.body());
        JsonNode sel = j(created.body());
        String sid = sel.get("sessionId").asText();
        assertEquals("v2", sel.path("selection").path("version").asText());
        String hash = sel.get("selectionHash").asText();
        String token = sel.get("token").asText();

        // 相同幂等键 + 相同 offer => 200 原会话
        HttpResponse<String> replay = post("/api/sessions", offerBody);
        assertEquals(200, replay.statusCode());
        assertEquals(sid, j(replay.body()).get("sessionId").asText());

        // 相同幂等键 + 不同 offer => 409
        HttpResponse<String> conflict = post("/api/sessions", j("""
                {"idemKey":"http-k1","scenarioId":"tls-layered",
                 "client":{"name":"client","versions":["v2"],"caps":["record-tls","sni"],
                   "extensions":[],"params":{}},
                 "server":{"name":"server","versions":["v2"],"caps":["record-tls","sni"],
                   "extensions":[],"params":{}}}"""));
        assertEquals(409, conflict.statusCode());
        assertEquals("IDEMPOTENCY_CONFLICT", j(conflict.body()).path("error").path("code").asText());

        // confirm 丢失注入 => 503，状态仍 SELECTED
        HttpResponse<String> lost = post("/api/sessions/" + sid + "/confirm",
                j("{\"sessionId\":\"" + sid + "\",\"selectionHash\":\"" + hash
                        + "\",\"token\":\"" + token + "\",\"injections\":{\"loseConfirm\":true}}"));
        assertEquals(503, lost.statusCode());
        assertEquals("CONFIRM_LOST", j(lost.body()).path("error").path("code").asText());

        // 错误 hash => 409（恢复必须同一选择）
        HttpResponse<String> wrong = post("/api/sessions/" + sid + "/confirm",
                j("{\"sessionId\":\"" + sid + "\",\"selectionHash\":\"bad\",\"token\":\"" + token + "\"}"));
        assertEquals(409, wrong.statusCode());
        assertEquals("SELECTION_MISMATCH", j(wrong.body()).path("error").path("code").asText());

        // 正常 confirm
        HttpResponse<String> confirmed = post("/api/sessions/" + sid + "/confirm",
                j("{\"sessionId\":\"" + sid + "\",\"selectionHash\":\"" + hash
                        + "\",\"token\":\"" + token + "\"}"));
        assertEquals(200, confirmed.statusCode());

        // 陈旧 token 激活 => 409
        HttpResponse<String> stale = post("/api/sessions/" + sid + "/active",
                j("{\"sessionId\":\"" + sid + "\",\"token\":\"tok_OLD\"}"));
        assertEquals(409, stale.statusCode());
        assertEquals("STALE_TOKEN", j(stale.body()).path("error").path("code").asText());

        // 正常 active + 重复激活
        HttpResponse<String> active = post("/api/sessions/" + sid + "/active",
                j("{\"sessionId\":\"" + sid + "\",\"token\":\"" + token + "\"}"));
        assertEquals(200, active.statusCode());
        HttpResponse<String> activeAgain = post("/api/sessions/" + sid + "/active",
                j("{\"sessionId\":\"" + sid + "\",\"token\":\"" + token + "\"}"));
        assertEquals(200, activeAgain.statusCode());

        // 导出 / 导入
        HttpResponse<String> exported = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/sessions/" + sid + "/export")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, exported.statusCode());
        HttpResponse<String> imported = post("/api/transcripts/import", j(exported.body()));
        assertEquals(201, imported.statusCode());
        assertTrue(j(imported.body()).get("sameSelection").asBoolean());

        // 循环边发布前拒绝：建一个带循环的草稿
        HttpResponse<String> createdSc = post("/api/scenarios", j("""
                {"id":"cyc","name":"cyc","draft":{"versions":[
                  {"version":"a","requiredCaps":[],"extensions":[],"params":{},"downgrade":[{"target":"b"}]},
                  {"version":"b","requiredCaps":[],"extensions":[],"params":{},"downgrade":[{"target":"a"}]}
                ]}}"""));
        assertEquals(400, createdSc.statusCode(), createdSc.body());
        assertEquals("DRAFT_INVALID", j(createdSc.body()).path("error").path("code").asText());
        assertTrue(j(createdSc.body()).path("error").path("message").asText().contains("循环"));
    }

    @Test
    void draftRevisionConflictOverHttp() throws Exception {
        // 新场景（合法单版本）
        HttpResponse<String> created = post("/api/scenarios", j("""
                {"id":"rev","name":"rev","draft":{"versions":[
                  {"version":"v1","requiredCaps":[],"extensions":[],"params":{},"downgrade":[]}
                ]}}"""));
        assertEquals(201, created.statusCode());
        HttpResponse<String> first = put("/api/scenarios/rev/draft", j("""
                {"expectedRevision":0,"draft":{"versions":[
                  {"version":"v1","requiredCaps":["x"],"extensions":[],"params":{},"downgrade":[]}
                ]}}"""));
        assertEquals(200, first.statusCode());
        HttpResponse<String> second = put("/api/scenarios/rev/draft", j("""
                {"expectedRevision":0,"draft":{"versions":[
                  {"version":"v1","requiredCaps":["y"],"extensions":[],"params":{},"downgrade":[]}
                ]}}"""));
        assertEquals(409, second.statusCode());
        assertEquals("REVISION_CONFLICT", j(second.body()).path("error").path("code").asText());
    }
}
