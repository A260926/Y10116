package negotiator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 领域模型：协议版本、能力、扩展、参数范围、降级边，以及双方 offer。
 * 全部使用可变 POJO + Jackson 序列化；快照存 JSON 文本，保证发布后不可变。
 */
public final class Models {

    private Models() {}

    /** 可选/必选扩展。priority 数字越小优先级越高；并列时按名字升序（固定规则）。 */
    public static final class Extension {
        public String name;
        public boolean required;
        public boolean ignorable;
        public String group;
        public int priority;
        public List<String> requires = new ArrayList<>();

        public Extension() {}

        public Extension(String name, boolean required, boolean ignorable,
                         String group, int priority, List<String> requires) {
            this.name = name;
            this.required = required;
            this.ignorable = ignorable;
            this.group = group;
            this.priority = priority;
            this.requires = requires == null ? new ArrayList<>() : requires;
        }
    }

    /** 参数范围声明。int 用 min/max，enum 用 values。 */
    public static final class ParamSpec {
        public String type;
        public Long min;
        public Long max;
        public List<String> values = new ArrayList<>();

        public ParamSpec() {}

        public ParamSpec(String type, Long min, Long max, List<String> values) {
            this.type = type;
            this.min = min;
            this.max = max;
            this.values = values == null ? new ArrayList<>() : values;
        }
    }

    /** 显式降级边：从当前版本指向 target。 */
    public static final class Edge {
        public String target;
        public String reason;

        public Edge() {}

        public Edge(String target, String reason) {
            this.target = target;
            this.reason = reason;
        }
    }

    /** 发布后不可变的协议版本声明。 */
    public static final class ProtocolVersion {
        public String version;
        public List<String> requiredCaps = new ArrayList<>();
        public List<Extension> extensions = new ArrayList<>();
        public LinkedHashMap<String, ParamSpec> params = new LinkedHashMap<>();
        public List<Edge> downgrade = new ArrayList<>();

        public ProtocolVersion() {}
    }

    /** 草稿：一个场景下完整的版本集合定义。 */
    public static final class Draft {
        public List<ProtocolVersion> versions = new ArrayList<>();
    }

    /** 一方提交的参数范围。 */
    public static final class PartyParam {
        public Long min;
        public Long max;
        public List<String> values = new ArrayList<>();
    }

    /** 一方对某个扩展的声明；未知扩展（协议版本未定义）用 required/ignorable 标记。 */
    public static final class ExtClaim {
        public String name;
        public boolean required;
        public boolean ignorable;

        public ExtClaim() {}

        public ExtClaim(String name, boolean required, boolean ignorable) {
            this.name = name;
            this.required = required;
            this.ignorable = ignorable;
        }
    }

    /** 一方（客户端或服务端）的能力声明。 */
    public static final class Party {
        public String name;
        public List<String> versions = new ArrayList<>();
        public List<String> caps = new ArrayList<>();
        public List<ExtClaim> extensions = new ArrayList<>();
        public LinkedHashMap<String, PartyParam> params = new LinkedHashMap<>();
    }

    /** 建立会话的 offer 载荷。 */
    public static final class OfferRequest {
        public String idemKey;
        public String scenarioId;
        public Party client;
        public Party server;
        public OfferInjections injections;
    }

    public static final class OfferInjections {
        public boolean crashAfterSelect;
    }

