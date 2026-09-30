package proto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

import proto.Models.*;
import proto.Storage.*;

/** Orchestrates the offer/select/confirm/active state machine and fault injection. */
public final class Service {
    private final Storage storage;

    public Service(Storage storage) { this.storage = storage; }

    public static final class ApiError extends RuntimeException {
        public final int code;
        public ApiError(int code, String msg) { super(msg); this.code = code; }
    }

    public static final class CrashBeforeReturn extends RuntimeException {
        public final Json.Value body;
        public CrashBeforeReturn(Json.Value body, String msg) { super(msg); this.body = body; }
    }

    private static int bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length;
    }

    private ScenarioRow scenarioOrDie(long id) {
        ScenarioRow r = storage.listScenarios().stream().filter(x -> x.id == id).findFirst().orElse(null);
        if (r == null) throw new ApiError(404, "scenario not found");
        if (r.revision == 0) throw new ApiError(409, "scenario has no published revision; publish first");
        return r;
    }

    private Set<String> serverCaps(ScenarioConfig cfg) {
        Set<String> caps = new LinkedHashSet<>();
        for (ProtoVersion v : cfg.versions) caps.addAll(v.requiredCaps);
        return caps;
    }

    private String makeToken(long sessionId, int generation) {
        String raw = sessionId + "." + generation + "." + (System.nanoTime());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    // ---- offer ----

    public Json.Value offer(long scenarioId, Json.Value req) {
        ScenarioRow row = scenarioOrDie(scenarioId);
        String idemKey = req.reqStr("idemKey");
        // Canonicalize through the domain model so semantic equality (independent of
        // JSON field order or omitted optional fields) drives idempotency.
        Offer parsed = req.get("offer") != null ? Offer.from(req.get("offer")) : new Offer();
        String offerJson = parsed.toJson().dump();
        SessionRow existing = storage.findSession(scenarioId, idemKey);
        if (existing != null) {
            if (!existing.offerJson.equals(offerJson))
                throw new ApiError(409, "idempotency key '" + idemKey + "' reused with a different offer");
            return sessionView(existing, true); // identical offer: return original session
        }
        long sid = storage.insertSession(scenarioId, row.revision, idemKey, offerJson, bytes(offerJson));
        storage.updateToken(sid, makeToken(sid, 1));
        storage.addMessage(sid, 1, "offer", "C2S", offerJson, "offer");
        // server capability declaration as offer response
        ScenarioConfig cfg = storage.getPublishedConfig(scenarioId, row.revision);
        Json.Value decl = Json.Value.obj();
        Json.Value caps = Json.Value.arr();
        for (String c : serverCaps(cfg)) caps.add(c);
        decl.put("serverCaps", caps);
        Json.Value vs = Json.Value.arr();
        for (ProtoVersion v : cfg.versions) vs.add(v.version);
        decl.put("serverVersions", vs);
        decl.put("revision", row.revision);
        String declJson = decl.dump();
        storage.addMessage(sid, 1, "offer", "S2C", declJson, "server capability declaration");
        SessionRow s = storage.getSession(sid);
        return sessionView(s, false);
    }

    // ---- select ----

    public Json.Value select(long sessionId, Json.Value req) {
        SessionRow s = storage.getSession(sessionId);
        if (s == null) throw new ApiError(404, "session not found");

        // Recovery after a crash between saving select and sending its response:
        // the same selection MUST be replayed, never recomputed from current config.
        if (s.resultJson != null) {
            if (req != null && req.has("selection")) {
                Json.Value claimed = req.get("selection");
                if (!claimed.dump().equals(selectionDigest(s.resultJson)))
                    throw new ApiError(409, "recovery selection differs from the stored selection");
            }
            return selectView(s);
        }

        ScenarioConfig cfg = storage.getPublishedConfig(s.scenarioId, s.revision);
        Offer offer = Offer.from(Json.parse(s.offerJson));
        Set<String> caps = serverCaps(cfg);
        Negotiation.Result result = Negotiation.run(cfg, offer, caps);

        Json.Value selectMsg = Json.Value.obj();
        selectMsg.put("scenarioId", s.scenarioId);
        selectMsg.put("revision", s.revision);
        selectMsg.put("result", result.toJson());
        String selectJson = selectMsg.dump();
        storage.addMessage(sessionId, s.generation, "select", "S2C", selectJson,
                result.success ? "selection" : "negotiation failure recorded");
        storage.saveSelect(sessionId, selectJson); // commit point

        // Simulated process exit: state is durable, response is never sent.
        if (req != null && req.get("crashAfterSelect") != null && req.get("crashAfterSelect").asBool()) {
            throw new CrashBeforeReturn(selectView(storage.getSession(sessionId)),
                    "simulated crash after saving select, before sending response");
        }
        return selectView(storage.getSession(sessionId));
    }

    private String selectionDigest(String resultJson) {
        Json.Value sel = Json.parse(resultJson);
        Json.Value r = sel.get("result");
        return r == null ? "{}" : r.dump();
    }

    // ---- confirm ----

    public Json.Value confirm(long sessionId, Json.Value req) {
        SessionRow s = storage.getSession(sessionId);
        if (s == null) throw new ApiError(404, "session not found");
        if (s.resultJson == null) throw new ApiError(409, "cannot confirm before select");
        Json.Value result = Json.parse(s.resultJson).get("result");
        if (!result.get("success").asBool())
            throw new ApiError(409, "cannot confirm a failed negotiation");

        String token = req != null && req.get("token") != null ? req.get("token").asStr() : null;
        if (token == null || !token.equals(s.confirmToken))
            throw new ApiError(409, "stale or unknown session token");

        // Recovery must confirm the same selection; never re-resolve from current config.
        if (req != null && req.has("selection")) {
            if (!req.get("selection").dump().equals(result.dump()))
                throw new ApiError(409, "confirm selection differs from stored selection");
        }

        if ("active".equals(s.stage) && s.confirmedJson != null) {
            // duplicate confirm: return the original result unchanged
            return activeView(s);
        }

        Json.Value confirmed = Json.Value.obj();
        confirmed.put("chosenVersion", result.get("chosenVersion"));
        confirmed.put("params", result.get("params"));
        confirmed.put("extensions", result.get("extensions"));
        confirmed.put("generation", s.generation);
        confirmed.put("status", "ACTIVE");
        String confirmedJson = confirmed.dump();
        storage.addMessage(sessionId, s.generation, "confirm", "C2S", req.dump(), "confirm request");
        storage.saveConfirm(sessionId, confirmedJson);

        // Simulated confirm loss: server committed ACTIVE but the reply never arrives.
        if (req.get("simulateLoss") != null && req.get("simulateLoss").asBool()) {
            throw new CrashBeforeReturn(activeView(storage.getSession(sessionId)),
                    "simulated confirm response lost (server already ACTIVE)");
        }

        Json.Value ack = Json.Value.obj();
        ack.put("status", "ACTIVE");
        ack.put("generation", s.generation);
        String ackJson = ack.dump();
        storage.addMessage(sessionId, s.generation, "active", "S2C", ackJson, "activation ack");
        return activeView(storage.getSession(sessionId));
    }

    // ---- new generation (stale-token scenario) ----

    public Json.Value reoffer(long sessionId, Json.Value req) {
        SessionRow s = storage.getSession(sessionId);
        if (s == null) throw new ApiError(404, "session not found");
        long newId = storage.beginNewGeneration(sessionId);
        SessionRow n = storage.getSession(newId);
        ScenarioConfig cfg = storage.getPublishedConfig(s.scenarioId, s.revision);
        Json.Value decl = Json.Value.obj();
        Json.Value caps = Json.Value.arr();
        for (String c : serverCaps(cfg)) caps.add(c);
        decl.put("serverCaps", caps);
        decl.put("generation", n.generation);
        storage.addMessage(newId, n.generation, "offer", "C2S", n.offerJson, "re-offer new generation");
        storage.addMessage(newId, n.generation, "offer", "S2C", decl.dump(), "new token issued");
        // issue a fresh token
        String token = makeToken(newId, n.generation);
        storage.updateToken(newId, token);
        Json.Value view = sessionView(storage.getSession(newId), false);
        view.put("previousGeneration", s.generation);
        return view;
    }

    // ---- transcript export / import ----

    public Json.Value exportTranscript(long sessionId) {
        SessionRow s = storage.getSession(sessionId);
        if (s == null) throw new ApiError(404, "session not found");
        Json.Value t = Json.Value.obj();
        t.put("format", "proto-negotiator-transcript/1");
        t.put("scenarioId", s.scenarioId);
        t.put("revision", s.revision);
        t.put("offer", Json.parse(s.offerJson));
        if (s.resultJson != null) t.put("select", Json.parse(s.resultJson));
        if (s.confirmedJson != null) t.put("confirmed", Json.parse(s.confirmedJson));
        Json.Value msgs = Json.Value.arr();
        for (Json.Value m : storage.listMessages(sessionId)) msgs.add(m);
        t.put("messages", msgs);
        return t;
    }

    public Json.Value importTranscript(long scenarioId, Json.Value t) {
        // Re-run the negotiation from the recorded offer and revision (immutable config),
        // then assert the recomputed selection equals the recorded one.
        int revision = (int) t.get("revision").asLong();
        ScenarioConfig cfg = storage.getPublishedConfig(scenarioId, revision);
        if (cfg == null) throw new ApiError(409, "recorded revision " + revision + " not published");
        Offer offer = Offer.from(t.get("offer"));
        Negotiation.Result recomputed = Negotiation.run(cfg, offer, serverCaps(cfg));
        if (t.get("select") == null) throw new ApiError(400, "transcript has no select record");
        Json.Value recorded = t.get("select").get("result");
        if (!recomputed.toJson().dump().equals(recorded.dump())) {
            Json.Value diff = Json.Value.obj();
            diff.put("recorded", recorded);
            diff.put("recomputed", recomputed.toJson());
            throw new ApiError(409, "imported transcript yields a different selection: " + diff.dump());
        }
        Json.Value out = Json.Value.obj();
        out.put("verified", true);
        out.put("selection", recorded);
        return out;
    }

    // ---- views ----

    private Json.Value sessionView(SessionRow s, boolean replay) {
        Json.Value v = base(s);
        v.put("replayed", replay);
        return v;
    }

    private Json.Value selectView(SessionRow s) {
        Json.Value v = base(s);
        v.put("select", Json.parse(s.resultJson));
        return v;
    }

    private Json.Value activeView(SessionRow s) {
        Json.Value v = base(s);
        if (s.confirmedJson != null) v.put("confirmed", Json.parse(s.confirmedJson));
        return v;
    }

    private Json.Value base(SessionRow s) {
        Json.Value v = Json.Value.obj();
        v.put("sessionId", s.id);
        v.put("scenarioId", s.scenarioId);
        v.put("revision", s.revision);
        v.put("idemKey", s.idemKey);
        v.put("stage", s.stage);
        v.put("generation", s.generation);
        v.put("confirmToken", s.confirmToken == null ? Json.Value.ofNull() : Json.Value.str(s.confirmToken));
        v.put("offerBytes", s.offerBytes);
        Json.Value msgs = Json.Value.arr();
        for (Json.Value m : storage.listMessages(s.id)) msgs.add(m);
        v.put("messages", msgs);
        return v;
    }
}
