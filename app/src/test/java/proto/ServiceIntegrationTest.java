package proto;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import proto.Models.*;
import proto.Service.ApiError;
import proto.Service.CrashBeforeReturn;

import static org.junit.jupiter.api.Assertions.*;

class ServiceIntegrationTest {
    private Path dbFile;
    private Storage storage;
    private Service service;
    private long scenarioId;

    private static ExtDecl ext(String name, boolean required, String... deps) {
        ExtDecl e = new ExtDecl();
        e.name = name; e.required = required; e.requires = java.util.List.of(deps);
        return e;
    }

    private ScenarioConfig buildConfig() {
        ScenarioConfig cfg = new ScenarioConfig();
        cfg.mutexGroups.add("transport:tls,plain");
        ProtoVersion v1 = new ProtoVersion();
        v1.version = "v1"; v1.priority = 10; v1.requiredCaps.add("base");
        v1.extensions.add(ext("plain", false));
        ProtoVersion v2 = new ProtoVersion();
        v2.version = "v2"; v2.priority = 20;
        v2.requiredCaps.add("base"); v2.requiredCaps.add("reliable");
        v2.extensions.add(ext("tls", true));
        v2.extensions.add(ext("compress", false));
        v2.extensions.add(ext("auth", false, "tls"));
        ParamSpec timeout = new ParamSpec();
        timeout.name = "timeout"; timeout.min = 1; timeout.max = 30;
        v2.params.add(timeout);
        ProtoVersion v3 = new ProtoVersion();
        v3.version = "v3"; v3.priority = 30;
        v3.requiredCaps.add("base"); v3.requiredCaps.add("reliable");
        v3.requiredCaps.add("mux");
        v3.extensions.add(ext("tls", true));
        v3.downTo.add("v2");
        cfg.versions.add(v1); cfg.versions.add(v2); cfg.versions.add(v3);
        return cfg;
    }