    /** 校验整个草稿；在发布前拒绝循环边与悬空边。 */
    public static List<String> validateDraft(Draft draft) {
        List<String> errors = new ArrayList<>();
        if (draft == null || draft.versions == null) {
            errors.add("草稿为空");
            return errors;
        }
        Map<String, ProtocolVersion> byName = new LinkedHashMap<>();
        Set<String> extNames = new HashSet<>();
        for (ProtocolVersion v : draft.versions) {
            if (v.version == null || v.version.isBlank()) {
                errors.add("存在缺少版本号的协议版本");
                continue;
            }
            if (byName.put(v.version, v) != null) {
                errors.add("版本号重复: " + v.version);
            }
            if (v.requiredCaps == null) v.requiredCaps = new ArrayList<>();
            if (v.extensions == null) v.extensions = new ArrayList<>();
            if (v.params == null) v.params = new LinkedHashMap<>();
            if (v.downgrade == null) v.downgrade = new ArrayList<>();
            Set<String> localExts = new HashSet<>();
            for (Extension e : v.extensions) {
                if (e.name == null || e.name.isBlank()) {
                    errors.add("版本 " + v.version + " 存在无名扩展");
                    continue;
                }
                if (!localExts.add(e.name)) {
                    errors.add("版本 " + v.version + " 扩展重复: " + e.name);
                }
                if (e.requires == null) e.requires = new ArrayList<>();
                extNames.add(e.name);
            }
            for (Map.Entry<String, ParamSpec> p : v.params.entrySet()) {
                ParamSpec spec = p.getValue();
                if (spec == null || spec.type == null) {
                    errors.add("版本 " + v.version + " 参数 " + p.getKey() + " 缺少类型");
                    continue;
                }
                if ("int".equals(spec.type)) {
                    if (spec.min == null || spec.max == null || spec.min > spec.max) {
                        errors.add("版本 " + v.version + " int 参数 " + p.getKey() + " 范围非法");
                    }
                } else if ("enum".equals(spec.type)) {
                    if (spec.values == null || spec.values.isEmpty()) {
                        errors.add("版本 " + v.version + " enum 参数 " + p.getKey() + " 缺少取值");
                    }
                } else {
                    errors.add("版本 " + v.version + " 参数 " + p.getKey() + " 类型不受支持: " + spec.type);
                }
            }
            List<String> targets = v.downgrade.stream().map(x -> x.target).toList();
            if (new HashSet<>(targets).size() != targets.size()) {
                errors.add("版本 " + v.version + " 存在重复降级边");
            }
            if (targets.contains(v.version)) {
                errors.add("版本 " + v.version + " 存在指向自身的降级边（循环）");
            }
        }
        // 依赖只能引用扩展或必选能力（跨版本能力名宽松允许）
        for (ProtocolVersion v : draft.versions) {
            Set<String> capNames = new HashSet<>(v.requiredCaps);
            for (Extension e : v.extensions) {
                for (String dep : e.requires) {
                    if (!extNames.contains(dep) && !capNames.contains(dep)) {
                        errors.add("版本 " + v.version + " 扩展 " + e.name + " 的依赖不存在: " + dep);
                    }
                }
            }
        }
        // 悬空边与循环边（发布前拒绝）
        for (ProtocolVersion v : draft.versions) {
            for (Edge edge : v.downgrade) {
                if (edge.target == null || !byName.containsKey(edge.target)) {
                    errors.add("版本 " + v.version + " 的降级边指向未定义版本: " + edge.target);
                }
            }
        }
        for (String name : byName.keySet()) {
            List<String> cycle = findCycle(name, byName);
            if (cycle != null) {
                errors.add("降级图存在循环: " + String.join(" -> ", cycle) + " -> " + name);
                break;
            }
        }
        return errors;
    }

    /** DFS 三色法检测循环，返回构成循环的节点链。 */
    static List<String> findCycle(String start, Map<String, ProtocolVersion> byName) {
        Set<String> visited = new HashSet<>();
        List<String> stack = new ArrayList<>();
        return dfs(start, byName, visited, new HashSet<>(), stack);
    }

    private static List<String> dfs(String node, Map<String, ProtocolVersion> byName,
                                    Set<String> visited, Set<String> onStack, List<String> stack) {
        if (onStack.contains(node)) {
            int idx = stack.indexOf(node);
            return new ArrayList<>(stack.subList(idx, stack.size()));
        }
        if (visited.contains(node)) return null;
        visited.add(node);
        onStack.add(node);
        stack.add(node);
        ProtocolVersion v = byName.get(node);
        if (v != null) {
            for (Edge edge : v.downgrade) {
                if (edge.target == null || !byName.containsKey(edge.target)) continue;
                List<String> cycle = dfs(edge.target, byName, visited, onStack, stack);
                if (cycle != null) return cycle;
            }
        }
        stack.remove(stack.size() - 1);
        onStack.remove(node);
        return null;
    }

    static void requireValidOffer(OfferRequest req, Map<String, ProtocolVersion> published) {
        if (req == null) throw new Json.BadRequest("缺少请求体");
        if (isBlank(req.idemKey)) throw new Json.BadRequest("缺少幂等键 idemKey");
        if (isBlank(req.scenarioId)) throw new Json.BadRequest("缺少 scenarioId");
        if (req.client == null || req.server == null) throw new Json.BadRequest("缺少 client/server 能力声明");
        validateParty("client", req.client, published);
        validateParty("server", req.server, published);
    }

    static void validateParty(String side, Party p, Map<String, ProtocolVersion> published) {
        if (p.name == null || p.name.isBlank()) p.name = side;
        if (p.versions == null || p.versions.isEmpty()) {
            throw new Json.BadRequest(side + " 必须声明至少一个协议版本");
        }
        for (String v : p.versions) {
            if (!published.containsKey(v)) {
                throw new Json.BadRequest(side + " 声明了场景中不存在的版本: " + v);
            }
        }
        if (new HashSet<>(p.versions).size() != p.versions.size()) {
            throw new Json.BadRequest(side + " 版本列表存在重复");
        }
        for (ExtClaim c : p.extensions) {
            if (c.name == null || c.name.isBlank()) {
                throw new Json.BadRequest(side + " 存在无名扩展声明");
            }
            if (c.required && c.ignorable) {
                throw new Json.BadRequest(side + " 扩展 " + c.name + " 不能同时必选且可忽略");
            }
        }
        if (p.caps == null) p.caps = new ArrayList<>();
        if (p.extensions == null) p.extensions = new ArrayList<>();
        if (p.params == null) p.params = new LinkedHashMap<>();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
