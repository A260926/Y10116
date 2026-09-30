package negotiator;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {

    private Map<String, Models.ProtocolVersion> map(Models.Draft d) {
        Map<String, Models.ProtocolVersion> m = new LinkedHashMap<>();
        for (Models.ProtocolVersion v : d.versions) m.put(v.version, v);
        return m;
    }

    @Test
    void intersectionOfCapsAndExtensions() {
        Models.Draft d = new Models.Draft();
        d.versions = List.of(TestSupport.version("v1",
                List.of("tls", "sni"),
                List.of(TestSupport.ext("alpn", false, null, 5, List.of()),
                        TestSupport.ext("0rtt", false, null, 9, List.of())),
                TestSupport.intSpec("mtu", 1000, 2000), List.of()));
        Models.Party c = TestSupport.party("c", List.of("v1"),
                List.of("tls", "sni", "quic"),
                List.of(TestSupport.claim("alpn"), TestSupport.claim("0rtt"),
                        TestSupport.claimUnknown("client-only", false, true)),
                new LinkedHashMap<>(Map.of("mtu", TestSupport.intParam(1200, 1800))));
        Models.Party s = TestSupport.party("s", List.of("v1"),
                List.of("tls", "sni"),
                List.of(TestSupport.claim("alpn"), TestSupport.claimUnknown("server-only", false, true)),
                new LinkedHashMap<>(Map.of("mtu", TestSupport.intParam(1500, 2200))));
        Engine.Result r = Engine.negotiate(map(d), c, s);
        assertTrue(r.selection.success);
        assertEquals("v1", r.selection.version);
        assertEquals(List.of("tls", "sni"), r.selection.caps);
        assertEquals(List.of("alpn"), r.selection.extensions, "只有双方交集扩展入选");
        assertEquals(1500L, r.selection.params.get("mtu").intValue, "区间交集 [1500,1800] 取协议中点 1500");
        Engine.CandidateReport rep = r.candidates.get(0);
        assertTrue(rep.droppedExtensions.stream().anyMatch(x -> x.contains("0rtt")));
    }

    @Test
    void mutexGroupUsesPriorityThenName() {
        Models.Draft d = new Models.Draft();
        // 两个优先级并列(10)：按固定规则字典序 alpha 胜；bravo 优先级更小则 bravo 胜
        d.versions = List.of(TestSupport.version("v1", List.of(),
                List.of(TestSupport.ext("zeta", false, "g", 10, List.of()),
                        TestSupport.ext("alpha", false, "g", 10, List.of()),
                        TestSupport.ext("bravo", false, "g", 5, List.of())),
                null, List.of()));
        Models.Party c = TestSupport.party("c", List.of("v1"), List.of(),
                List.of(TestSupport.claim("zeta"), TestSupport.claim("alpha"), TestSupport.claim("bravo")), null);
        Models.Party s = TestSupport.party("s", List.of("v1"), List.of(),
                List.of(TestSupport.claim("zeta"), TestSupport.claim("alpha"), TestSupport.claim("bravo")), null);
        Engine.Result r = Engine.negotiate(map(d), c, s);
        assertEquals(List.of("bravo"), r.selection.extensions, "priority 5 最小，bravo 胜");

        Models.Draft d2 = new Models.Draft();
        d2.versions = List.of(TestSupport.version("v1", List.of(),
                List.of(TestSupport.ext("zeta", false, "g", 10, List.of()),
                        TestSupport.ext("alpha", false, "g", 10, List.of())),
                null, List.of()));
        Models.Party c2 = TestSupport.party("c", List.of("v1"), List.of(),
                List.of(TestSupport.claim("zeta"), TestSupport.claim("alpha")), null);
        Models.Party s2 = TestSupport.party("s", List.of("v1"), List.of(),
                List.of(TestSupport.claim("zeta"), TestSupport.claim("alpha")), null);
        Engine.Result r2 = Engine.negotiate(map(d2), c2, s2);
        assertEquals(List.of("alpha"), r2.selection.extensions, "并列时按名字升序 alpha 胜");
        assertTrue(r2.candidates.get(0).droppedExtensions.get(0).contains("让位于 alpha"));
    }

    @Test
    void dependencyDropIsRecordedAndRequiredFails() {
        Models.Draft d = new Models.Draft();
        d.versions = List.of(TestSupport.version("v1", List.of(),
                List.of(TestSupport.ext("opt", false, null, 1, List.of("missing-cap")),
                        TestSupport.ext("req", true, null, 1, List.of("nothing"))),
                null, List.of("v0")));
        Models.Party c = TestSupport.party("c", List.of("v1"), List.of(), List.of(TestSupport.claim("opt"), TestSupport.claim("req")), null);
        Models.Party s = TestSupport.party("s", List.of("v1"), List.of(), List.of(TestSupport.claim("opt"), TestSupport.claim("req")), null);
        Engine.Result r = Engine.negotiate(map(d), c, s);
        assertFalse(r.selection.success);
        assertEquals("REQUIRED_EXT_DEPENDENCY", r.candidates.get(0).rejectCode);
    }

    @Test
    void unknownExtensionsIgnorableKeptButRequiredFails() {
        Models.Draft d = new Models.Draft();
        d.versions = List.of(TestSupport.version("v1", List.of(),
                List.of(TestSupport.ext("known", false, null, 1, List.of())), null, List.of()));
        Models.Party c = TestSupport.party("c", List.of("v1"), List.of(),
                List.of(TestSupport.claim("known"), TestSupport.claimUnknown("x-grease", false, true)), null);
        Models.Party s = TestSupport.party("s", List.of("v1"), List.of(),
                List.of(TestSupport.claim("known"), TestSupport.claimUnknown("x-grease", false, true)), null);
        Engine.Result r = Engine.negotiate(map(d), c, s);
        assertTrue(r.selection.success, "可忽略未知扩展保留在 transcript，不阻断");
        assertTrue(r.selection.ignoredUnknown.get(0).contains("x-grease"));

        Models.Party c2 = TestSupport.party("c2", List.of("v1"), List.of(),
                List.of(TestSupport.claim("known"), TestSupport.claimUnknown("x-must", true, false)), null);
        Engine.Result r2 = Engine.negotiate(map(d), c2, s);
        assertFalse(r2.selection.success);
        assertEquals("UNKNOWN_REQUIRED_EXTENSION", r2.candidates.get(0).rejectCode);
    }

    @Test
    void downgradeOnlyFollowsExplicitEdgesAndRecordsRejects() {
        Models.Draft d = new Models.Draft();
        d.versions = List.of(
                TestSupport.version("v3", List.of("quic"), List.of(), null, List.of("v2")),
                TestSupport.version("v2", List.of("tls"), List.of(), null, List.of("v1")),
                TestSupport.version("v1", List.of("plain"), List.of(), null, List.of()));
        Models.Party c = TestSupport.party("c", List.of("v3", "v2", "v1"), List.of("quic", "tls", "plain"), List.of(), null);
        Models.Party s = TestSupport.party("s", List.of("v3", "v2", "v1"), List.of("tls", "plain"), List.of(), null);
        Engine.Result r = Engine.negotiate(map(d), c, s);
        assertTrue(r.selection.success);
        assertEquals("v2", r.selection.version);
        assertEquals("REQUIRED_CAP_MISSING", r.selection.rejected.get(0).reasonCode);
        assertEquals("v3", r.candidates.get(0).version);
        assertFalse(r.candidates.get(0).accepted);
        assertTrue(r.candidates.get(1).accepted);
    }

    @Test
    void cycleRejectedAtPublishValidation() {
        Models.Draft d = new Models.Draft();
        d.versions = List.of(
                TestSupport.version("a", List.of(), List.of(), null, List.of("b")),
                TestSupport.version("b", List.of(), List.of(), null, List.of("a")));
        List<String> errors = Models.validateDraft(d);
        assertTrue(errors.stream().anyMatch(e -> e.contains("循环")), String.valueOf(errors));
    }

    @Test
    void selfLoopAndDanglingEdgeRejected() {
        Models.Draft d = new Models.Draft();
        d.versions = List.of(TestSupport.version("a", List.of(), List.of(), null, List.of("a", "ghost")));
        List<String> errors = Models.validateDraft(d);
        assertTrue(errors.stream().anyMatch(e -> e.contains("自身")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("未定义版本")));
    }

    @Test
    void paramRangeNoOverlapFailsCandidate() {
        Models.Draft d = new Models.Draft();
        d.versions = List.of(TestSupport.version("v1", List.of(), List.of(),
                TestSupport.intSpec("mtu", 100, 1000), List.of()));
        Models.Party c = TestSupport.party("c", List.of("v1"), List.of(), List.of(),
                new LinkedHashMap<>(Map.of("mtu", TestSupport.intParam(100, 200))));
        Models.Party s = TestSupport.party("s", List.of("v1"), List.of(), List.of(),
                new LinkedHashMap<>(Map.of("mtu", TestSupport.intParam(300, 900))));
        Engine.Result r = Engine.negotiate(map(d), c, s);
        assertFalse(r.selection.success);
        assertEquals("PARAM_NO_OVERLAP", r.candidates.get(0).rejectCode);
    }
}
