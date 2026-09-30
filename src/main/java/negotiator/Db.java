package negotiator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQLite 持久化：场景、草稿（带 revision）、会话与消息。 */
public final class Db implements AutoCloseable {

    public final Connection cx;

    public Db(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.cx = DriverManager.getConnection("jdbc:sqlite:" + path);
            this.cx.setAutoCommit(true);
            try (Statement st = cx.createStatement()) {
                st.executeUpdate("PRAGMA journal_mode=WAL");
                st.executeUpdate("PRAGMA foreign_keys=ON");
            }
            migrate();
        } catch (Exception e) {
            throw new IllegalStateException("无法打开数据库 " + path, e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = cx.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS scenarios (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT,
                    draft_json TEXT NOT NULL,
                    revision INTEGER NOT NULL DEFAULT 0,
                    published_json TEXT,
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    scenario_id TEXT NOT NULL,
                    idem_key TEXT NOT NULL UNIQUE,
                    offer_hash TEXT NOT NULL,
                    status TEXT NOT NULL,
                    generation INTEGER NOT NULL DEFAULT 1,
                    client_json TEXT NOT NULL,
                    server_json TEXT NOT NULL,
                    scenario_snapshot_json TEXT NOT NULL,
                    offer_json TEXT NOT NULL,
                    selection_json TEXT,
                    selection_hash TEXT,
                    token TEXT,
                    candidate_json TEXT,
                    failure_json TEXT,
                    active_result_json TEXT,
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    generation INTEGER NOT NULL DEFAULT 1,
                    phase TEXT NOT NULL,
                    direction TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    raw_text TEXT NOT NULL,
                    byte_count INTEGER NOT NULL,
                    parsed_json TEXT NOT NULL,
                    note TEXT,
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    FOREIGN KEY(session_id) REFERENCES sessions(id)
                )""");
        }
    }

    // ---------- scenarios ----------

    public void insertScenario(String id, String name, String description, String draftJson) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO scenarios(id,name,description,draft_json,revision) VALUES(?,?,?,?,0)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setString(4, draftJson);
            ps.executeUpdate();
        }
    }

    public static final class ScenarioRow {
        public String id, name, description, draftJson, publishedJson;
        public int revision;
    }

    public ScenarioRow findScenario(String id) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement("SELECT * FROM scenarios WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapScenario(rs) : null;
            }
        }
    }

    public List<ScenarioRow> listScenarios() throws SQLException {
        List<ScenarioRow> out = new ArrayList<>();
        try (PreparedStatement ps = cx.prepareStatement("SELECT * FROM scenarios ORDER BY created_at, id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapScenario(rs));
        }
        return out;
    }

    private ScenarioRow mapScenario(ResultSet rs) throws SQLException {
        ScenarioRow r = new ScenarioRow();
        r.id = rs.getString("id");
        r.name = rs.getString("name");
        r.description = rs.getString("description");
        r.draftJson = rs.getString("draft_json");
        r.publishedJson = rs.getString("published_json");
        r.revision = rs.getInt("revision");
        return r;
    }

    /** 乐观更新草稿；revision 不匹配返回 false。 */
    public boolean updateDraft(String id, String draftJson, int expectedRevision) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(
                "UPDATE scenarios SET draft_json=?, revision=revision+1, updated_at=datetime('now') "
                        + "WHERE id=? AND revision=?")) {
            ps.setString(1, draftJson);
            ps.setString(2, id);
            ps.setInt(3, expectedRevision);
            return ps.executeUpdate() == 1;
        }
    }

    /** 发布：把草稿固化为 published，草稿保留为 Draft 包装；revision +1。 */
    public void publish(String id, String publishedListJson, String draftJson) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(
                "UPDATE scenarios SET published_json=?, draft_json=?, revision=revision+1, "
                        + "updated_at=datetime('now') WHERE id=?")) {
            ps.setString(1, publishedListJson);
            ps.setString(2, draftJson);
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    // ---------- sessions ----------

    public void insertSession(SessionRecord rec) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement("""
            INSERT INTO sessions(id,scenario_id,idem_key,offer_hash,status,generation,
                client_json,server_json,scenario_snapshot_json,offer_json,
                selection_json,selection_hash,token,candidate_json,failure_json,active_result_json)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            bindSession(ps, rec);
            ps.executeUpdate();
        }
    }

