package proto;

import java.util.List;
import org.junit.jupiter.api.Test;
import proto.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class ValidatorTest {
    private ProtoVersion v(String name) {
        ProtoVersion x = new ProtoVersion();
        x.version = name; return x;
    }

    @Test
    void cyclicDegradeEdgeRejectedBeforePublish() {
        ScenarioConfig cfg = new ScenarioConfig();
        ProtoVersion a = v("v3"); a.downTo.add("v2");
        ProtoVersion b = v("v2"); b.downTo.add("v3"); // cycle
        cfg.versions.add(a); cfg.versions.add(b);
        Storage.ConflictException ex = assertThrows(Storage.ConflictException.class,
                () -> Validator.validateOnPublish(cfg));
        assertTrue(ex.getMessage().contains("cyclic degrade edge"));
    }

    @Test
    void selfLoopRejected() {
        ScenarioConfig cfg = new ScenarioConfig();
        ProtoVersion a = v("v1"); a.downTo.add("v1");
        cfg.versions.add(a);
        assertThrows(Storage.ConflictException.class, () -> Validator.validateOnPublish(cfg));
    }

    @Test
    void acyclicChainAccepted() {
        ScenarioConfig cfg = new ScenarioConfig();
        ProtoVersion c = v("v3"); c.downTo.add("v2");
        ProtoVersion b = v("v2"); b.downTo.add("v1");
        cfg.versions.add(c); cfg.versions.add(b); cfg.versions.add(v("v1"));
        assertDoesNotThrow(() -> Validator.validateOnPublish(cfg));
    }

    @Test
    void edgeToUnknownVersionRejected() {
        ScenarioConfig cfg = new ScenarioConfig();
        ProtoVersion c = v("v3"); c.downTo.add("v9");
        cfg.versions.add(c);
        Storage.ConflictException ex = assertThrows(Storage.ConflictException.class,
                () -> Validator.validateOnPublish(cfg));
        assertTrue(ex.getMessage().contains("unknown version"));
    }
}
