package negotiator;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 构造场景与 offer 的测试辅助。 */
final class TestSupport {
    private TestSupport() {}

    static Path tmpDb() {
        try {
            Path p = Files.createTempFile("negotiator-test-", ".db");
            Files.deleteIfExists(p);
            return p;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Service newService(Path dbPath, List<String> crashedIds) {
        Db db = new Db(dbPath.toString());
        Service.CrashPolicy policy = sid -> {
            crashedIds.add(sid);
            throw new Service.CrashSignal(sid);
        };
        return new Service(db, policy);
    }

    static JsonNode scenario(String id, Models.Draft draft) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("name", id + "-name");
        body.put("description", "test scenario");
        body.put("draft", Json.MAPPER.convertValue(draft, JsonNode.class));
        return Json.MAPPER.valueToTree(body);
    }

    static Models.ProtocolVersion version(String name, List<String> caps,
                                          List<Models.Extension> exts,
                                          LinkedHashMap<String, Models.ParamSpec> params,
                                          List<String> downgradeTargets) {
        Models.ProtocolVersion v = new Models.ProtocolVersion();
        v.version = name;
        v.requiredCaps = caps;
        v.extensions = exts;
        v.params = params == null ? new LinkedHashMap<>() : params;
        v.downgrade = downgradeTargets.stream().map(t -> new Models.Edge(t, "test edge")).toList();
        return v;
    }

    static Models.Extension ext(String name, boolean required, String group, int priority, List<String> requires) {
        return new Models.Extension(name, required, false, group, priority, requires);
    }

    static Models.Extension ext(String name, boolean required, boolean ignorable, String group, int priority, List<String> requires) {
        return new Models.Extension(name, required, ignorable, group, priority, requires);
    }

    static Models.PartyParam intParam(long min, long max) {
        Models.PartyParam p = new Models.PartyParam();
        p.min = min; p.max = max;
        return p;
    }

    static Models.PartyParam enumParam(String... values) {
        Models.PartyParam p = new Models.PartyParam();
        p.values = new ArrayList<>(List.of(values));
        return p;
    }

    static Models.Party party(String name, List<String> versions, List<String> caps,
                              List<Models.ExtClaim> exts,
                              LinkedHashMap<String, Models.PartyParam> params) {
        Models.Party p = new Models.Party();
        p.name = name;
        p.versions = versions;
        p.caps = caps;
        p.extensions = exts;
        p.params = params == null ? new LinkedHashMap<>() : params;
        return p;
    }

    static Models.ExtClaim claim(String name) { return new Models.ExtClaim(name, false, false); }
    static Models.ExtClaim claimUnknown(String name, boolean required, boolean ignorable) {
        return new Models.ExtClaim(name, required, ignorable);
    }

    static JsonNode offer(String idem, String scenario, Models.Party client, Models.Party server) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idemKey", idem);
        body.put("scenarioId", scenario);
        body.put("client", Json.MAPPER.convertValue(client, JsonNode.class));
        body.put("server", Json.MAPPER.convertValue(server, JsonNode.class));
        return Json.MAPPER.valueToTree(body);
    }

    static JsonNode offerCrash(String idem, String scenario, Models.Party client, Models.Party server) {
        JsonNode node = offer(idem, scenario, client, server);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                .putObject("injections").put("crashAfterSelect", true);
        return node;
    }

    static LinkedHashMap<String, Models.ParamSpec> intSpec(String key, long min, long max) {
        LinkedHashMap<String, Models.ParamSpec> m = new LinkedHashMap<>();
        m.put(key, new Models.ParamSpec("int", min, max, null));
        return m;
    }

    static LinkedHashMap<String, Models.ParamSpec> enumSpec(String key, String... values) {
        LinkedHashMap<String, Models.ParamSpec> m = new LinkedHashMap<>();
        m.put(key, new Models.ParamSpec("enum", null, null, List.of(values)));
        return m;
    }
}
