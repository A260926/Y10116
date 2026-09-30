package negotiator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话状态机与场景服务。
 *
 * 阶段：offer -> select(SELECTED) -> confirm(CONFIRMED) -> active(ACTIVE)。
 * 选择在 offer/select 时计算一次并冻结（selection_json + selection_hash + 场景快照），
 * 之后 resume / confirm / active 全部只比对冻结结果，绝不重新计算。
 */
public final class Service {

    public static final class ApiException extends RuntimeException {
        public final int status;
        public final String code;
        public ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    public static final class CrashSignal extends RuntimeException {
        public final String sessionId;
        public CrashSignal(String sessionId) {
            super("select 已持久化，confirm 发送前崩溃（注入）");
            this.sessionId = sessionId;
        }
    }

    private final Db db;
    private final CrashPolicy crashPolicy;

    public Service(Db db) {
        this(db, CrashPolicy.realProcess());
    }

    public Service(Db db, CrashPolicy crashPolicy) {
        this.db = db;
        this.crashPolicy = crashPolicy;
    }

    public Db db() { return db; }

    @FunctionalInterface
    public interface CrashPolicy {
        void onCrash(String sessionId);

        static CrashPolicy realProcess() {
            return sid -> {
                System.err.println("[CRASH 注入] session=" + sid
                        + " select 已落盘，confirm 前进程退出");
                Runtime.getRuntime().halt(137);
            };
        }
    }

    // ================= 场景 / 草稿 / 发布 =================

    public Db.ScenarioRow createScenario(JsonNode body) {
        String id = text(body, "id", null);
        String name = text(body, "name", null);
        if (blank(id) || blank(name)) {
            throw new ApiException(400, "VALIDATION", "缺少 id 或 name");
        }
        String description = text(body, "description", "");
        Models.Draft draft = Json.MAPPER.convertValue(
                body.has("draft") ? body.get("draft") : Json.MAPPER.createObjectNode(),
                Models.Draft.class);
        List<String> errors = Models.validateDraft(draft);
        if (!errors.isEmpty()) {
            throw new ApiException(400, "DRAFT_INVALID", String.join("; ", errors));
        }
        try {
            if (db.findScenario(id) != null) {
                throw new ApiException(409, "SCENARIO_EXISTS", "场景已存在: " + id);
            }
            db.insertScenario(id, name, description, Json.write(draft));
            return db.findScenario(id);
        } catch (SQLException e) {
            throw new ApiException(500, "DB", e.getMessage());
        }
    }

    /** 乐观锁保存草稿；返回更新后的行，revision 冲突抛 409。 */
    public Db.ScenarioRow saveDraft(String scenarioId, JsonNode body) {
        Db.ScenarioRow row = mustScenario(scenarioId);
        int expected = body.has("expectedRevision") ? body.get("expectedRevision").asInt() : row.revision;
        Models.Draft draft = Json.MAPPER.convertValue(body.get("draft"), Models.Draft.class);
        List<String> errors = Models.validateDraft(draft);
        if (!errors.isEmpty()) {
            throw new ApiException(400, "DRAFT_INVALID", String.join("; ", errors));
        }
        try {
            if (!db.updateDraft(scenarioId, Json.write(draft), expected)) {
                throw new ApiException(409, "REVISION_CONFLICT",
                        "草稿已被另一个编辑窗口修改（当前 revision=" + db.findScenario(scenarioId).revision
                                + "，提交基于 " + expected + "）；请刷新后重试");
            }
            return db.findScenario(scenarioId);
        } catch (SQLException e) {
            throw new ApiException(500, "DB", e.getMessage());
        }
    }

