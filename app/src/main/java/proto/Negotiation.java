package proto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import proto.Models.*;

/** Pure negotiation engine: picks a version + extensions + params deterministically. */
public final class Negotiation {
    private Negotiation() {}

    public static final class Rejection {
        public String version;
        public String reason;
        Rejection(String version, String reason) { this.version = version; this.reason = reason; }
        Json.Value toJson() {
            return Json.Value.obj().put("version", version).put("reason", reason);
        }
    }

    public static final class ExtOutcome {
        public String name;
        public String status;   // enabled | ignored
        public String note;
        ExtOutcome(String name, String status, String note) {
            this.name = name; this.status = status; this.note = note;
        }
        Json.Value toJson() {
            Json.Value o = Json.Value.obj().put("name", name).put("status", status);
            if (note != null) o.put("note", note);
            return o;
        }
    }

    public static final class Result {
        public boolean success;
        public String failureReason;
        public String chosenVersion;
        public long chosenPriority;
        public List<ExtOutcome> extOutcomes = new ArrayList<>();
        public Map<String, Json.Value> params = new LinkedHashMap<>();
        public List<Rejection> rejections = new ArrayList<>();
        public List<String> visitedPath = new ArrayList<>(); // explicit-edge walk order
        public String tieBreak;

        Json.Value toJson() {
            Json.Value o = Json.Value.obj();
            o.put("success", success);
            if (failureReason != null) o.put("failureReason", failureReason);
            if (chosenVersion != null) {
                o.put("chosenVersion", chosenVersion);
                o.put("chosenPriority", chosenPriority);
            }
            Json.Value ex = Json.Value.arr();
            for (ExtOutcome e : extOutcomes) ex.add(e.toJson());
            o.put("extensions", ex);
            Json.Value ps = Json.Value.obj();
            for (Map.Entry<String, Json.Value> e : params.entrySet())
                ps.asObj().put(e.getKey(), e.getValue());
            o.put("params", ps);
            Json.Value rj = Json.Value.arr();
            for (Rejection r : rejections) rj.add(r.toJson());
            o.put("rejections", rj);
            Json.Value vp = Json.Value.arr();
            for (String s : visitedPath) vp.add(s);
            o.put("visitedPath", vp);
            if (tieBreak != null) o.put("tieBreak", tieBreak);
            return o;
        }
    }

    public static Result run(ScenarioConfig cfg, Offer offer, Set<String> serverCaps) {
        Result out = new Result();

        // Mandatory unknown extensions (declared required by client but unknown to every
        // server version) cause an explicit failure instead of degradation.
        Set<String> allServerExts = new LinkedHashSet<>();
        for (ProtoVersion pv : cfg.versions) for (ExtDecl e : pv.extensions)
            allServerExts.add(e.name);
        for (String re : offer.requiredExts) {
            if (!allServerExts.contains(re)) {
                out.success = false;
                out.failureReason = "MANDATORY_UNKNOWN_EXTENSION:" + re;
                return out;
            }
        }

        // Candidate order: shared versions by client preference (offer list order).
        List<String> candidates = new ArrayList<>();
        for (String v : offer.versions) {
            if (cfg.version(v) != null) candidates.add(v);
        }

        ProtoVersion chosen = null;
        if (!candidates.isEmpty()) {
            // Degradation walks ONLY explicit edges. Start from the client's most
            // preferred candidate; on failure follow that version's first edge.
            // A different non-edge candidate is never silently used.
            Set<String> seen = new LinkedHashSet<>();
            String current = candidates.get(0);
            while (current != null) {
                if (!seen.add(current)) {
                    out.rejections.add(new Rejection(current, "cycle/already-visited via explicit edges"));
                    break;
                }
                out.visitedPath.add(current);
                ProtoVersion pv = cfg.version(current);
                String reject = evaluate(pv, cfg, offer, serverCaps, out);
                if (reject == null) { chosen = pv; break; }
                out.rejections.add(new Rejection(current, reject));
                List<String> edges = pv.downTo;
                if (edges.isEmpty()) break;
                current = edges.get(0); // first explicit edge, deterministically
                if (cfg.version(current) == null) {
                    out.rejections.add(new Rejection(current, "edge target version not published"));
                    break;
                }
            }
        }

        if (chosen == null) {
            if (out.rejections.isEmpty()) {
                out.rejections.add(new Rejection("-", "no common version between client and server"));
            }
            out.success = false;
            out.failureReason = "NO_NEGOTIABLE_VERSION";
            return out;
        }

        buildOutcome(chosen, cfg, offer, serverCaps, out);
        out.success = true;
        return out;
    }

