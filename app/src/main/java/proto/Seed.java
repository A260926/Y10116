package proto;

import proto.Models.*;

/** Built-in demo scenario. The seeded offer fails v3 and degrades explicitly to v2. */
public final class Seed {
    private Seed() {}

    public static void run(Storage storage) {
        ScenarioConfig cfg = new ScenarioConfig();

        // mutual exclusion group: encryption and plaintext cannot coexist
        cfg.mutexGroups.add("transport:tls,plain");

        ProtoVersion v1 = new ProtoVersion();
        v1.version = "v1"; v1.priority = 10;
        v1.requiredCaps.add("base");
        ExtDecl plain = new ExtDecl(); plain.name = "plain";
        v1.extensions.add(plain);
        ParamSpec timeoutV1 = new ParamSpec();
        timeoutV1.name = "timeout"; timeoutV1.type = "int";
        timeoutV1.min = 1; timeoutV1.max = 10;
        v1.params.add(timeoutV1);

        ProtoVersion v2 = new ProtoVersion();
        v2.version = "v2"; v2.priority = 20;
        v2.requiredCaps.add("base");
        v2.requiredCaps.add("reliable");
        ExtDecl tls = new ExtDecl(); tls.name = "tls"; tls.required = true;
        ExtDecl compress = new ExtDecl(); compress.name = "compress"; compress.ignorable = true;
        ExtDecl auth = new ExtDecl(); auth.name = "auth";
        auth.requires.add("tls"); // auth depends on tls
        v2.extensions.add(tls); v2.extensions.add(compress); v2.extensions.add(auth);
        ParamSpec timeoutV2 = new ParamSpec();
        timeoutV2.name = "timeout"; timeoutV2.type = "int";
        timeoutV2.min = 1; timeoutV2.max = 30;
        v2.params.add(timeoutV2);
        ParamSpec mode = new ParamSpec();
        mode.name = "mode"; mode.type = "string";
        mode.values = new java.util.ArrayList<>();
        mode.values.add("safe"); mode.values.add("fast");
        v2.params.add(mode);

        ProtoVersion v3 = new ProtoVersion();
        v3.version = "v3"; v3.priority = 30;
        v3.requiredCaps.add("base");
        v3.requiredCaps.add("reliable");
        v3.requiredCaps.add("mux"); // the seeded client lacks this capability
        ExtDecl tls3 = new ExtDecl(); tls3.name = "tls"; tls3.required = true;
        ExtDecl streaming = new ExtDecl(); streaming.name = "streaming";
        streaming.requires.add("tls");
        v3.extensions.add(tls3); v3.extensions.add(streaming);
        ParamSpec timeoutV3 = new ParamSpec();
        timeoutV3.name = "timeout"; timeoutV3.type = "int";
        timeoutV3.min = 1; timeoutV3.max = 60;
        v3.params.add(timeoutV3);
        // explicit degradation edge v3 -> v2
        v3.downTo.add("v2");

        cfg.versions.add(v1); cfg.versions.add(v2); cfg.versions.add(v3);

        long scenarioId = storage.createScenario("demo", "server-A", cfg);
        storage.publish(scenarioId);

        // Seeded client offer: prefers v3 but lacks "mux"; wants tls + compress + auth.
        Offer offer = new Offer();
        offer.clientId = "client-X";
        offer.versions.add("v3"); offer.versions.add("v2"); offer.versions.add("v1");
        offer.caps.add("base"); offer.caps.add("reliable"); // no mux
        offer.requiredExts.add("tls");
        offer.optionalExts.add("compress");
        offer.optionalExts.add("auth");
        offer.paramInts.put("timeout", 45L); // v3 allows 60; v2 clamps to 30
        offer.paramStrings.put("mode", "safe");
        String offerJson = offer.toJson().dump();

        long sid = storage.insertSession(scenarioId, 1, "seed-demo-v3-to-v2",
                offerJson, offerJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        storage.updateToken(sid, "seed-token-gen1");
        storage.addMessage(sid, 1, "offer", "C2S", offerJson, "seed offer");

        Service service = new Service(storage);
        // Drive the negotiation up to select, then stop (simulates a process exit
        // after select is durable but before confirm is sent).
        service.select(sid, Json.Value.obj());
    }
}