    /**
     * 发布：草稿中出现的新版本追加进已发布集合；已发布版本的声明必须逐字节一致，
     * 否则拒绝（发布后的能力声明不可修改，只能创建新版本）。
     */
    @SuppressWarnings("unchecked")
    public synchronized Db.ScenarioRow publish(String scenarioId) {
        Db.ScenarioRow row = mustScenario(scenarioId);
        Models.Draft draft = Db.read(row.draftJson, Models.Draft.class);
        List<String> errors = Models.validateDraft(draft);
        if (!errors.isEmpty()) {
            throw new ApiException(400, "DRAFT_INVALID", String.join("; ", errors));
        }
        Map<String, Models.ProtocolVersion> published = parsePublished(row.publishedJson);
        for (Models.ProtocolVersion v : draft.versions) {
            Models.ProtocolVersion existing = published.get(v.version);
            if (existing != null) {
                if (!Json.writeCanonical(existing).equals(Json.writeCanonical(v))) {
                    throw new ApiException(409, "VERSION_IMMUTABLE",
                            "版本 " + v.version + " 已发布，能力声明不可修改；请创建新版本号");
                }
            } else {
                published.put(v.version, v);
            }
        }
        if (published.isEmpty()) {
            throw new ApiException(400, "NOTHING_TO_PUBLISH", "草稿中没有可发布的版本");
        }
        List<Models.ProtocolVersion> merged = new ArrayList<>(published.values());
        try {
            db.publish(scenarioId, Json.write(merged), Json.write(draft));
            return db.findScenario(scenarioId);
        } catch (SQLException e) {
            throw new ApiException(500, "DB", e.getMessage());
        }
    }

    private Db.ScenarioRow mustScenario(String scenarioId) {
        try {
            Db.ScenarioRow row = db.findScenario(scenarioId);
            if (row == null) throw new ApiException(404, "SCENARIO_NOT_FOUND", "场景不存在: " + scenarioId);
            return row;
        } catch (SQLException e) {
            throw new ApiException(500, "DB", e.getMessage());
        }
    }

    static Map<String, Models.ProtocolVersion> parsePublished(String json) {
        Map<String, Models.ProtocolVersion> out = new LinkedHashMap<>();
        if (blank(json)) return out;
        List<Models.ProtocolVersion> list = Db.readList(json, new TypeReference<>() {});
        for (Models.ProtocolVersion v : list) out.put(v.version, v);
        return out;
    }

    // ================= offer / select =================