    public void updateSession(SessionRecord rec) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement("""
            UPDATE sessions SET status=?, generation=?, selection_json=?, selection_hash=?,
                token=?, candidate_json=?, failure_json=?, active_result_json=?,
                server_json=?, updated_at=datetime('now') WHERE id=?
            """)) {
            ps.setString(1, rec.status);
            ps.setInt(2, rec.generation);
            ps.setString(3, rec.selectionJson);
            ps.setString(4, rec.selectionHash);
            ps.setString(5, rec.token);
            ps.setString(6, rec.candidateJson);
            ps.setString(7, rec.failureJson);
            ps.setString(8, rec.activeResultJson);
            ps.setString(9, rec.serverJson);
            ps.setString(10, rec.id);
            ps.executeUpdate();
        }
    }

    public SessionRecord findSessionById(String id) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement("SELECT * FROM sessions WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapSession(rs) : null;
            }
        }
    }

    public SessionRecord findSessionByIdemKey(String key) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement("SELECT * FROM sessions WHERE idem_key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapSession(rs) : null;
            }
        }
    }

    public List<SessionRecord> listSessions() throws SQLException {
        List<SessionRecord> out = new ArrayList<>();
        try (PreparedStatement ps = cx.prepareStatement("SELECT * FROM sessions ORDER BY created_at, id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapSession(rs));
        }
        return out;
    }

    private void bindSession(PreparedStatement ps, SessionRecord r) throws SQLException {
        ps.setString(1, r.id);
        ps.setString(2, r.scenarioId);
        ps.setString(3, r.idemKey);
        ps.setString(4, r.offerHash);
        ps.setString(5, r.status);
        ps.setInt(6, r.generation);
        ps.setString(7, r.clientJson);
        ps.setString(8, r.serverJson);
        ps.setString(9, r.scenarioSnapshotJson);
        ps.setString(10, r.offerJson);
        ps.setString(11, r.selectionJson);
        ps.setString(12, r.selectionHash);
        ps.setString(13, r.token);
        ps.setString(14, r.candidateJson);
        ps.setString(15, r.failureJson);
        ps.setString(16, r.activeResultJson);
    }

    private SessionRecord mapSession(ResultSet rs) throws SQLException {
        SessionRecord r = new SessionRecord();
        r.id = rs.getString("id");
        r.scenarioId = rs.getString("scenario_id");
        r.idemKey = rs.getString("idem_key");
        r.offerHash = rs.getString("offer_hash");
        r.status = rs.getString("status");
        r.generation = rs.getInt("generation");
        r.clientJson = rs.getString("client_json");
        r.serverJson = rs.getString("server_json");
        r.scenarioSnapshotJson = rs.getString("scenario_snapshot_json");
        r.offerJson = rs.getString("offer_json");
        r.selectionJson = rs.getString("selection_json");
        r.selectionHash = rs.getString("selection_hash");
        r.token = rs.getString("token");
        r.candidateJson = rs.getString("candidate_json");
        r.failureJson = rs.getString("failure_json");
        r.activeResultJson = rs.getString("active_result_json");
        return r;
    }

    public static final class MessageRow {
        public long id;
        public int generation;
        public String phase, direction, rawText, parsedJson, note;
        public int seq, byteCount;
    }

    public void insertMessage(String sessionId, int generation, String phase, String direction,
                              int seq, String rawText, String parsedJson, String note) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO messages(session_id,generation,phase,direction,seq,raw_text,byte_count,parsed_json,note)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, sessionId);
            ps.setInt(2, generation);
            ps.setString(3, phase);
            ps.setString(4, direction);
            ps.setInt(5, seq);
            ps.setString(6, rawText);
            ps.setInt(7, rawText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            ps.setString(8, parsedJson);
            ps.setString(9, note);
            ps.executeUpdate();
        }
    }

    public List<MessageRow> listMessages(String sessionId) throws SQLException {
        List<MessageRow> out = new ArrayList<>();
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT * FROM messages WHERE session_id=? ORDER BY id")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MessageRow m = new MessageRow();
                    m.id = rs.getLong("id");
                    m.generation = rs.getInt("generation");
                    m.phase = rs.getString("phase");
                    m.direction = rs.getString("direction");
                    m.seq = rs.getInt("seq");
                    m.rawText = rs.getString("raw_text");
                    m.byteCount = rs.getInt("byte_count");
                    m.parsedJson = rs.getString("parsed_json");
                    m.note = rs.getString("note");
                    out.add(m);
                }
            }
        }
        return out;
    }

    public int countScenarios() throws SQLException {
        try (Statement st = cx.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM scenarios")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override
    public void close() {
        try { cx.close(); } catch (SQLException ignored) {}
    }

    static <T> T read(String json, Class<T> type) {
        if (json == null) return null;
        try {
            return Json.MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static <T> T readList(String json, TypeReference<T> type) {
        if (json == null) return null;
        try {
            return Json.MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
