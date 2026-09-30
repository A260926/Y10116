package negotiator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 纯函数式协商引擎：给定已发布版本图与双方能力，按显式降级边逐候选评估。
 * 固定规则：扩展优先级数字小者胜，并列按扩展名字典序升序。
 * 参数 int 取双方区间交集中靠近协议默认中点的值（无默认时取最大下界），
 * enum 取协议声明顺序中第一个双方都支持的取值。
 */
public final class Engine {

    private Engine() {}

    public static final class RejectedCandidate {
        public String version;
        public String reasonCode;
        public String detail;

        public RejectedCandidate() {}

        public RejectedCandidate(String version, String reasonCode, String detail) {
            this.version = version;
            this.reasonCode = reasonCode;
            this.detail = detail;
        }
    }

    public static final class ParamValue {
        public String type;
        public Long intValue;
        public String enumValue;

        public ParamValue() {}
    }

    public static final class Selection {
        public String version;
        public List<String> caps = new ArrayList<>();
        public List<String> extensions = new ArrayList<>();
        public LinkedHashMap<String, ParamValue> params = new LinkedHashMap<>();
        public List<String> ignoredUnknown = new ArrayList<>();
        public boolean success;
        public String failureCode;
        public String failureDetail;
        public List<RejectedCandidate> rejected = new ArrayList<>();

        public String selectionHash() {
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("version", version);
            compact.put("caps", new ArrayList<>(new TreeSortedSet(caps)));
            compact.put("extensions", new ArrayList<>(new TreeSortedSet(extensions)));
            compact.put("params", paramMap());
            return Json.sha256(compact);
        }

