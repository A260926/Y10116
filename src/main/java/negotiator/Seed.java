package negotiator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** 预置演示数据：TLS 风格分层协议，客户端首选 v3，因服务端缺少必选能力沿显式边降级到 v2。 */
public final class Seed {

    public static final String SCENARIO_ID = "tls-layered";
    public static final String PRESET_IDEM_KEY = "preset-v3-to-v2";

    private Seed() {}

    public static void install(Service service) {
        Models.Draft draft = buildDraft();
        JsonNode scenarioBody = Json.MAPPER.createObjectNode()
                .put("id", SCENARIO_ID)
                .put("name", "TLS 风格分层协议")
                .put("description", "v1/v2/v3 三代协议，含能力交集、互斥扩展组、参数范围与显式降级边");
        ((com.fasterxml.jackson.databind.node.ObjectNode) scenarioBody)
                .set("draft", Json.MAPPER.valueToTree(draft));
        service.createScenario(scenarioBody);
        service.publish(SCENARIO_ID);

        Models.OfferRequest offer = new Models.OfferRequest();
        offer.idemKey = PRESET_IDEM_KEY;
        offer.scenarioId = SCENARIO_ID;
        offer.client = client();
        offer.server = server();

        JsonNode offerBody = Json.MAPPER.valueToTree(offer);
        String raw = Json.write(offerBody);
        SessionView selected = service.offer(Json.read(raw), raw);
        if (!"SELECTED".equals(selected.status) || !"v2".equals(
                selected.selection.path("version").asText())) {
            throw new IllegalStateException("预置协商未按预期降级到 v2: "
                    + selected.status + " " + selected.selection);
        }
        JsonNode confirmBody = Json.MAPPER.createObjectNode()
                .put("sessionId", selected.sessionId)
                .put("selectionHash", selected.selectionHash)
                .put("token", selected.token);
        String cRaw = Json.write(confirmBody);
        SessionView confirmed = service.confirm(Json.read(cRaw), cRaw);
        JsonNode activeBody = Json.MAPPER.createObjectNode()
                .put("sessionId", confirmed.sessionId)
                .put("token", confirmed.token);
        String aRaw = Json.write(activeBody);
        service.activate(Json.read(aRaw), aRaw);
    }

    static Models.Draft buildDraft() {
        Models.Draft draft = new Models.Draft();

        Models.ProtocolVersion v1 = new Models.ProtocolVersion();
        v1.version = "v1";
        v1.requiredCaps = List.of("record-tls");
        v1.params.put("mtu", new Models.ParamSpec("int", 576L, 1500L, null));
        v1.params.put("cipher", new Models.ParamSpec("enum", null, null, List.of("3DES", "AES-CBC")));
        v1.downgrade = List.of();

        Models.ProtocolVersion v2 = new Models.ProtocolVersion();
        v2.version = "v2";
        v2.requiredCaps = List.of("record-tls", "sni");
        v2.extensions = List.of(
                new Models.Extension("session-resumption", false, false, "resumption", 10, List.of()),
                new Models.Extension("0rtt", false, false, "resumption", 20, List.of("session-resumption")),
                new Models.Extension("alpn", false, false, null, 5, List.of()));
        v2.params.put("mtu", new Models.ParamSpec("int", 576L, 9000L, null));
        v2.params.put("cipher", new Models.ParamSpec("enum", null, null,
                List.of("AES-GCM", "CHACHA20", "AES-CBC")));
        v2.downgrade = List.of(new Models.Edge("v1", "legacy 客户端兜底"));

        Models.ProtocolVersion v3 = new Models.ProtocolVersion();
        v3.version = "v3";
        v3.requiredCaps = List.of("record-tls", "sni", "quic-transport");
        v3.extensions = List.of(
                new Models.Extension("quic-0rtt", false, false, "resumption", 10, List.of("quic-transport")),
                new Models.Extension("quic-migration", false, false, null, 10, List.of("quic-transport")));
        v3.params.put("mtu", new Models.ParamSpec("int", 1200L, 9000L, null));
        v3.params.put("cipher", new Models.ParamSpec("enum", null, null, List.of("AES-GCM", "CHACHA20")));
        v3.downgrade = List.of(new Models.Edge("v2", "对端不具备 quic-transport 时回落到 TLS/v2"));

        draft.versions = List.of(v1, v2, v3);
        return draft;
    }

    static Models.Party client() {
        Models.Party p = new Models.Party();
        p.name = "client";
        p.versions = List.of("v3", "v2", "v1");
        p.caps = List.of("record-tls", "sni", "quic-transport");
        p.extensions = List.of(
                new Models.ExtClaim("quic-0rtt", false, true),
                new Models.ExtClaim("quic-migration", false, true),
                new Models.ExtClaim("session-resumption", false, false),
                new Models.ExtClaim("0rtt", false, false),
                new Models.ExtClaim("alpn", false, false),
                new Models.ExtClaim("x-grease-experiment", false, true));
        Models.PartyParam mtu = new Models.PartyParam();
        mtu.min = 1400L;
        mtu.max = 1500L;
        Models.PartyParam cipher = new Models.PartyParam();
        cipher.values = List.of("AES-GCM", "CHACHA20");
        p.params = new java.util.LinkedHashMap<>(Map.of("mtu", mtu, "cipher", cipher));
        return p;
    }

    static Models.Party server() {
        Models.Party p = new Models.Party();
        p.name = "server";
        p.versions = List.of("v3", "v2", "v1");
        // 服务端声称支持 v3 版本号，但缺少 v3 必选能力 quic-transport => v3 被明确拒绝并沿边降级
        p.caps = List.of("record-tls", "sni");
        p.extensions = List.of(
                new Models.ExtClaim("session-resumption", false, false),
                new Models.ExtClaim("0rtt", false, false),
                new Models.ExtClaim("alpn", false, false),
                new Models.ExtClaim("x-grease-experiment", false, true));
        Models.PartyParam mtu = new Models.PartyParam();
        mtu.min = 1280L;
        mtu.max = 1480L;
        Models.PartyParam cipher = new Models.PartyParam();
        cipher.values = List.of("AES-GCM");
        p.params = new java.util.LinkedHashMap<>(Map.of("mtu", mtu, "cipher", cipher));
        return p;
    }
}
