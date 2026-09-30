package negotiator;

/** sessions 表的一行；JSON 文本字段按需懒解析。 */
public final class SessionRecord {
    public String id;
    public String scenarioId;
    public String idemKey;
    public String offerHash;
    public String status;
    public int generation;
    public String clientJson;
    public String serverJson;
    public String scenarioSnapshotJson;
    public String offerJson;
    public String selectionJson;
    public String selectionHash;
    public String token;
    public String candidateJson;
    public String failureJson;
    public String activeResultJson;
}
