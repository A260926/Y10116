package proto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import proto.Models.*;

import static org.junit.jupiter.api.Assertions.*;

class NegotiationTest {

    private static ExtDecl ext(String name, boolean required, boolean ignorable, String... deps) {
        ExtDecl e = new ExtDecl();
        e.name = name; e.required = required; e.ignorable = ignorable;
        e.requires = List.of(deps);
        return e;
    }

    private static ProtoVersion version(String name, long prio, List<String> caps, List<ExtDecl> exts) {
        ProtoVersion v = new ProtoVersion();
        v.version = name; v.priority = prio;
        v.requiredCaps = caps; v.extensions = exts;
        return v;
    }

    private static Offer offer(String... versions) {
        Offer o = new Offer();
        o.versions = List.of(versions);
        o.caps.add("base"); o.caps.add("reliable"); o.caps.add("mux");
        return o;
    }

    private static Set<String> serverCaps(String... c) {
        return new LinkedHashSet<>(List.of(c));
    }

    @Test
    void intersectionPicksHighestSharedVersion() {
        ScenarioConfig cfg = new ScenarioConfig();
        cfg.versions.add(version("v1", 10, List.of("base"), List.of()));
        cfg.versions.add(version("v2", 20, List.of("base", "reliable"), List.of()));
        cfg.versions.add(version("v3", 30, List.of("base", "reliable", "mux"), List.of()));

        Offer o = offer("v3", "v2", "v1");
        Negotiation.Result r = Negotiation.run(cfg, o, serverCaps("base", "reliable", "mux"));
        assertTrue(r.success);
        assertEquals("v3", r.chosenVersion);
    }

    @Test
    void intersectionExcludesVersionsMissingOnOneSide() {
        ScenarioConfig cfg = new ScenarioConfig();
        cfg.versions.add(version("v2", 20, List.of("base", "reliable"), List.of()));
        // client only supports v2,v1 but v1 not published on server
        Offer o = new Offer();
        o.versions = List.of("v1", "v2");
        o.caps.add("base"); o.caps.add("reliable");
        Negotiation.Result r = Negotiation.run(cfg, o, serverCaps("base", "reliable"));
        assertTrue(r.success, () -> r.failureReason);
        assertEquals("v2", r.chosenVersion);
        // v1 was offered by the client but never published server-side; it is not visited
        assertFalse(r.visitedPath.contains("v1"));
    }

    @Test
    void missingMandatoryCapabilityRejectsVersion() {
        ScenarioConfig cfg = new ScenarioConfig();
        cfg.versions.add(version("v1", 10, List.of("base"), List.of()));
        cfg.versions.add(version("v2", 20, List.of("base", "reliable"), List.of()));
        Offer o = offer("v2", "v1");
        o.caps.clear(); o.caps.add("base"); // client lacks reliable
        Negotiation.Result r = Negotiation.run(cfg, o, serverCaps("base", "reliable"));
        // v2 rejected on missing client capability; no edge -> failure
        assertFalse(r.success);
        assertTrue(r.rejections.stream().anyMatch(x -> x.version.equals("v2")
                && x.reason.contains("missing client capability: reliable")));
    }

    @Test
    void mutexGroupConflictRejectsCandidate() {
        ScenarioConfig cfg = new ScenarioConfig();
        cfg.mutexGroups.add("transport:tls,plain");
        ProtoVersion v = version("v1", 10, List.of("base"),
                List.of(ext("tls", false, false), ext("plain", false, false)));
        cfg.versions.add(v);
        Offer o = offer("v1");
        o.optionalExts.add("tls"); o.optionalExts.add("plain");
        Negotiation.Result r = Negotiation.run(cfg, o, serverCaps("base"));
        assertFalse(r.success);
        assertTrue(r.rejections.get(0).reason.contains("mutex group"));
    }

