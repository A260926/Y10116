package proto;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import proto.Models.*;

/** SQLite persistence for scenarios (draft + immutable published revisions) and sessions. */
public final class Storage implements AutoCloseable {
    private final Connection db;

    public Storage(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:" + path);
            db.setAutoCommit(true);
            try (Statement st = db.createStatement()) {
                st.executeUpdate("PRAGMA foreign_keys = ON");
            }
            schema();
        } catch (Exception e) {
            throw new RuntimeException("storage init failed: " + e.getMessage(), e);
        }
    }

    private void schema() throws SQLException {
        try (Statement st = db.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS scenarios (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    server_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS scenario_revisions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scenario_id INTEGER NOT NULL REFERENCES scenarios(id),
                    revision INTEGER NOT NULL,
                    config_json TEXT NOT NULL,
                    published_at INTEGER NOT NULL,
                    UNIQUE(scenario_id, revision)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS drafts (
                    scenario_id INTEGER PRIMARY KEY REFERENCES scenarios(id),
                    config_json TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scenario_id INTEGER NOT NULL REFERENCES scenarios(id),
                    revision INTEGER NOT NULL,
                    idem_key TEXT NOT NULL,
                    stage TEXT NOT NULL,
                    generation INTEGER NOT NULL,
                    offer_json TEXT NOT NULL,
                    offer_bytes INTEGER NOT NULL,
                    result_json TEXT,
                    confirm_token TEXT,
                    confirmed_json TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(scenario_id, idem_key)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL REFERENCES sessions(id),
                    generation INTEGER NOT NULL,
                    phase TEXT NOT NULL,
                    direction TEXT NOT NULL,
                    body_json TEXT NOT NULL,
                    byte_len INTEGER NOT NULL,
                    note TEXT,
                    created_at INTEGER NOT NULL
                )""");
        }
    }

    // ---- scenarios ----

    public long createScenario(String name, String serverId, ScenarioConfig initialDraft) {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO scenarios(name, server_id, created_at) VALUES(?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, serverId);
            ps.setLong(3, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                long id = rs.getLong(1);
                saveDraft(id, initialDraft, 1);
                return id;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static final class ScenarioRow {
        public long id; public String name; public String serverId;
        public int revision; public ScenarioConfig config;
    }

    public ScenarioRow getScenarioByName(String name) {
        try (PreparedStatement ps = db.prepareStatement("SELECT id,name,server_id FROM scenarios WHERE name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                ScenarioRow r = new ScenarioRow();
                r.id = rs.getLong(1); r.name = rs.getString(2); r.serverId = rs.getString(3);
                return r;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<ScenarioRow> listScenarios() {
        List<ScenarioRow> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT s.id,s.name,s.server_id,COALESCE(MAX(r.revision),0)
                FROM scenarios s LEFT JOIN scenario_revisions r ON r.scenario_id=s.id
                GROUP BY s.id ORDER BY s.id""")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ScenarioRow r = new ScenarioRow();
                    r.id = rs.getLong(1); r.name = rs.getString(2);
                    r.serverId = rs.getString(3); r.revision = rs.getInt(4);
                    out.add(r);
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    // ---- revisions (immutable published declarations) ----

    public ScenarioConfig publish(long scenarioId) {
        Draft d = getDraft(scenarioId);
        if (d == null) throw new ConflictException("no draft");
        int next = latestRevision(scenarioId) + 1;
        ScenarioConfig cfg = ScenarioConfig.from(Json.parse(d.configJson));
        Validator.validateOnPublish(cfg);
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO scenario_revisions(scenario_id,revision,config_json,published_at) VALUES(?,?,?,?)")) {
            ps.setLong(1, scenarioId);
            ps.setInt(2, next);
            ps.setString(3, d.configJson);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return cfg;
    }

    public int latestRevision(long scenarioId) {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT MAX(revision) FROM scenario_revisions WHERE scenario_id=?")) {
            ps.setLong(1, scenarioId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public ScenarioConfig getPublishedConfig(long scenarioId, int revision) {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT config_json FROM scenario_revisions WHERE scenario_id=? AND revision=?")) {
            ps.setLong(1, scenarioId);
            ps.setInt(2, revision);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return ScenarioConfig.from(Json.parse(rs.getString(1)));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ---- drafts (optimistic locking) ----

    public static final class Draft {
        public String configJson; public int version;
    }

    public Draft getDraft(long scenarioId) {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT config_json,version FROM drafts WHERE scenario_id=?")) {
            ps.setLong(1, scenarioId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Draft d = new Draft();
                d.configJson = rs.getString(1); d.version = rs.getInt(2);
                return d;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void saveDraft(long scenarioId, ScenarioConfig cfg, int expectedVersion) {
        String json = cfg.toJson().dump();
        long now = System.currentTimeMillis();
        try {
            if (getDraft(scenarioId) == null) {
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO drafts(scenario_id,config_json,version,updated_at) VALUES(?,?,1,?)")) {
                    ps.setLong(1, scenarioId); ps.setString(2, json); ps.setLong(3, now);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = db.prepareStatement(
                        "UPDATE drafts SET config_json=?,version=version+1,updated_at=? "
                        + "WHERE scenario_id=? AND version=?")) {
                    ps.setString(1, json); ps.setLong(2, now);
                    ps.setLong(3, scenarioId); ps.setInt(4, expectedVersion);
                    if (ps.executeUpdate() == 0)
                        throw new ConflictException("draft version conflict (expected " + expectedVersion + ")");
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ---- sessions ----

    public static final class SessionRow {
        public long id; public long scenarioId; public int revision; public String idemKey;
        public String stage; public int generation;
        public String offerJson; public int offerBytes;
        public String resultJson; public String confirmToken; public String confirmedJson;
    }

    public SessionRow findSession(long scenarioId, String idemKey) {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id,scenario_id,revision,idem_key,stage,generation,offer_json,offer_bytes,"
              + "result_json,confirm_token,confirmed_json FROM sessions WHERE scenario_id=? AND idem_key=?")) {
            ps.setLong(1, scenarioId); ps.setString(2, idemKey);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? readSession(rs) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public SessionRow getSession(long id) {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id,scenario_id,revision,idem_key,stage,generation,offer_json,offer_bytes,"
              + "result_json,confirm_token,confirmed_json FROM sessions WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? readSession(rs) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private SessionRow readSession(ResultSet rs) throws SQLException {
        SessionRow s = new SessionRow();
        s.id = rs.getLong(1); s.scenarioId = rs.getLong(2); s.revision = rs.getInt(3);
        s.idemKey = rs.getString(4); s.stage = rs.getString(5); s.generation = rs.getInt(6);
        s.offerJson = rs.getString(7); s.offerBytes = rs.getInt(8);
        s.resultJson = rs.getString(9); s.confirmToken = rs.getString(10);
        s.confirmedJson = rs.getString(11);
        return s;
    }

    public long insertSession(long scenarioId, int revision, String idemKey,
                              String offerJson, int offerBytes) {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO sessions(scenario_id,revision,idem_key,stage,generation,offer_json,"
              + "offer_bytes,created_at,updated_at) VALUES(?,?,?, 'offer', 1, ?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            long now = System.currentTimeMillis();
            ps.setLong(1, scenarioId); ps.setInt(2, revision); ps.setString(3, idemKey);
            ps.setString(4, offerJson); ps.setInt(5, offerBytes);
            ps.setLong(6, now); ps.setLong(7, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void saveSelect(long sessionId, String resultJson) {
        try (PreparedStatement ps = db.prepareStatement(
                "UPDATE sessions SET stage='select', result_json=?, updated_at=? WHERE id=?")) {
            ps.setString(1, resultJson); ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void saveConfirm(long sessionId, String confirmedJson) {
        try (PreparedStatement ps = db.prepareStatement(
                "UPDATE sessions SET stage='active', confirmed_json=?, updated_at=? WHERE id=? AND stage='select'")) {
            ps.setString(1, confirmedJson); ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public long beginNewGeneration(long oldSessionId) {
        SessionRow old = getSession(oldSessionId);
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO sessions(scenario_id,revision,idem_key,stage,generation,offer_json,offer_bytes,"
              + "created_at,updated_at) VALUES(?,?,?, 'offer', ?, ?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            long now = System.currentTimeMillis();
            ps.setLong(1, old.scenarioId); ps.setInt(2, old.revision);
            ps.setString(3, old.idemKey + ":gen" + (old.generation + 1));
            ps.setInt(4, old.generation + 1);
            ps.setString(5, old.offerJson); ps.setInt(6, old.offerBytes);
            ps.setLong(7, now); ps.setLong(8, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void addMessage(long sessionId, int generation, String phase, String direction,
                           String bodyJson, String note) {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO messages(session_id,generation,phase,direction,body_json,byte_len,note,created_at) "
              + "VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, sessionId); ps.setInt(2, generation); ps.setString(3, phase);
            ps.setString(4, direction); ps.setString(5, bodyJson);
            ps.setInt(6, bodyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            ps.setString(7, note); ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<Json.Value> listMessages(long sessionId) {
        List<Json.Value> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT generation,phase,direction,body_json,byte_len,note FROM messages "
              + "WHERE session_id=? ORDER BY id")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Json.Value m = Json.Value.obj();
                    m.put("generation", rs.getInt(1));
                    m.put("phase", rs.getString(2));
                    m.put("direction", rs.getString(3));
                    m.put("body", Json.parse(rs.getString(4)));
                    m.put("bytes", rs.getInt(5));
                    String note = rs.getString(6);
                    if (note != null) m.put("note", note);
                    out.add(m);
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public void updateToken(long sessionId, String token) {
        try (PreparedStatement ps = db.prepareStatement(
                "UPDATE sessions SET confirm_token=?, updated_at=? WHERE id=?")) {
            ps.setString(1, token); ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override public void close() {
        try { db.close(); } catch (SQLException ignored) {}
    }

    public static final class ConflictException extends RuntimeException {
        public ConflictException(String m) { super(m); }
    }
}