        private LinkedHashMap<String, Object> paramMap() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            params.forEach((k, v) -> {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("type", v.type);
                if (v.intValue != null) one.put("intValue", v.intValue);
                if (v.enumValue != null) one.put("enumValue", v.enumValue);
                out.put(k, one);
            });
            return out;
        }
    }

    /** 输出同时包含每个候选上被跳过/淘汰的扩展与参数，供 UI 展示候选淘汰。 */
    public static final class CandidateReport {
        public String version;
        public boolean accepted;

        public CandidateReport() {}

        public String rejectCode;
        public String rejectDetail;
        public List<String> droppedExtensions = new ArrayList<>();
        public List<String> droppedParams = new ArrayList<>();
        public List<String> ignoredUnknown = new ArrayList<>();
    }

    public static final class Result {
        public Selection selection;
        public List<CandidateReport> candidates = new ArrayList<>();

        public Result() {}
    }

    /** 候选遍历顺序：客户端偏好序为起始，沿该候选版本的显式边继续，已访问不重复。 */
    public static Result negotiate(Map<String, Models.ProtocolVersion> published,
                                   Models.Party client, Models.Party server) {
        Result result = new Result();
        Selection sel = new Selection();
        Set<String> clientVersionSet = new LinkedHashSet<>(client.versions);
        Set<String> serverVersionSet = new LinkedHashSet<>(server.versions);

        Set<String> visited = new HashSet<>();
        List<String> queue = new ArrayList<>(client.versions);
        while (!queue.isEmpty()) {
            String vname = queue.remove(0);
            if (visited.contains(vname)) continue;
            visited.add(vname);

            Models.ProtocolVersion spec = published.get(vname);
            CandidateReport report = new CandidateReport();
            report.version = vname;

            if (spec == null) {
                report.accepted = false;
                report.rejectCode = "VERSION_UNDEFINED";
                report.rejectDetail = "版本未在场景中定义: " + vname;
            } else if (!serverVersionSet.contains(vname)) {
                report.accepted = false;
                report.rejectCode = "SERVER_VERSION_UNSUPPORTED";
                report.rejectDetail = "服务端不支持版本 " + vname;
            } else {
                evaluateVersion(spec, client, server, report, sel);
            }
            result.candidates.add(report);
            if (!report.accepted) {
                sel.rejected.add(new RejectedCandidate(vname, report.rejectCode, report.rejectDetail));
            } else {
                sel.success = true;
                sel.version = vname;
                result.selection = sel;
                return result;
            }
            // 沿显式边把目标加入队尾（保留边声明顺序，稳定遍历）
            if (spec != null) {
                for (Models.Edge edge : spec.downgrade) {
                    if (edge.target != null && !visited.contains(edge.target) && !queue.contains(edge.target)) {
                        queue.add(edge.target);
                    }
                }
            }
        }
        sel.success = false;
        sel.failureCode = "NO_COMMON_VERSION";
        sel.failureDetail = "沿全部显式降级边评估后没有可接受版本";
        result.selection = sel;
        return result;
    }

    private static void evaluateVersion(Models.ProtocolVersion spec, Models.Party client,
                                        Models.Party server, CandidateReport report, Selection sel) {
        Set<String> clientCaps = new HashSet<>(client.caps);
        Set<String> serverCaps = new HashSet<>(server.caps);

        // 必选能力：双方交集
        for (String cap : spec.requiredCaps) {
            boolean ok = clientCaps.contains(cap) && serverCaps.contains(cap);
            if (!ok) {
                report.accepted = false;
                report.rejectCode = "REQUIRED_CAP_MISSING";
                report.rejectDetail = "必选能力 " + cap + " 未被双方共同支持"
                        + (clientCaps.contains(cap) ? "" : "（客户端缺失）")
                        + (serverCaps.contains(cap) ? "" : "（服务端缺失）");
                return;
            }
            if (!sel.caps.contains(cap)) sel.caps.add(cap);
        }

        Set<String> clientExts = new HashSet<>();
        Set<String> serverExts = new HashSet<>();
        for (Models.ExtClaim c : client.extensions) clientExts.add(c.name);
        for (Models.ExtClaim c : server.extensions) serverExts.add(c.name);
        Map<String, Models.Extension> extSpecs = new LinkedHashMap<>();
        for (Models.Extension e : spec.extensions) extSpecs.put(e.name, e);

        // 未知扩展（协议版本未声明）：
        //  - 任一方声明为必选（required 且不可忽略）=> 明确失败
        //  - 声明可忽略 => 不参与选择，但保留在 transcript 的 ignoredUnknown
        Map<String, Models.ExtClaim> unknownClaims = new LinkedHashMap<>();
        for (Models.ExtClaim claim : client.extensions) {
            if (!extSpecs.containsKey(claim.name)) unknownClaims.putIfAbsent(claim.name, claim);
        }
        for (Models.ExtClaim claim : server.extensions) {
            if (!extSpecs.containsKey(claim.name)) {
                Models.ExtClaim existing = unknownClaims.get(claim.name);
                if (existing == null) unknownClaims.put(claim.name, claim);
                else if (claim.required) unknownClaims.put(claim.name, claim);
            }
        }
        for (Models.ExtClaim claim : unknownClaims.values()) {
            if (claim.ignorable) {
                report.ignoredUnknown.add(claim.name + "（声明可忽略，仅保留 transcript）");
                continue;
            }
            report.accepted = false;
            report.rejectCode = "UNKNOWN_REQUIRED_EXTENSION";
            report.rejectDetail = (claim.required ? "必选未知扩展 " : "未声明可忽略的未知扩展 ")
                    + claim.name + " 无法协商（协议版本 " + spec.version + " 未声明）";
            return;
        }

        // 候选扩展：必须双方都支持
        List<Models.Extension> common = new ArrayList<>();
        for (Models.Extension e : spec.extensions) {
            if (clientExts.contains(e.name) && serverExts.contains(e.name)) {
                common.add(e);
            } else {
                report.droppedExtensions.add(e.name + ": 非双方交集（client="
                        + clientExts.contains(e.name) + ", server=" + serverExts.contains(e.name) + "）");
            }
        }

        // 依赖闭包：缺依赖的扩展被淘汰，必选扩展缺依赖直接失败
        Set<String> availableCaps = new HashSet<>(sel.caps);
        // 必选扩展本身也必须在双方交集内，否则必选缺失 -> 失败
        for (Models.Extension e : spec.extensions) {
            if (e.required && !(clientExts.contains(e.name) && serverExts.contains(e.name))) {
                report.accepted = false;
                report.rejectCode = "REQUIRED_EXT_MISSING";
                report.rejectDetail = "必选扩展 " + e.name + " 不在双方交集";
                return;
            }
        }

        // 固定点迭代：依赖满足
        List<Models.Extension> chosen = new ArrayList<>();
        Set<String> chosenNames = new HashSet<>();
        List<Models.Extension> pending = new ArrayList<>(common);
        // 必选扩展优先进入闭包种子
        pending.sort(Comparator.comparingInt((Models.Extension e) -> e.required ? 0 : 1));
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            List<Models.Extension> still = new ArrayList<>();
            for (Models.Extension e : pending) {
                if (chosenNames.contains(e.name)) continue;
                Set<String> providers = new HashSet<>(availableCaps);
                chosen.forEach(c -> providers.add(c.name));
                List<String> missing = e.requires.stream()
                        .filter(dep -> !providers.contains(dep))
                        .toList();
                if (missing.isEmpty()) {
                    chosen.add(e);
                    chosenNames.add(e.name);
                    progressed = true;
                } else {
                    still.add(e);
                }
            }
            pending = still;
        }
        for (Models.Extension e : common) {
            if (!chosenNames.contains(e.name)) {
                report.droppedExtensions.add(e.name + ": 依赖未满足 ("
                        + String.join(", ", e.requires) + ")");
                if (e.required) {
                    report.accepted = false;
                    report.rejectCode = "REQUIRED_EXT_DEPENDENCY";
                    report.rejectDetail = "必选扩展 " + e.name + " 的依赖无法满足";
                    return;
                }
            }
        }

        // 互斥组：同组只保留一个，按 priority 升序、并列按名字升序
        Map<String, List<Models.Extension>> byGroup = new LinkedHashMap<>();
        List<Models.Extension> noGroup = new ArrayList<>();
        for (Models.Extension e : chosen) {
            if (e.group == null || e.group.isBlank()) {
                noGroup.add(e);
            } else {
                byGroup.computeIfAbsent(e.group, k -> new ArrayList<>()).add(e);
            }
        }
        List<Models.Extension> finalExts = new ArrayList<>(noGroup);
        for (Map.Entry<String, List<Models.Extension>> g : byGroup.entrySet()) {
            List<Models.Extension> members = g.getValue();
            members.sort(Comparator.comparingInt((Models.Extension x) -> x.priority)
                    .thenComparing(x -> x.name));
            Models.Extension winner = members.get(0);
            finalExts.add(winner);
            for (int i = 1; i < members.size(); i++) {
                Models.Extension loser = members.get(i);
                report.droppedExtensions.add(loser.name + ": 互斥组 " + g.getKey()
                        + " 中按固定规则让位于 " + winner.name
                        + "（priority " + loser.priority + " vs " + winner.priority + "）");
            }
        }
        finalExts.sort(Comparator.comparing(x -> x.name));
        for (Models.Extension e : finalExts) sel.extensions.add(e.name);
        report.ignoredUnknown.forEach(u -> {
            if (!sel.ignoredUnknown.contains(u)) sel.ignoredUnknown.add(u);
        });

        // 参数范围协商
        for (Map.Entry<String, Models.ParamSpec> ps : spec.params.entrySet()) {
            String name = ps.getKey();
            Models.ParamSpec specParam = ps.getValue();
            Models.PartyParam cp = client.params.get(name);
            Models.PartyParam sp = server.params.get(name);
            if (cp == null || sp == null) {
                report.accepted = false;
                report.rejectCode = "PARAM_NOT_DECLARED";
                report.rejectDetail = "参数 " + name + " 未被双方同时声明";
                return;
            }
            ParamValue value = new ParamValue();
            value.type = specParam.type;
            if ("int".equals(specParam.type)) {
                if (cp.min == null || cp.max == null || sp.min == null || sp.max == null) {
                    report.accepted = false;
                    report.rejectCode = "PARAM_RANGE_INVALID";
                    report.rejectDetail = "参数 " + name + " 一方范围不完整";
                    return;
                }
                long lo = Math.max(Math.max(cp.min, sp.min), specParam.min);
                long hi = Math.min(Math.min(cp.max, sp.max), specParam.max);
                if (lo > hi) {
                    report.droppedParams.add(name + ": 区间无交集（交集下界 "
                            + lo + " > 上界 " + hi + "）");
                    report.accepted = false;
                    report.rejectCode = "PARAM_NO_OVERLAP";
                    report.rejectDetail = "参数 " + name + " 的 int 范围没有交集";
                    return;
                }
                long midpoint = (specParam.min + specParam.max) / 2;
                long pick;
                if (midpoint < lo) pick = lo;
                else if (midpoint > hi) pick = hi;
                else pick = midpoint;
                value.intValue = pick;
            } else {
                Set<String> cs = new LinkedHashSet<>(cp.values == null ? List.of() : cp.values);
                Set<String> ss = new LinkedHashSet<>(sp.values == null ? List.of() : sp.values);
                String picked = null;
                for (String allowed : specParam.values) {
                    if (cs.contains(allowed) && ss.contains(allowed)) {
                        picked = allowed;
                        break;
                    }
                }
                if (picked == null) {
                    report.droppedParams.add(name + ": 枚举取值无交集（client="
                            + cs + ", server=" + ss + "）");
                    report.accepted = false;
                    report.rejectCode = "PARAM_NO_OVERLAP";
                    report.rejectDetail = "参数 " + name + " 的 enum 取值没有交集";
                    return;
                }
                value.enumValue = picked;
            }
            sel.params.put(name, value);
        }
        report.accepted = true;
    }

    private static final class TreeSortedSet extends java.util.TreeSet<String> {
        TreeSortedSet(java.util.Collection<String> c) { super(c); }
    }
}