    /**
     * 幂等 offer：相同 idemKey + 相同 offer -> 返回原会话；相同 key 不同 offer -> 409。
     */
    public SessionView offer(JsonNode body, String rawBody) {
        Models.OfferRequest req = Json.MAPPER.convertValue(body, Models.OfferRequest.class);
        Db.ScenarioRow row;
        Map<String, Models.ProtocolVersion> published;
        try {
            row = mustScenario(req.scenarioId == null ? "" : req.scenarioId);
            published = parsePublished(row.publishedJson);
        } catch (ApiException e) {
            throw e;
        }
        if (published.isEmpty()) {
            throw new ApiException(409, "NO_PUBLISHED_VERSION", "场景尚未发布任何版本");
        }
        Models.requireValidOffer(req, published);

        Map<String, Object> offerSeed = new LinkedHashMap<>();
        offerSeed.put("scenarioId", req.scenarioId);
        offerSeed.put("client", Json.MAPPER.convertValue(req.client, JsonNode.class));
        offerSeed.put("server", Json.MAPPER.convertValue(req.server, JsonNode.class));
        String offerHash = Json.sha256(offerSeed);

        try {
            SessionRecord existing = db.findSessionByIdemKey(req.idemKey);
            if (existing != null) {
                if (!offerHash.equals(existing.offerHash)) {
                    throw new ApiException(409, "IDEMPOTENCY_CONFLICT",
                            "幂等键 " + req.idemKey + " 已绑定另一份 offer（原 offerHash="
                                    + existing.offerHash.substring(0, 10) + "…）");
                }
                return view(existing, true);
            }

            Engine.Result result = Engine.negotiate(published, req.client, req.server);
            SessionRecord rec = new SessionRecord();
            rec.id = "ses_" + UUID.randomUUID().toString().substring(0, 12);
            rec.scenarioId = req.scenarioId;
            rec.idemKey = req.idemKey;
            rec.offerHash = offerHash;
            rec.generation = 1;
            rec.clientJson = Json.write(req.client);
            rec.serverJson = Json.write(req.server);
            rec.scenarioSnapshotJson = Json.writeCanonical(
                    published.values().stream().toList());
            rec.offerJson = rawBody == null ? Json.write(body) : rawBody;
            rec.candidateJson = Json.write(result.candidates);

            int seq = 1;
            if (result.selection.success) {
                rec.status = "SELECTED";
                rec.selectionJson = Json.write(result.selection);
                rec.selectionHash = result.selection.selectionHash();
                rec.token = tokenFor(rec.id, rec.generation, offerHash);
            } else {
                rec.status = "FAILED";
                rec.failureJson = Json.write(Map.of(
                        "code", result.selection.failureCode,
                        "detail", result.selection.failureDetail));
            }

            db.insertSession(rec);

            // 1) C2S offer（字节数按实际请求体）
            db.insertMessage(rec.id, 1, "offer", "C2S", seq++,
                    rawBody == null ? Json.write(body) : rawBody,
                    Json.write(Map.of("phase", "offer", "from", "client",
                            "versions", req.client.versions, "caps", req.client.caps,
                            "extensions", extNames(req.client))),
                    "客户端能力声明");

            // 2) S2C offer：服务端回报自己的能力
            Map<String, Object> serverOffer = new LinkedHashMap<>();
            serverOffer.put("phase", "offer");
            serverOffer.put("from", "server");
            serverOffer.put("versions", req.server.versions);
            serverOffer.put("caps", req.server.caps);
            serverOffer.put("extensions", extNames(req.server));
            db.insertMessage(rec.id, 1, "offer", "S2C", seq++,
                    Json.write(serverOffer), Json.write(serverOffer), "服务端能力声明");

            // 3) C2S select：客户端提交选择提案
            Map<String, Object> selectProposal = new LinkedHashMap<>();
            selectProposal.put("phase", "select");
            selectProposal.put("from", "client");
            selectProposal.put("success", result.selection.success);
            if (result.selection.success) {
                selectProposal.put("selection", Json.MAPPER.convertValue(
                        result.selection, JsonNode.class));
                selectProposal.put("selectionHash", rec.selectionHash);
            } else {
                selectProposal.put("failure", Map.of(
                        "code", result.selection.failureCode,
                        "detail", result.selection.failureDetail));
            }
            db.insertMessage(rec.id, 1, "select", "C2S", seq++,
                    Json.write(selectProposal), Json.write(selectProposal),
                    result.selection.success ? "客户端选择提案（交集+依赖+互斥+参数）"
                            : "客户端报告协商失败");

            // 4) S2C select ack：服务端按相同固定规则独立复算并确认
            Map<String, Object> selectAck = new LinkedHashMap<>();
            selectAck.put("phase", "select");
            selectAck.put("from", "server");
            selectAck.put("success", result.selection.success);
            if (result.selection.success) {
                selectAck.put("selectionHash", rec.selectionHash);
                selectAck.put("token", rec.token);
                selectAck.put("next", "confirm");
            } else {
                selectAck.put("failure", Map.of(
                        "code", result.selection.failureCode,
                        "detail", result.selection.failureDetail));
            }
            db.insertMessage(rec.id, 1, "select", "S2C", seq,
                    Json.write(selectAck), Json.write(selectAck),
                    result.selection.success
                            ? "服务端复算一致，冻结选择并签发 token（保存 select 完成）"
                            : "全部候选被拒，会话失败");

            SessionView view = view(rec, false);

            if (result.selection.success && req.injections != null && req.injections.crashAfterSelect) {
                crashPolicy.onCrash(rec.id);
            }
            return view;
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "幂等键并发冲突");
            }
            throw new ApiException(500, "DB", e.getMessage());
        }
    }

    private List<Map<String, Object>> extNames(Models.Party p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Models.ExtClaim c : p.extensions) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", c.name);
            one.put("required", c.required);
            one.put("ignorable", c.ignorable);
            out.add(one);
        }
        return out;
    }

    private static String tokenFor(String sessionId, int generation, String offerHash) {
        return "tok_" + Json.sha256(sessionId + "#" + generation + "#" + offerHash).substring(0, 16);
    }

    // ================= confirm / active =================

    /**
     * confirm：恢复或继续。客户端必须携带冻结的 selectionHash：
     *  - 不一致 -> 409（不得借配置变化重新计算）
     *  - 陈旧 token（generation 不匹配）-> 409 STALE_TOKEN
     *  - confirm 丢失注入 -> 503，不产生 confirm 消息
     *  - SELECTED -> 推进到 CONFIRMED；CONFIRMED/ACTIVE -> 幂等返回原结果
     */
    public SessionView confirm(JsonNode body, String rawBody) {
        String sessionId = text(body, "sessionId", null);
        String hash = text(body, "selectionHash", null);
        String token = text(body, "token", null);
        boolean lose = body.path("injections").path("loseConfirm").asBoolean(false);
        boolean duplicate = body.path("injections").path("duplicateConfirm").asBoolean(false);
        SessionRecord rec = mustSession(sessionId);

        if ("FAILED".equals(rec.status)) {
            throw new ApiException(409, "SESSION_FAILED",
                    "会话已处于失败状态，无法 confirm: " + rec.failureJson);
        }
        if (!rec.selectionHash.equals(hash)) {
            throw new ApiException(409, "SELECTION_MISMATCH",
                    "恢复必须发送同一选择（冻结 hash=" + abbrev(rec.selectionHash)
                            + "，提交=" + abbrev(hash) + "），不会因配置变化重新计算");
        }
        if (rec.token != null && !rec.token.equals(token)) {
            throw new ApiException(409, "BAD_TOKEN", "token 与冻结代次不匹配");
        }
        if (lose) {
            throw new ApiException(503, "CONFIRM_LOST",
                    "注入：confirm 在网络中丢失（服务端未推进状态，请用同一 selectionHash 重试）");
        }
        try {
            if ("SELECTED".equals(rec.status)) {
                int seq = nextSeq(rec.id, rec.generation);
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("phase", "confirm");
                req.put("from", "client");
                req.put("selectionHash", hash);
                req.put("token", token);
                db.insertMessage(rec.id, rec.generation, "confirm", "C2S", seq,
                        rawBody == null ? Json.write(req) : rawBody,
                        Json.write(req), "客户端 confirm（携带冻结选择与 token）");

                Map<String, Object> ack = new LinkedHashMap<>();
                ack.put("phase", "confirm");
                ack.put("from", "server");
                ack.put("accepted", true);
                ack.put("generation", rec.generation);
                ack.put("selectionHash", rec.selectionHash);
                db.insertMessage(rec.id, rec.generation, "confirm", "S2C", seq + 1,
                        Json.write(ack), Json.write(ack), "服务端确认参数，等待激活");

                rec.status = "CONFIRMED";
                db.updateSession(rec);
                return view(rec, false);
            }
            // CONFIRMED / ACTIVE：重复 confirm 返回原结果（仅追加一条重复消息记录）
            int seq = nextSeq(rec.id, rec.generation);
            Map<String, Object> dup = new LinkedHashMap<>();
            dup.put("phase", "confirm");
            dup.put("from", "client");
            dup.put("selectionHash", hash);
            dup.put("duplicate", true);
            db.insertMessage(rec.id, rec.generation, "confirm", "C2S", seq,
                    rawBody == null ? Json.write(dup) : rawBody,
                    Json.write(dup), duplicate
                            ? "注入：重复 confirm —— 返回原结果"
                            : "重复 confirm —— 返回原结果");
            return view(rec, true);
        } catch (SQLException e) {
            throw new ApiException(500, "DB", e.getMessage());
        }
    }

    /** active：用 token 激活。旧代次 token 不能激活新代次。重复激活幂等。 */
    public SessionView activate(JsonNode body, String rawBody) {
        String sessionId = text(body, "sessionId", null);
        String token = text(body, "token", null);
        SessionRecord rec = mustSession(sessionId);
        if ("FAILED".equals(rec.status)) {
            throw new ApiException(409, "SESSION_FAILED", "失败会话不能激活");
        }
        if (rec.token == null || !rec.token.equals(token)) {
            throw new ApiException(409, "STALE_TOKEN",
                    "陈旧或非法 token，不能激活代次 " + rec.generation);
        }
        if (!"CONFIRMED".equals(rec.status) && !"ACTIVE".equals(rec.status)) {
            throw new ApiException(409, "NOT_CONFIRMED", "会话尚未 confirm（当前状态 " + rec.status + "）");
        }
        try {
            if ("ACTIVE".equals(rec.status)) {
                int seq = nextSeq(rec.id, rec.generation);
                Map<String, Object> dup = new LinkedHashMap<>();
                dup.put("phase", "active");
                dup.put("from", "client");
                dup.put("duplicate", true);
                db.insertMessage(rec.id, rec.generation, "active", "C2S", seq,
                        rawBody == null ? Json.write(dup) : rawBody,
                        Json.write(dup), "重复激活 —— 返回原激活结果");
                return view(rec, true);
            }
            int seq = nextSeq(rec.id, rec.generation);
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("phase", "active");
            req.put("from", "client");
            req.put("token", token);
            db.insertMessage(rec.id, rec.generation, "active", "C2S", seq,
                    rawBody == null ? Json.write(req) : rawBody,
                    Json.write(req), "客户端请求激活");

            Engine.Selection sel = Db.read(rec.selectionJson, Engine.Selection.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("phase", "active");
            result.put("from", "server");
            result.put("activated", true);
            result.put("generation", rec.generation);
            result.put("version", sel.version);
            result.put("caps", sel.caps);
            result.put("extensions", sel.extensions);
            result.put("params", Json.MAPPER.convertValue(sel.params, JsonNode.class));
            db.insertMessage(rec.id, rec.generation, "active", "S2C", seq + 1,
                    Json.write(result), Json.write(result), "会话激活，最终参数生效");

            rec.status = "ACTIVE";
            rec.activeResultJson = Json.write(result);
            db.updateSession(rec);
            return view(rec, false);
        } catch (SQLException e) {
            throw new ApiException(500, "DB", e.getMessage());
        }
    }

    /**
     * 活动会话上发起新一代次的重新协商：旧 token 立即失效。
     * 新选择基于当前 offer 携带的双方能力与场景快照重新计算，冻结为 generation+1。
     */
    public SessionView renegotiate(JsonNode body, String rawBody) {
        String sessionId = text(body, "sessionId", null);
        String oldToken = text(body, "oldToken", null);
        SessionRecord rec = mustSession(sessionId);
        if (!"ACTIVE".equals(rec.status)) {
            throw new ApiException(409, "NOT_ACTIVE", "只有 ACTIVE 会话可以重新协商");
        }
        if (rec.token == null || !rec.token.equals(oldToken)) {
            throw new ApiException(409, "STALE_TOKEN", "旧 token 校验失败，不能发起新一代次");
        }
        Models.Party client = Json.MAPPER.convertValue(body.get("client"), Models.Party.class);
        Models.Party server = Json.MAPPER.convertValue(body.get("server"), Models.Party.class);
        Map<String, Models.ProtocolVersion> snapshot = parsePublished(rec.scenarioSnapshotJson);
        Models.OfferRequest pseudo = new Models.OfferRequest();
        pseudo.scenarioId = rec.scenarioId;
        pseudo.client = client;
        pseudo.server = server;
        Models.validateParty("client", client, snapshot);
        Models.validateParty("server", server, snapshot);

        Engine.Result result = Engine.negotiate(snapshot, client, server);
        if (!result.selection.success) {
            throw new ApiException(409, "RENEGOTIATION_FAILED",
                    result.selection.failureCode + ": " + result.selection.failureDetail);
        }
        try {
            int newGen = rec.generation + 1;
            rec.generation = newGen;
            rec.clientJson = Json.write(client);
            rec.serverJson = Json.write(server);
            rec.selectionJson = Json.write(result.selection);
            rec.selectionHash = result.selection.selectionHash();
            rec.token = tokenFor(rec.id, newGen, rec.offerHash + "#g" + newGen);
            rec.candidateJson = Json.write(result.candidates);
            rec.status = "SELECTED";
            rec.failureJson = null;

            int seq = 1;
            Map<String, Object> offerMsg = new LinkedHashMap<>();
            offerMsg.put("phase", "offer");
            offerMsg.put("generation", newGen);
            offerMsg.put("from", "client");
            offerMsg.put("client", Json.MAPPER.convertValue(client, JsonNode.class));
            offerMsg.put("server", Json.MAPPER.convertValue(server, JsonNode.class));
            db.insertMessage(rec.id, newGen, "offer", "C2S", seq++,
                    rawBody == null ? Json.write(offerMsg) : rawBody,
                    Json.write(offerMsg), "新一代次 offer（旧 token 已失效）");
            Map<String, Object> selMsg = new LinkedHashMap<>();
            selMsg.put("phase", "select");
            selMsg.put("generation", newGen);
            selMsg.put("from", "server");
            selMsg.put("selection", jnToMap(result.selection));
            selMsg.put("selectionHash", rec.selectionHash);
            selMsg.put("token", rec.token);
            db.insertMessage(rec.id, newGen, "select", "S2C", seq,
                    Json.write(selMsg), Json.write(selMsg), "新一代次选择已冻结");

            db.updateSession(rec);
            return view(rec, false);
        } catch (SQLException e) {
            throw new ApiException(500, "DB", e.getMessage());
        }
    }

    // ================= 导出 / 导入 =================

    @SuppressWarnings("unchecked")
    public Map<String, Object> exportTranscript(String sessionId) {
        SessionRecord rec = mustSession(sessionId);
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("format", "protocol-negotiation-transcript/1");
            doc.put("exportedAt", java.time.Instant.now().toString());
            doc.put("sessionId", rec.id);
            doc.put("scenarioId", rec.scenarioId);
            doc.put("status", rec.status);
            doc.put("generation", rec.generation);
            doc.put("idemKey", rec.idemKey);
            doc.put("offerHash", rec.offerHash);
            doc.put("offer", Json.MAPPER.readTree(rec.offerJson));
            doc.put("client", Json.MAPPER.readTree(rec.clientJson));
            doc.put("server", Json.MAPPER.readTree(rec.serverJson));
            doc.put("scenarioSnapshot", Json.MAPPER.readTree(rec.scenarioSnapshotJson));
            if (rec.selectionJson != null) {
                doc.put("selection", Json.MAPPER.readTree(rec.selectionJson));
                doc.put("selectionHash", rec.selectionHash);
            }
            if (rec.candidateJson != null) doc.put("candidates", Json.MAPPER.readTree(rec.candidateJson));
            List<Map<String, Object>> msgs = new ArrayList<>();
            for (Db.MessageRow m : db.listMessages(sessionId)) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("generation", m.generation);
                one.put("phase", m.phase);
                one.put("direction", m.direction);
                one.put("seq", m.seq);
                one.put("byteCount", m.byteCount);
                one.put("note", m.note);
                one.put("raw", m.rawText);
                one.put("parsed", Json.MAPPER.readTree(m.parsedJson));
                msgs.add(one);
            }
            doc.put("messages", msgs);
            return doc;
        } catch (Exception e) {
            throw new ApiException(500, "EXPORT", e.getMessage());
        }
    }

    /**
     * 导入 transcript：用内嵌的双方能力与场景快照重新跑协商引擎，
     * 比较重新计算的选择与原选择是否一致（同样选择），并把会话与消息原样落库。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> importTranscript(JsonNode doc, boolean preserveId) {
        String scenarioId = text(doc, "scenarioId", null);
        Db.ScenarioRow row;
        try {
            row = db.findScenario(scenarioId);
        } catch (SQLException e) {
            throw new ApiException(500, "DB", e.getMessage());
        }
        if (row == null) {
            throw new ApiException(400, "SCENARIO_NOT_FOUND",
                    "导入的场景不存在: " + scenarioId + "（请先导入/创建该场景）");
        }
        List<Models.ProtocolVersion> snapshotList = Json.MAPPER.convertValue(
                doc.get("scenarioSnapshot"), new TypeReference<List<Models.ProtocolVersion>>() {});
        Map<String, Models.ProtocolVersion> snapshot = new LinkedHashMap<>();
        for (Models.ProtocolVersion v : snapshotList) snapshot.put(v.version, v);
        Models.Party client = Json.MAPPER.convertValue(doc.get("client"), Models.Party.class);
        Models.Party server = Json.MAPPER.convertValue(doc.get("server"), Models.Party.class);
        Models.OfferRequest pseudo = new Models.OfferRequest();
        pseudo.scenarioId = scenarioId;
        pseudo.client = client;
        pseudo.server = server;
        Models.validateParty("client", client, snapshot);
        Models.validateParty("server", server, snapshot);

        Engine.Result recomputed = Engine.negotiate(snapshot, client, server);
        String originalHash = text(doc, "selectionHash", null);
        String recomputedHash = recomputed.selection.success ? recomputed.selection.selectionHash() : null;
        boolean sameSelection = recomputed.selection.success
                && recomputedHash != null && recomputedHash.equals(originalHash);

        String oldId = text(doc, "sessionId", null);
        String newId = preserveId && oldId != null ? oldId : "ses_" + UUID.randomUUID().toString().substring(0, 12);
        String oldKey = text(doc, "idemKey", null);
        String newKey = (oldKey == null ? "imp_" : "imp_" + oldKey + "_")
                + UUID.randomUUID().toString().substring(0, 8);

        SessionRecord rec = new SessionRecord();
        rec.id = newId;
        rec.scenarioId = scenarioId;
        rec.idemKey = newKey;
        rec.offerHash = text(doc, "offerHash", Json.sha256(Map.of("x", newId)));
        rec.status = text(doc, "status", "SELECTED");
        rec.generation = doc.path("generation").asInt(1);
        rec.clientJson = Json.write(client);
        rec.serverJson = Json.write(server);
        rec.scenarioSnapshotJson = Json.writeCanonical(snapshotList);
        rec.offerJson = doc.has("offer") ? Json.write(doc.get("offer")) : "{}";
        if (doc.has("selection")) {
            rec.selectionJson = Json.write(doc.get("selection"));
            rec.selectionHash = originalHash;
        }
        rec.token = doc.path("token").asText(null);
        if (rec.token == null) rec.token = tokenFor(newId, rec.generation, rec.offerHash);
        rec.candidateJson = doc.has("candidates") ? Json.write(doc.get("candidates"))
                : Json.write(recomputed.candidates);
        try {
            if (db.findSessionById(newId) != null) newId = newId + "_" + UUID.randomUUID().toString().substring(0, 4);
            rec.id = newId;
            db.insertSession(rec);
            if (doc.has("messages")) {
                for (JsonNode m : doc.get("messages")) {
                    String raw = m.path("raw").asText("");
                    db.insertMessage(newId, m.path("generation").asInt(1),
                            text(m, "phase", "offer"), text(m, "direction", "C2S"),
                            m.path("seq").asInt(1), raw,
                            m.has("parsed") ? Json.write(m.get("parsed")) : raw,
                            text(m, "note", "导入消息"));
                }
            }
        } catch (SQLException e) {
            throw new ApiException(500, "DB", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("importedSessionId", newId);
        out.put("sameSelection", sameSelection);
        out.put("originalHash", originalHash);
        out.put("recomputedHash", recomputedHash);
        if (!sameSelection) {
            out.put("recomputedFailure", Map.of(
                    "code", String.valueOf(recomputed.selection.failureCode),
                    "detail", String.valueOf(recomputed.selection.failureDetail)));
        }
        return out;
    }

    // ================= 查询视图 =================

    public SessionView view(SessionRecord rec, boolean idempotentReplay) throws SQLException {
        SessionView v = new SessionView();
        v.sessionId = rec.id;
        v.scenarioId = rec.scenarioId;
        v.idemKey = rec.idemKey;
        v.status = rec.status;
        v.generation = rec.generation;
        v.offerHash = rec.offerHash;
        v.idempotentReplay = idempotentReplay;
        v.client = node(rec.clientJson);
        v.server = node(rec.serverJson);
        v.scenarioSnapshot = node(rec.scenarioSnapshotJson);
        if (rec.selectionJson != null) {
            v.selection = node(rec.selectionJson);
            v.selectionHash = rec.selectionHash;
            v.token = rec.token;
        }
        if (rec.candidateJson != null) v.candidates = node(rec.candidateJson);
        if (rec.failureJson != null) v.failure = node(rec.failureJson);
        if (rec.activeResultJson != null) v.activeResult = node(rec.activeResultJson);
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (Db.MessageRow m : db.listMessages(rec.id)) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("generation", m.generation);
            one.put("phase", m.phase);
            one.put("direction", m.direction);
            one.put("seq", m.seq);
            one.put("byteCount", m.byteCount);
            one.put("note", m.note);
            one.put("rawText", m.rawText);
            one.put("parsed", node(m.parsedJson));
            msgs.add(one);
        }
        v.messages = msgs;
        return v;
    }

    private static com.fasterxml.jackson.databind.JsonNode node(String json) {
        try {
            return Json.MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public SessionView getView(String sessionId) {
        SessionRecord rec = mustSession(sessionId);
        try {
            return view(rec, false);
        } catch (SQLException e) {
            throw new ApiException(500, "DB", e.getMessage());
        }
    }

    public List<SessionView> listViews() {
        try {
            List<SessionView> out = new ArrayList<>();
            for (SessionRecord rec : db.listSessions()) out.add(view(rec, false));
            return out;
        } catch (SQLException e) {
            throw new ApiException(500, "DB", e.getMessage());
        }
    }

    private SessionRecord mustSession(String id) {
        try {
            SessionRecord rec = db.findSessionById(id);
            if (rec == null) throw new ApiException(404, "SESSION_NOT_FOUND", "会话不存在: " + id);
            return rec;
        } catch (SQLException e) {
            throw new ApiException(500, "DB", e.getMessage());
        }
    }

    private int nextSeq(String sessionId, int generation) throws SQLException {
        int max = 0;
        for (Db.MessageRow m : db.listMessages(sessionId)) {
            if (m.generation == generation) max = Math.max(max, m.seq);
        }
        return max + 1;
    }

    private Map<String, Object> jnToMap(Object o) {
        return Json.MAPPER.convertValue(o, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private static String abbrev(String s) {
        if (s == null) return "null";
        return s.length() <= 10 ? s : s.substring(0, 10) + "…";
    }

    static String text(JsonNode node, String field, String dflt) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return dflt;
        return node.get(field).asText();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
