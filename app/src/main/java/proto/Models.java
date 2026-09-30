package proto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Domain models: immutable published declarations of a protocol scenario. */
public final class Models {
    private Models() {}

    public static final class ExtDecl {
        public String name;
        public boolean required;       // mandatory extension
        public boolean ignorable;      // unknown peer may ignore it
        public List<String> requires = new ArrayList<>(); // depends on other extensions

        public Json.Value toJson() {
            Json.Value o = Json.Value.obj();
            o.put("name", name);
            o.put("required", required);
            o.put("ignorable", ignorable);
            Json.Value rs = Json.Value.arr();
            for (String r : requires) rs.add(r);
            o.put("requires", rs);
            return o;
        }

        static ExtDecl from(Json.Value v) {
            ExtDecl e = new ExtDecl();
            e.name = v.reqStr("name");
            if (v.get("required") != null) e.required = v.get("required").asBool();
            if (v.get("ignorable") != null) e.ignorable = v.get("ignorable").asBool();
            for (Json.Value r : v.optArr("requires")) e.requires.add(r.asStr());
            return e;
        }
    }

    public static final class ParamSpec {
        public String name;
        public String type = "int";    // int | string
        public long min = 0;
        public long max = Long.MAX_VALUE;
        public List<String> values;    // enum for string type

        public Json.Value toJson() {
            Json.Value o = Json.Value.obj();
            o.put("name", name);
            o.put("type", type);
            if ("int".equals(type)) {
                o.put("min", min);
                o.put("max", max);
            } else {
                Json.Value vs = Json.Value.arr();
                if (values != null) for (String s : values) vs.add(s);
                o.put("values", vs);
            }
            return o;
        }

        static ParamSpec from(Json.Value v) {
            ParamSpec p = new ParamSpec();
            p.name = v.reqStr("name");
            Json.Value t = v.get("type");
            if (t != null) p.type = t.asStr();
            if (v.get("min") != null) p.min = v.get("min").asLong();
            if (v.get("max") != null) p.max = v.get("max").asLong();
            p.values = new ArrayList<>();
            for (Json.Value s : v.optArr("values")) p.values.add(s.asStr());
            return p;
        }
    }

    public static final class ProtoVersion {
        public String version;
        public long priority;
        public List<String> requiredCaps = new ArrayList<>();
        public List<ExtDecl> extensions = new ArrayList<>();
        public List<ParamSpec> params = new ArrayList<>();
        public List<String> downTo = new ArrayList<>(); // explicit degradation edges

        public Json.Value toJson() {
            Json.Value o = Json.Value.obj();
            o.put("version", version);
            o.put("priority", priority);
            Json.Value caps = Json.Value.arr();
            for (String c : requiredCaps) caps.add(c);
            o.put("requiredCaps", caps);
            Json.Value exts = Json.Value.arr();
            for (ExtDecl e : extensions) exts.add(e.toJson());
            o.put("extensions", exts);
            Json.Value ps = Json.Value.arr();
            for (ParamSpec p : params) ps.add(p.toJson());
            o.put("params", ps);
            Json.Value edges = Json.Value.arr();
            for (String d : downTo) edges.add(d);
            o.put("degradeTo", edges);
            return o;
        }

        static ProtoVersion from(Json.Value v) {
            ProtoVersion pv = new ProtoVersion();
            pv.version = v.reqStr("version");
            if (v.get("priority") != null) pv.priority = v.get("priority").asLong();
            for (Json.Value c : v.optArr("requiredCaps")) pv.requiredCaps.add(c.asStr());
            for (Json.Value e : v.optArr("extensions")) pv.extensions.add(ExtDecl.from(e));
            for (Json.Value p : v.optArr("params")) pv.params.add(ParamSpec.from(p));
            for (Json.Value d : v.optArr("degradeTo")) pv.downTo.add(d.asStr());
            return pv;
        }

        ExtDecl ext(String name) {
            for (ExtDecl e : extensions) if (e.name.equals(name)) return e;
            return null;
        }
    }

    /** Complete, immutable declaration published at a revision. */
    public static final class ScenarioConfig {
        public List<String> mutexGroups = new ArrayList<>();
        public List<ProtoVersion> versions = new ArrayList<>();

        public Json.Value toJson() {
            Json.Value o = Json.Value.obj();
            Json.Value g = Json.Value.arr();
            for (String s : mutexGroups) g.add(s);
            o.put("mutexGroups", g);
            Json.Value vs = Json.Value.arr();
            for (ProtoVersion v : versions) vs.add(v.toJson());
            o.put("versions", vs);
            return o;
        }

        static ScenarioConfig from(Json.Value v) {
            ScenarioConfig c = new ScenarioConfig();
            for (Json.Value g : v.optArr("mutexGroups")) c.mutexGroups.add(g.asStr());
            for (Json.Value ver : v.optArr("versions")) c.versions.add(ProtoVersion.from(ver));
            return c;
        }

        public ProtoVersion version(String name) {
            for (ProtoVersion v : versions) if (v.version.equals(name)) return v;
            return null;
        }
    }

    public static final class Offer {
        public String clientId = "client";
        public List<String> versions = new ArrayList<>();   // client-supported versions, preference order
        public List<String> caps = new ArrayList<>();       // client capabilities
        public List<String> requiredExts = new ArrayList<>();
        public List<String> optionalExts = new ArrayList<>();
        public Map<String, Long> paramInts = new LinkedHashMap<>();
        public Map<String, String> paramStrings = new LinkedHashMap<>();

        Json.Value toJson() {
            Json.Value o = Json.Value.obj();
            o.put("clientId", clientId);
            Json.Value vs = Json.Value.arr();
            for (String x : versions) vs.add(x);
            o.put("versions", vs);
            Json.Value cs = Json.Value.arr();
            for (String x : caps) cs.add(x);
            o.put("caps", cs);
            Json.Value rq = Json.Value.arr();
            for (String x : requiredExts) rq.add(x);
            o.put("requiredExts", rq);
            Json.Value op = Json.Value.arr();
            for (String x : optionalExts) op.add(x);
            o.put("optionalExts", op);
            Json.Value pi = Json.Value.obj();
            for (java.util.Map.Entry<String, Long> e : paramInts.entrySet())
                pi.asObj().put(e.getKey(), Json.Value.of(e.getValue()));
            o.put("paramInts", pi);
            Json.Value ps2 = Json.Value.obj();
            for (java.util.Map.Entry<String, String> e : paramStrings.entrySet())
                ps2.asObj().put(e.getKey(), Json.Value.str(e.getValue()));
            o.put("paramStrings", ps2);
            return o;
        }

        static Offer from(Json.Value v) {
            Offer o = new Offer();
            if (v.get("clientId") != null) o.clientId = v.get("clientId").asStr();
            for (Json.Value x : v.optArr("versions")) o.versions.add(x.asStr());
            for (Json.Value x : v.optArr("caps")) o.caps.add(x.asStr());
            for (Json.Value x : v.optArr("requiredExts")) o.requiredExts.add(x.asStr());
            for (Json.Value x : v.optArr("optionalExts")) o.optionalExts.add(x.asStr());
            Json.Value pi = v.get("paramInts");
            if (pi != null) for (Map.Entry<String, Json.Value> e : pi.asObj().entrySet())
                o.paramInts.put(e.getKey(), e.getValue().asLong());
            Json.Value ps = v.get("paramStrings");
            if (ps != null) for (Map.Entry<String, Json.Value> e : ps.asObj().entrySet())
                o.paramStrings.put(e.getKey(), e.getValue().asStr());
            return o;
        }
    }
}
