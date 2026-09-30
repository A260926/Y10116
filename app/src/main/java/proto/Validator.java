package proto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import proto.Models.*;

/** Validates declarations before publishing; rejects malformed configs and cyclic degrade edges. */
public final class Validator {
    private Validator() {}

    public static void validateOnPublish(ScenarioConfig cfg) {
        List<String> errors = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ProtoVersion v : cfg.versions) {
            if (v.version == null || v.version.isBlank())
                errors.add("version with empty name");
            else if (!names.add(v.version))
                errors.add("duplicate version name: " + v.version);
        }
        for (ProtoVersion v : cfg.versions) {
            Set<String> extNames = new HashSet<>();
            for (ExtDecl e : v.extensions) {
                if (!extNames.add(e.name))
                    errors.add("duplicate extension '" + e.name + "' in " + v.version);
                for (String dep : e.requires) {
                    if (v.ext(dep) == null)
                        errors.add("extension '" + e.name + "' in " + v.version + " requires unknown '" + dep + "'");
                }
            }
            for (String edge : v.downTo) {
                if (cfg.version(edge) == null)
                    errors.add(v.version + " degrades to unknown version " + edge);
            }
            for (ParamSpec p : v.params) {
                if ("int".equals(p.type) && p.min > p.max)
                    errors.add("param " + p.name + " in " + v.version + " has min>max");
            }
        }
        // cycle detection across explicit degrade edges (each node may list edges; walk all)
        for (ProtoVersion start : cfg.versions) {
            Set<String> onStack = new LinkedHashSet<>();
            dfs(cfg, start.version, onStack, errors);
        }
        if (!errors.isEmpty())
            throw new Storage.ConflictException("publish rejected:\n - " + String.join("\n - ", errors));
    }

    private static void dfs(ScenarioConfig cfg, String node, Set<String> onStack, List<String> errors) {
        onStack.add(node);
        ProtoVersion pv = cfg.version(node);
        if (pv == null) return;
        for (String edge : pv.downTo) {
            if (cfg.version(edge) == null) continue;
            if (onStack.contains(edge)) {
                if (!errors.contains("cyclic degrade edge involving " + edge))
                    errors.add("cyclic degrade edge involving " + edge);
                continue;
            }
            dfs(cfg, edge, onStack, errors);
        }
        onStack.remove(node);
    }
}