    /** Returns null when the version is viable, otherwise a rejection reason. */
    private static String evaluate(ProtoVersion pv, ScenarioConfig cfg, Offer offer,
                                   Set<String> serverCaps, Result out) {
        // mandatory capabilities: must be in the intersection of both sides
        for (String cap : pv.requiredCaps) {
            if (!offer.caps.contains(cap)) return "missing client capability: " + cap;
            if (!serverCaps.contains(cap)) return "missing server capability: " + cap;
        }

        // gather extensions the client wants on this version
        Set<String> wanted = new LinkedHashSet<>();
        wanted.addAll(offer.optionalExts);
        wanted.addAll(offer.requiredExts);

        List<String> enabled = new ArrayList<>();
        List<ExtOutcome> tentative = new ArrayList<>();
        for (String name : wanted) {
            ExtDecl decl = pv.ext(name);
            if (decl == null) {
                boolean clientRequired = offer.requiredExts.contains(name);
                if (clientRequired) return "mandatory extension not defined by " + pv.version + ": " + name;
                // optional unknown extension: retain in transcript
                tentative.add(new ExtOutcome(name, "ignored",
                        "unknown optional extension" + (isMarkedIgnorable(offer, name) ? " (declared ignorable)" : "")));
                continue;
            }
            if (offer.requiredExts.contains(name) && !decl.required) {
                // client requires an extension the server only offers as optional: still enable
            }
            enabled.add(name);
        }

        // dependency closure within enabled set
        Set<String> enabledSet = new LinkedHashSet<>(enabled);
        for (String name : new ArrayList<>(enabled)) {
            ExtDecl decl = pv.ext(name);
            for (String dep : decl.requires) {
                ExtDecl depDecl = pv.ext(dep);
                if (depDecl == null) return "dependency not defined by " + pv.version + ": " + name + "->" + dep;
                if (!enabledSet.contains(dep)) {
                    if (offer.requiredExts.contains(name))
                        return "unmet dependency of required extension: " + name + "->" + dep;
                    return "unmet dependency: " + name + "->" + dep;
                }
            }
        }

        // mutual exclusion groups: at most one enabled extension per group
        Map<String, String> groupOwner = new LinkedHashMap<>();
        for (String name : enabled) {
            for (String group : groupsOf(cfg, name)) {
                String owner = groupOwner.get(group);
                if (owner != null) return "mutex group '" + group + "' conflict: " + owner + " vs " + name;
                groupOwner.put(group, name);
            }
        }

        // parameter ranges must intersect on this version
        for (ParamSpec spec : pv.params) {
            String pr = checkParam(spec, offer);
            if (pr != null) return pr;
        }
        return null;
    }

    private static boolean isMarkedIgnorable(Offer offer, String name) {
        return offer.optionalExts.contains(name); // all client optionals are ignorable requests
    }

    private static List<String> groupsOf(ScenarioConfig cfg, String ext) {
        List<String> out = new ArrayList<>();
        for (String g : cfg.mutexGroups) {
            // groups encoded as "g:a,b,c"
            int colon = g.indexOf(':');
            String members = colon >= 0 ? g.substring(colon + 1) : g;
            for (String m : members.split(",")) if (m.trim().equals(ext)) {
                out.add(colon >= 0 ? g.substring(0, colon) : g);
                break;
            }
        }
        return out;
    }

    private static String checkParam(ParamSpec spec, Offer offer) {
        if ("int".equals(spec.type)) {
            // continuous int range: intersection always exists via clamping
            return null;
        }
        String v = offer.paramStrings.get(spec.name);
        if (v == null) return null;
        if (spec.values != null && !spec.values.isEmpty() && !spec.values.contains(v))
            return "param " + spec.name + "=" + v + " not in " + spec.values;
        return null;
    }

    private static void buildOutcome(ProtoVersion chosen, ScenarioConfig cfg, Offer offer,
                                     Set<String> serverCaps, Result out) {
        out.chosenVersion = chosen.version;
        out.chosenPriority = chosen.priority;

        // Deterministic ordering among equally preferred: versions carry a priority;
        // ties were resolved by client preference order (first listed wins). Record it.
        out.tieBreak = "equal priority resolved by client offer order; first match wins";

        Set<String> wanted = new LinkedHashSet<>();
        wanted.addAll(offer.optionalExts);
        wanted.addAll(offer.requiredExts);
        for (String name : wanted) {
            ExtDecl decl = chosen.ext(name);
            if (decl == null) {
                out.extOutcomes.add(new ExtOutcome(name, "ignored", "unknown optional extension retained in transcript"));
                continue;
            }
            String note = decl.required ? "required" : "optional";
            if (offer.requiredExts.contains(name)) note += "; client-requested";
            out.extOutcomes.add(new ExtOutcome(name, "enabled", note));
        }

        // parameters: intersection of client value and server range
        for (ParamSpec spec : chosen.params) {
            if ("int".equals(spec.type)) {
                Long v = offer.paramInts.get(spec.name);
                long lo = Math.max(spec.min, v != null ? v : spec.min);
                long hi = spec.max;
                Json.Value po = Json.Value.obj();
                po.put("client", v != null ? v : spec.min);
                po.put("serverMin", spec.min);
                po.put("serverMax", spec.max);
                po.put("agreed", Math.min(Math.max(lo, spec.min), hi));
                out.params.put(spec.name, po);
            } else {
                String v = offer.paramStrings.get(spec.name);
                String agreed = (v != null && spec.values != null && spec.values.contains(v))
                        ? v : (spec.values != null && !spec.values.isEmpty() ? spec.values.get(0) : null);
                Json.Value po = Json.Value.obj();
                po.put("client", v != null ? Json.Value.str(v) : Json.Value.ofNull());
                Json.Value vs = Json.Value.arr();
                if (spec.values != null) for (String s : spec.values) vs.add(s);
                po.put("serverValues", vs);
                if (agreed != null) po.put("agreed", agreed);
                out.params.put(spec.name, po);
            }
        }
    }
}
