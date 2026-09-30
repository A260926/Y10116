package negotiator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionServiceTest {

    @TempDir
    Path tmp;

    private static final class Fixture {
        Service service;
        List<String> crashes = new ArrayList<>();
        Path db;
    }

    private Fixture fixture() {
        Fixture f = new Fixture();
        f.db = tmp.resolve("test-" + System.nanoTime() + ".db");
        f.service = TestSupport.newService(f.db, f.crashes);
        f.service.createScenario(TestSupport.scenario("sc", Seed.buildDraft()));
        f.service.publish("sc");
        return f;
    }

    private JsonNode presetOffer(String key) {
        return TestSupport.offer(key, "sc", Seed.client(), Seed.server());
    }

    @Test
    void presetOfferDowngradesV3ToV2() {
        Fixture f = fixture();
        JsonNode raw = presetOffer("k1");
        SessionView v = f.service.offer(raw, Json.write(raw));
        assertEquals("SELECTED", v.status);
        assertEquals("v2", v.selection.get("version").asText());
        // v3 候选被明确拒绝，原因是必选能力缺失
        JsonNode v3 = v.candidates.get(0);
        assertEquals("v3", v3.get("version").asText());
        assertFalse(v3.get("accepted").asBoolean());
        assertEquals("REQUIRED_CAP_MISSING", v3.get("rejectCode").asText());
        JsonNode v2 = v.candidates.get(1);
        assertTrue(v2.get("accepted").asBoolean());
    }

    @Test
    void idempotentSameKeySameOfferReturnsOriginal() {
        Fixture f = fixture();
        JsonNode raw = presetOffer("same-key");
        SessionView a = f.service.offer(raw, Json.write(raw));
        SessionView b = f.service.offer(raw, Json.write(raw));
        assertEquals(a.sessionId, b.sessionId);
        assertTrue(b.idempotentReplay);
    }

    @Test
    void sameKeyDifferentOfferConflicts() {
        Fixture f = fixture();
        JsonNode raw = presetOffer("dup-key");
        f.service.offer(raw, Json.write(raw));
        Models.Party differentServer = Seed.server();
        differentServer.caps = List.of("record-tls", "sni", "quic-transport");
        JsonNode conflict = TestSupport.offer("dup-key", "sc", Seed.client(), differentServer);
        Service.ApiException ex = assertThrows(Service.ApiException.class,
                () -> f.service.offer(conflict, Json.write(conflict)));
        assertEquals(409, ex.status);
        assertEquals("IDEMPOTENCY_CONFLICT", ex.code);
    }

    @Test
    void crashAfterSelectThenResumeSendsSameSelection() {
        Fixture f = fixture();
        JsonNode raw = TestSupport.offerCrash("crash-key", "sc", Seed.client(), Seed.server());
        Service.CrashSignal crash = assertThrows(Service.CrashSignal.class,
                () -> f.service.offer(raw, Json.write(raw)));
        assertEquals(1, f.crashes.size());
        String sid = crash.sessionId;

        // 模拟进程重启：新建 Service 指向同一 SQLite 文件，选择不能重新计算
        List<String> crashes2 = new ArrayList<>();
        Db reopened = new Db(f.db.toString());
        Service restarted = new Service(reopened, s -> crashes2.add(s));

        // 恢复时篡改配置：发布新版本声明不会影响冻结选择 —— 直接验证 resume 比对 hash
        SessionView frozen = restarted.getView(sid);
        assertEquals("SELECTED", frozen.status);
        assertEquals("v2", frozen.selection.get("version").asText());

        ObjectNode wrong = Json.MAPPER.createObjectNode();
        wrong.put("sessionId", sid);
        wrong.put("selectionHash", "deadbeef-wrong-hash");
        wrong.put("token", frozen.token);
        Service.ApiException mismatch = assertThrows(Service.ApiException.class,
                () -> restarted.confirm(wrong, Json.write(wrong)));
        assertEquals("SELECTION_MISMATCH", mismatch.code);

        ObjectNode correct = Json.MAPPER.createObjectNode();
        correct.put("sessionId", sid);
        correct.put("selectionHash", frozen.selectionHash);
        correct.put("token", frozen.token);
        SessionView confirmed = restarted.confirm(correct, Json.write(correct));
        assertEquals("CONFIRMED", confirmed.status);
        assertEquals("v2", confirmed.selection.get("version").asText());
        assertTrue(crashes2.isEmpty(), "恢复路径不应触发崩溃");
    }

    @Test
    void duplicateConfirmReturnsOriginalResult() {
        Fixture f = fixture();
        JsonNode raw = presetOffer("dup-confirm");
        SessionView sel = f.service.offer(raw, Json.write(raw));
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("sessionId", sel.sessionId);
        body.put("selectionHash", sel.selectionHash);
        body.put("token", sel.token);
        body.putObject("injections").put("duplicateConfirm", true);
        SessionView first = f.service.confirm(body, Json.write(body));
        assertEquals("CONFIRMED", first.status);
        SessionView second = f.service.confirm(body, Json.write(body));
        assertEquals("CONFIRMED", second.status);
        long confirmMessages = second.messages.stream()
                .filter(m -> "confirm".equals(String.valueOf(m.get("phase")))).count();
        assertTrue(confirmMessages >= 3, "重复 confirm 追加记录但返回原结果，实际=" + confirmMessages);
    }

    @Test
    void confirmLossInjectionKeepsState() {
        Fixture f = fixture();
        JsonNode raw = presetOffer("lost");
        SessionView sel = f.service.offer(raw, Json.write(raw));
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("sessionId", sel.sessionId);
        body.put("selectionHash", sel.selectionHash);
        body.put("token", sel.token);
        body.putObject("injections").put("loseConfirm", true);
        Service.ApiException lost = assertThrows(Service.ApiException.class,
                () -> f.service.confirm(body, Json.write(body)));
        assertEquals(503, lost.status);
        SessionView unchanged = f.service.getView(sel.sessionId);
        assertEquals("SELECTED", unchanged.status, "confirm 丢失不得推进状态");
    }

    @Test
    void staleTokenCannotActivateNewGeneration() {
        Fixture f = fixture();
        JsonNode raw = presetOffer("stale");
        SessionView sel = f.service.offer(raw, Json.write(raw));
        SessionView confirmed = f.service.confirm(confirmNode(sel), Json.write(confirmNode(sel)));
        SessionView active = f.service.activate(activeNode(confirmed), Json.write(activeNode(confirmed)));
        assertEquals("ACTIVE", active.status);
        String oldToken = active.token;

        // 在活动会话上重新协商 -> 代次前进，旧 token 失效
        ObjectNode renego = Json.MAPPER.createObjectNode();
        renego.put("sessionId", active.sessionId);
        renego.put("oldToken", oldToken);
        renego.set("client", active.client);
        renego.set("server", active.server);
        SessionView gen2 = f.service.renegotiate(renego, Json.write(renego));
        assertEquals(2, gen2.generation);
        assertEquals("SELECTED", gen2.status);
        assertNotEquals(oldToken, gen2.token);

        // 旧 token 不能激活新代次
        ObjectNode staleActivate = Json.MAPPER.createObjectNode();
        staleActivate.put("sessionId", active.sessionId);
        staleActivate.put("token", oldToken);
        Service.ApiException ex = assertThrows(Service.ApiException.class,
                () -> f.service.activate(staleActivate, Json.write(staleActivate)));
        assertEquals("STALE_TOKEN", ex.code);

        // 新代次完整走完
        SessionView c2 = f.service.confirm(confirmNode(gen2), Json.write(confirmNode(gen2)));
        SessionView a2 = f.service.activate(activeNode(c2), Json.write(activeNode(c2)));
        assertEquals("ACTIVE", a2.status);
        assertEquals(2, a2.generation);
    }

    @Test
    void publishedVersionIsImmutableOnlyNewVersionAllowed() throws Exception {
        Fixture f = fixture();
        JsonNode scBefore = Json.MAPPER.valueToTree(
                f.service.db().listScenarios().stream().findFirst().orElseThrow());
        Models.Draft draft = Db.read(f.service.db().findScenario("sc").draftJson, Models.Draft.class);
        // 修改已发布的 v2：增加必选能力
        for (Models.ProtocolVersion v : draft.versions) {
            if ("v2".equals(v.version)) v.requiredCaps.add("tampered-cap");
        }
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("expectedRevision", f.service.db().findScenario("sc").revision);
        body.set("draft", Json.MAPPER.valueToTree(draft));
        f.service.saveDraft("sc", body);
        Service.ApiException ex = assertThrows(Service.ApiException.class, () -> f.service.publish("sc"));
        assertEquals("VERSION_IMMUTABLE", ex.code);

        // 新增 v4 可以发布
        Models.Draft draft2 = Db.read(f.service.db().findScenario("sc").draftJson, Models.Draft.class);
        for (Models.ProtocolVersion v : draft2.versions) {
            if ("v2".equals(v.version)) v.requiredCaps.remove("tampered-cap");
        }
        Models.ProtocolVersion v4 = TestSupport.version("v4",
                List.of("record-tls", "sni", "quic-transport", "post-quantum"),
                List.of(), null, List.of("v3"));
        List<Models.ProtocolVersion> withV4 = new ArrayList<>(draft2.versions);
        withV4.add(v4);
        draft2.versions = withV4;
        ObjectNode body2 = Json.MAPPER.createObjectNode();
        body2.put("expectedRevision", f.service.db().findScenario("sc").revision);
        body2.set("draft", Json.MAPPER.valueToTree(draft2));
        f.service.saveDraft("sc", body2);
        Db.ScenarioRow published = f.service.publish("sc");
        assertTrue(Service.parsePublished(published.publishedJson).containsKey("v4"));
    }

    @Test
    void exportThenImportProducesSameSelection() {
        Fixture f = fixture();
        JsonNode raw = presetOffer("exportable");
        SessionView sel = f.service.offer(raw, Json.write(raw));
        f.service.confirm(confirmNode(sel), Json.write(confirmNode(sel)));
        SessionView active = f.service.activate(activeNode(sel.sessionId, sel.token),
                Json.write(activeNode(sel.sessionId, sel.token)));
        Map<String, Object> doc = f.service.exportTranscript(active.sessionId);
        Map<String, Object> imported = f.service.importTranscript(Json.MAPPER.valueToTree(doc), false);
        assertEquals(Boolean.TRUE, imported.get("sameSelection"));
        assertEquals(sel.selectionHash, imported.get("recomputedHash"));
    }

    @Test
    void unknownRequiredExtensionFailsExplicitly() {
        Fixture f = fixture();
        Models.Party client = Seed.client();
        List<Models.ExtClaim> exts = new ArrayList<>(client.extensions);
        exts.add(new Models.ExtClaim("x-mandatory-experiment", true, false));
        client.extensions = exts;
        JsonNode raw = TestSupport.offer("unknown-req", "sc", client, Seed.server());
        SessionView v = f.service.offer(raw, Json.write(raw));
        assertEquals("FAILED", v.status);
        assertTrue(v.candidates.get(0).get("rejectCode").asText().equals("UNKNOWN_REQUIRED_EXTENSION")
                || v.candidates.get(1).get("rejectCode").asText().equals("UNKNOWN_REQUIRED_EXTENSION"));
    }

    private ObjectNode confirmNode(SessionView sel) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("sessionId", sel.sessionId);
        n.put("selectionHash", sel.selectionHash);
        n.put("token", sel.token);
        return n;
    }

    private ObjectNode activeNode(SessionView v) {
        return activeNode(v.sessionId, v.token);
    }

    private ObjectNode activeNode(String sid, String token) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("sessionId", sid);
        n.put("token", token);
        return n;
    }
}