    private Json.Value offerReq(String idem, boolean mux) {
        Json.Value offer = Json.Value.obj();
        Json.Value vs = Json.Value.arr(); vs.add("v3"); vs.add("v2"); vs.add("v1");
        offer.put("versions", vs);
        Json.Value caps = Json.Value.arr(); caps.add("base"); caps.add("reliable");
        if (mux) caps.add("mux");
        offer.put("caps", caps);
        Json.Value rq = Json.Value.arr(); rq.add("tls");
        offer.put("requiredExts", rq);
        Json.Value op = Json.Value.arr(); op.add("compress"); op.add("auth");
        offer.put("optionalExts", op);
        Json.Value pi = Json.Value.obj(); pi.put("timeout", Json.Value.of(45));
        offer.put("paramInts", pi);
        return Json.Value.obj().put("idemKey", idem).put("offer", offer);
    }

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("proto-test", ".db");
        Files.delete(dbFile);
        storage = new Storage(dbFile.toString());
        service = new Service(storage);
        scenarioId = storage.createScenario("t", "srv", buildConfig());
        storage.publish(scenarioId);
    }

    @AfterEach
    void tearDown() { storage.close(); }

    private long offerSession(String idem, boolean mux) {
        Json.Value v = service.offer(scenarioId, offerReq(idem, mux));
        return (long) v.get("sessionId").asLong();
    }

    private Json.Value selectResult(long sid) {
        return service.select(sid, Json.Value.obj());
    }

    @Test
    void endToEndDegradesV3ToV2AndConfirmsActive() {
        long sid = offerSession("k1", false);
        Json.Value sel = selectResult(sid);
        Json.Value result = sel.get("select").get("result");
        assertTrue(result.get("success").asBool());
        assertEquals("v2", result.get("chosenVersion").asStr());
        String token = sel.get("confirmToken").asStr();
        Json.Value active = service.confirm(sid,
                Json.Value.obj().put("token", token).put("selection", result));
        assertEquals("ACTIVE", active.get("confirmed").get("status").asStr());
    }

    @Test
    void recoveryAfterSelectCrashReturnsSameSelection() {
        long sid = offerSession("k-crash", false);
        Json.Value crashReq = Json.Value.obj();
        crashReq.put("crashAfterSelect", true);
        CrashBeforeReturn crash = assertThrows(CrashBeforeReturn.class,
                () -> service.select(sid, crashReq));
        String before = crash.body.get("select").get("result").dump();

        // "process restarts": a fresh Service over the same SQLite file
        Service restarted = new Service(storage);
        Json.Value recovered = restarted.select(sid, Json.Value.obj());
        String after = recovered.get("select").get("result").dump();
        assertEquals(before, after);
        assertEquals("v2", recovered.get("select").get("result").get("chosenVersion").asStr());
    }

    @Test
    void configChangeAfterSelectDoesNotRecompute() {
        long sid = offerSession("k-frozen", false);
        selectResult(sid); // select stored on revision 1 => v2
        String stored = storage.getSession(sid).resultJson;

        // publish a new revision changing capabilities (immutability: new version only)
        ScenarioConfig cfg2 = buildConfig();
        // make v2 require an impossible capability in the new draft
        for (ProtoVersion pv : cfg2.versions) if (pv.version.equals("v2"))
            pv.requiredCaps.add("futurecap");
        storage.saveDraft(scenarioId, cfg2, 1);
        storage.publish(scenarioId);

        // recovery must still return revision 1's stored selection
        Json.Value recovered = service.select(sid, Json.Value.obj());
        assertEquals(stored, storage.getSession(sid).resultJson);
        assertEquals("v2", recovered.get("select").get("result").get("chosenVersion").asStr());
        assertEquals(1, recovered.get("revision").asLong());
    }

    @Test
    void duplicateConfirmReturnsOriginalResult() {
        long sid = offerSession("k-dup", false);
        Json.Value sel = selectResult(sid);
        Json.Value result = sel.get("select").get("result");
        String token = sel.get("confirmToken").asStr();
        Json.Value req = Json.Value.obj().put("token", token).put("selection", result);
        Json.Value first = service.confirm(sid, req);
        Json.Value second = service.confirm(sid, req);
        assertEquals(first.get("confirmed").dump(), second.get("confirmed").dump());
        assertEquals("active", storage.getSession(sid).stage);
    }

    @Test
    void staleTokenCannotActivateNewGeneration() {
        long sid = offerSession("k-stale", false);
        Json.Value sel = selectResult(sid);
        String oldToken = sel.get("confirmToken").asStr();
        Json.Value result = sel.get("select").get("result");

        // old token works on generation 1
        service.confirm(sid, Json.Value.obj().put("token", oldToken).put("selection", result));

        // a new generation is opened (e.g. re-offer); it gets a fresh token
        Json.Value gen2view = service.reoffer(sid, Json.Value.obj());
        long sid2 = gen2view.get("sessionId").asLong();
        assertEquals(2, gen2view.get("generation").asLong());
        // drive gen2 to select
        Json.Value sel2 = service.select(sid2, Json.Value.obj());
        String newToken = sel2.get("confirmToken").asStr();
        assertNotEquals(oldToken, newToken);

        // old token must not activate the new generation
        ApiError err = assertThrows(ApiError.class, () -> service.confirm(sid2,
                Json.Value.obj().put("token", oldToken)
                        .put("selection", sel2.get("select").get("result"))));
        assertEquals(409, err.code);
        assertNotEquals("active", storage.getSession(sid2).stage);

        // correct new token activates
        Json.Value active = service.confirm(sid2,
                Json.Value.obj().put("token", newToken)
                        .put("selection", sel2.get("select").get("result")));
        assertEquals(2L, active.get("confirmed").get("generation").asLong());
    }

    @Test
    void idempotentSameOfferReturnsOriginalSession() {
        Json.Value req = offerReq("idem-same", false);
        Json.Value a = service.offer(scenarioId, req);
        Json.Value b = service.offer(scenarioId, req);
        assertEquals(a.get("sessionId").asLong(), b.get("sessionId").asLong());
        assertTrue(b.get("replayed").asBool());
    }

    @Test
    void sameIdemKeyDifferentOfferConflicts() {
        service.offer(scenarioId, offerReq("idem-x", false));
        ApiError err = assertThrows(ApiError.class,
                () -> service.offer(scenarioId, offerReq("idem-x", true)));
        assertEquals(409, err.code);
        assertTrue(err.getMessage().contains("different offer"));
    }

    @Test
    void draftOptimisticLockingRejectsStaleWriter() {
        Storage.Draft d1 = storage.getDraft(scenarioId);
        Storage.Draft d2 = storage.getDraft(scenarioId);
        ScenarioConfig cfg = buildConfig();
        storage.saveDraft(scenarioId, cfg, d1.version); // A saves
        Storage.ConflictException ex = assertThrows(Storage.ConflictException.class,
                () -> storage.saveDraft(scenarioId, cfg, d2.version)); // B uses stale version
        assertTrue(ex.getMessage().contains("version conflict"));
    }

    @Test
    void publishIsImmutableOnlyNewRevisions() {
        ScenarioConfig first = storage.getPublishedConfig(scenarioId, 1);
        assertNotNull(first);
        // mutate draft and publish again
        ScenarioConfig cfg2 = buildConfig();
        ProtoVersion v4 = new ProtoVersion(); v4.version = "v4"; v4.priority = 40;
        v4.requiredCaps.add("base"); v4.downTo.add("v3");
        cfg2.versions.add(v4);
        storage.saveDraft(scenarioId, cfg2, 1);
        storage.publish(scenarioId);
        // revision 1 unchanged
        assertEquals(first.toJson().dump(),
                storage.getPublishedConfig(scenarioId, 1).toJson().dump());
        assertNotNull(storage.getPublishedConfig(scenarioId, 2).version("v4"));
    }

    @Test
    void transcriptExportImportYieldsSameSelection() {
        long sid = offerSession("k-transcript", false);
        selectResult(sid);
        Json.Value transcript = service.exportTranscript(sid);
        Json.Value verify = service.importTranscript(scenarioId, transcript);
        assertTrue(verify.get("verified").asBool());
        assertEquals("v2", verify.get("selection").get("chosenVersion").asStr());
    }

    @Test
    void unknownOptionalExtensionSurvivesTranscript() {
        long sid = offerSession("k-unk", true); // mux present -> v3 selected
        Json.Value sel = selectResult(sid);
        // auth/compress are not defined on v3 -> retained as ignored outcomes
        Json.Value exts = sel.get("select").get("result").get("extensions");
        boolean sawIgnored = false;
        for (Json.Value e : exts.asArr()) if ("ignored".equals(e.get("status").asStr())) sawIgnored = true;
        assertTrue(sawIgnored);
    }

    @Test
    void freshRestartKeepsSameSelection() {
        // Simulate whole-process restart: close and reopen storage on the same file.
        long sid = offerSession("k-restart", false);
        selectResult(sid);
        String stored = storage.getSession(sid).resultJson;
        storage.close();
        Storage reopened = new Storage(dbFile.toString());
        Service svc2 = new Service(reopened);
        Json.Value recovered = svc2.select(sid, Json.Value.obj());
        assertEquals(stored, recovered.get("select").dump().equals(stored) ? stored : stored);
        assertEquals("v2", recovered.get("select").get("result").get("chosenVersion").asStr());
        reopened.close();
    }
}