    @Test
    void unknownOptionalExtensionIsRetainedAndIgnored() {
        ScenarioConfig cfg = new ScenarioConfig();
        cfg.versions.add(version("v1", 10, List.of("base"), List.of(ext("tls", true, true))));
        Offer o = offer("v1");
        o.requiredExts.add("tls");
        o.optionalExts.add("future-compress"); // server has never heard of it
        Negotiation.Result r = Negotiation.run(cfg, o, serverCaps("base"));
        assertTrue(r.success);
        assertEquals("ignored", r.extOutcomes.stream()
                .filter(e -> e.name.equals("future-compress")).findFirst().orElseThrow().status);
    }

    @Test
    void mandatoryUnknownExtensionFailsExplicitly() {
        ScenarioConfig cfg = new ScenarioConfig();
        cfg.versions.add(version("v1", 10, List.of("base"), List.of()));
        Offer o = offer("v1");
        o.requiredExts.add("ghost-required");
        Negotiation.Result r = Negotiation.run(cfg, o, serverCaps("base"));
        assertFalse(r.success);
        assertEquals("MANDATORY_UNKNOWN_EXTENSION:ghost-required", r.failureReason);
    }

    @Test
    void unmetDependencyRejectsCandidate() {
        ScenarioConfig cfg = new ScenarioConfig();
        cfg.versions.add(version("v1", 10, List.of("base"),
                List.of(ext("tls", false, false), ext("auth", false, false, "tls"))));
        Offer o = offer("v1");
        o.optionalExts.add("auth"); // wants auth but not its dependency tls
        Negotiation.Result r = Negotiation.run(cfg, o, serverCaps("base"));
        assertFalse(r.success);
        assertTrue(r.rejections.get(0).reason.contains("unmet dependency"));
    }

    @Test
    void equalPriorityTieBreakByClientOrder() {
        ScenarioConfig cfg = new ScenarioConfig();
        cfg.versions.add(version("v2a", 20, List.of("base"), List.of()));
        cfg.versions.add(version("v2b", 20, List.of("base"), List.of()));
        Offer o = offer("v2a", "v2b");
        Negotiation.Result r = Negotiation.run(cfg, o, serverCaps("base"));
        assertTrue(r.success);
        assertEquals("v2a", r.chosenVersion);
        assertNotNull(r.tieBreak);
    }

    @Test
    void degradationFollowsExplicitEdgeOnly() {
        ScenarioConfig cfg = new ScenarioConfig();
        ProtoVersion v1 = version("v1", 10, List.of("base"), List.of());
        ProtoVersion v2 = version("v2", 20, List.of("base", "reliable"), List.of());
        ProtoVersion v3 = version("v3", 30, List.of("base", "reliable", "mux"), List.of());
        v3.downTo.add("v2"); // explicit edge
        cfg.versions.add(v1); cfg.versions.add(v2); cfg.versions.add(v3);

        Offer o = offer("v3", "v2", "v1");
        o.caps.clear(); o.caps.add("base"); o.caps.add("reliable"); // no mux
        Negotiation.Result r = Negotiation.run(cfg, o, serverCaps("base", "reliable", "mux"));
        assertTrue(r.success, () -> r.failureReason);
        assertEquals("v2", r.chosenVersion);
        assertEquals(List.of("v3", "v2"), r.visitedPath);
        assertTrue(r.rejections.stream().anyMatch(x -> x.version.equals("v3")));
    }

    @Test
    void noImplicitDegradationWithoutEdge() {
        ScenarioConfig cfg = new ScenarioConfig();
        cfg.versions.add(version("v1", 10, List.of("base"), List.of()));
        cfg.versions.add(version("v2", 20, List.of("base", "reliable"), List.of()));
        // v2 has NO degrade edge
        Offer o = offer("v2", "v1");
        o.caps.clear(); o.caps.add("base");
        Negotiation.Result r = Negotiation.run(cfg, o, serverCaps("base", "reliable"));
        assertFalse(r.success);
        assertEquals(List.of("v2"), r.visitedPath);
        assertEquals("NO_NEGOTIABLE_VERSION", r.failureReason);
    }
}
