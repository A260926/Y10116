package negotiator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** 会话的对外 JSON 视图：双方能力、选择、候选报告、消息流水。 */
public final class SessionView {
    public String sessionId;
    public String scenarioId;
    public String idemKey;
    public String status;
    public int generation;
    public String offerHash;
    public boolean idempotentReplay;
    public JsonNode client;
    public JsonNode server;
    public JsonNode scenarioSnapshot;
    public JsonNode selection;
    public String selectionHash;
    public String token;
    public JsonNode candidates;
    public JsonNode failure;
    public JsonNode activeResult;
    public List<Map<String, Object>> messages;
}
