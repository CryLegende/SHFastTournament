package org.Cry_Legende.sHFastTournament.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * État des tournois en MariaDB, partagé par tous les serveurs : la base fait foi, le bus ne fait que prévenir.
 * Chargée seulement avec SHNetwork (HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml).
 *
 * <p>Tables :</p>
 * <ul>
 *   <li>{@code fasttournament_runs} : un tournoi lancé. {@code is_open} vaut 1 tant qu'il est en cours et porte un index
 *       unique : la base refuse un deuxième tournoi ouvert, quel que soit le serveur qui le lance.</li>
 *   <li>{@code fasttournament_scores} : points par joueur et par tournoi, ajoutés par incréments atomiques.</li>
 *   <li>{@code fasttournament_results} : classement final, écrit une seule fois ; {@code paid} passe à 1 pour le seul
 *       serveur qui donne la récompense.</li>
 *   <li>{@code fasttournament_wins} : victoires (première place) de chaque joueur.</li>
 * </ul>
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal et hors du fil du bus.</p>
 */
public final class TournamentDatabase {

    public static final String RUNNING = "RUNNING";
    public static final String ENDING = "ENDING";
    public static final String DONE = "DONE";
    public static final String STOPPED = "STOPPED";

    /** Palier d'un joueur qui ne reçoit rien (sous le seuil de participation). */
    public static final String NO_TIER = "none";

    private static final String RUNS = "fasttournament_runs";
    private static final String SCORES = "fasttournament_scores";
    private static final String RESULTS = "fasttournament_results";
    private static final String WINS = "fasttournament_wins";

    private static final String CHARSET = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci";

    /** Tournoi lancé. */
    public record Run(String id, String tournament, long startedAt, long endsAt, String status) {
    }

    /** Points d'un joueur, tels que la base les cumule. */
    public record Score(UUID uuid, String name, double score) {
    }

    /** Ligne du classement final. {@code tier} : top1, top2, top3, participation ou {@link #NO_TIER}. */
    public record Result(UUID uuid, String name, int place, double score, String tier, boolean paid) {
    }

    private final DataSource source;

    public TournamentDatabase(DataSource source) {
        this.source = source;
    }

    /** Pool de connexions MariaDB. Le serveur démarre même si la base est absente : chaque appel échoue alors seul. */
    public static TournamentDatabase connect(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHFastTournament");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(3);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        return new TournamentDatabase(new HikariDataSource(hikari));
    }

    public void close() {
        if (source instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // arrêt du serveur
            }
        }
    }

    // ------------------------------------------------------------------ tables

    /** Crée les tables manquantes. Une table déjà là ne demande aucun droit CREATE à l'utilisateur. */
    public void createTables() throws SQLException {
        try (Connection c = source.getConnection(); Statement st = c.createStatement()) {
            ensure(st, RUNS, "CREATE TABLE IF NOT EXISTS " + RUNS + " ("
                + "run_id CHAR(12) NOT NULL, "
                + "tournament VARCHAR(64) NOT NULL, "
                + "started_at BIGINT NOT NULL, "
                + "ends_at BIGINT NOT NULL, "
                + "status VARCHAR(8) NOT NULL, "
                + "is_open TINYINT NULL DEFAULT NULL, "
                + "PRIMARY KEY (run_id), "
                + "CONSTRAINT uq_fasttournament_open UNIQUE (is_open)"
                + ")" + CHARSET);
            ensure(st, SCORES, "CREATE TABLE IF NOT EXISTS " + SCORES + " ("
                + "run_id CHAR(12) NOT NULL, "
                + "uuid CHAR(36) NOT NULL, "
                + "name VARCHAR(32) NOT NULL DEFAULT '', "
                + "score DOUBLE NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (run_id, uuid)"
                + ")" + CHARSET);
            ensure(st, RESULTS, "CREATE TABLE IF NOT EXISTS " + RESULTS + " ("
                + "run_id CHAR(12) NOT NULL, "
                + "uuid CHAR(36) NOT NULL, "
                + "name VARCHAR(32) NOT NULL DEFAULT '', "
                + "place INT NOT NULL, "
                + "score DOUBLE NOT NULL, "
                + "tier VARCHAR(16) NOT NULL, "
                + "paid TINYINT NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (run_id, uuid)"
                + ")" + CHARSET);
            ensure(st, WINS, "CREATE TABLE IF NOT EXISTS " + WINS + " ("
                + "uuid CHAR(36) NOT NULL, "
                + "name VARCHAR(32) NOT NULL DEFAULT '', "
                + "wins INT NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (uuid)"
                + ")" + CHARSET);
        }
    }

    private static void ensure(Statement st, String table, String ddl) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + table + " LIMIT 1")) {
            return;
        } catch (SQLException missing) {
            // la table n'existe pas encore : création ci-dessous
        }
        st.executeUpdate(ddl);
    }

    // ------------------------------------------------------------------ tournois

    /**
     * Ouvre un tournoi. Faux si un autre est déjà ouvert : l'index unique sur {@code is_open} est l'arbitre, donc deux
     * serveurs qui lancent au même instant n'en ouvrent jamais deux.
     */
    public boolean insertRun(String id, String tournament, long startedAt, long endsAt) throws SQLException {
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + RUNS
                 + " (run_id, tournament, started_at, ends_at, status, is_open) VALUES (?, ?, ?, ?, '" + RUNNING + "', 1)")) {
            ps.setString(1, id);
            ps.setString(2, tournament);
            ps.setLong(3, startedAt);
            ps.setLong(4, endsAt);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            String state = e.getSQLState();
            if (state != null && state.startsWith("23")) {
                return false;
            }
            throw e;
        }
    }

    /** Le tournoi ouvert (en cours), ou null. */
    public Run openRun() throws SQLException {
        return findRun("WHERE is_open = 1", null);
    }

    public Run run(String id) throws SQLException {
        return findRun("WHERE run_id = ?", id);
    }

    private Run findRun(String where, String param) throws SQLException {
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT run_id, tournament, started_at, ends_at, status FROM " + RUNS + " " + where)) {
            if (param != null) {
                ps.setString(1, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRun(rs) : null;
            }
        }
    }

    /** Les tournois qui n'ont pas fini leur conclusion (en cours ou en cours de clôture). */
    public List<Run> unfinished() throws SQLException {
        List<Run> out = new ArrayList<>();
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT run_id, tournament, started_at, ends_at, status FROM " + RUNS
                 + " WHERE status IN ('" + RUNNING + "', '" + ENDING + "') ORDER BY started_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(readRun(rs));
            }
        }
        return out;
    }

    private static Run readRun(ResultSet rs) throws SQLException {
        return new Run(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5));
    }

    /** Arrêt par un administrateur, sans récompense. Vrai pour le seul appelant qui arrête le tournoi. */
    public boolean stopRun(String id) throws SQLException {
        return transition(id, RUNNING, STOPPED, true);
    }

    /** Début de la conclusion. Vrai pour le seul appelant qui la fait : les autres ne touchent à rien. */
    public boolean claimEnding(String id) throws SQLException {
        return transition(id, RUNNING, ENDING, true);
    }

    /** Conclusion terminée (classement publié, récompenses données). */
    public boolean markDone(String id) throws SQLException {
        return transition(id, ENDING, DONE, false);
    }

    private boolean transition(String id, String from, String to, boolean release) throws SQLException {
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE " + RUNS + " SET status = ?" + (release ? ", is_open = NULL" : "")
                 + " WHERE run_id = ? AND status = ?")) {
            ps.setString(1, to);
            ps.setString(2, id);
            ps.setString(3, from);
            return ps.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------------ points

    /**
     * Ajoute des points. Chaque ajout est un incrément atomique ({@code score = score + ?}) : deux serveurs qui comptent
     * le même joueur s'additionnent, quel que soit l'ordre. Le lot est écrit en une transaction : il l'est en entier ou pas du tout.
     */
    public void addScores(String runId, Map<UUID, Double> deltas, Map<UUID, String> names) throws SQLException {
        if (deltas.isEmpty()) {
            return;
        }
        try (Connection c = source.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + SCORES + " (run_id, uuid, name, score) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE score = score + VALUES(score), name = COALESCE(NULLIF(VALUES(name), ''), name)")) {
                for (Map.Entry<UUID, Double> entry : deltas.entrySet()) {
                    ps.setString(1, runId);
                    ps.setString(2, entry.getKey().toString());
                    ps.setString(3, names.getOrDefault(entry.getKey(), ""));
                    ps.setDouble(4, entry.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    /** Points de tous les joueurs du tournoi. */
    public List<Score> scores(String runId) throws SQLException {
        List<Score> out = new ArrayList<>();
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, name, score FROM " + SCORES + " WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Score(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getDouble(3)));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ classement et récompenses

    /**
     * Écrit le classement final, une seule fois : une ligne déjà là n'est jamais remplacée. La victoire du premier
     * est comptée dans la même transaction que sa ligne, donc seulement si la ligne vient d'être écrite : relancer
     * la conclusion après une panne ne compte jamais deux victoires. Les joueurs sans récompense sont marqués payés d'office.
     */
    public void saveResults(String runId, Collection<Result> results) throws SQLException {
        if (results.isEmpty()) {
            return;
        }
        try (Connection c = source.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement insert = c.prepareStatement("INSERT IGNORE INTO " + RESULTS
                + " (run_id, uuid, name, place, score, tier, paid) VALUES (?, ?, ?, ?, ?, ?, ?)");
                 PreparedStatement win = c.prepareStatement("INSERT INTO " + WINS + " (uuid, name, wins) VALUES (?, ?, 1) "
                     + "ON DUPLICATE KEY UPDATE wins = wins + 1, name = COALESCE(NULLIF(VALUES(name), ''), name)")) {
                for (Result r : results) {
                    insert.setString(1, runId);
                    insert.setString(2, r.uuid().toString());
                    insert.setString(3, r.name());
                    insert.setInt(4, r.place());
                    insert.setDouble(5, r.score());
                    insert.setString(6, r.tier());
                    insert.setInt(7, NO_TIER.equals(r.tier()) ? 1 : 0);
                    if (insert.executeUpdate() == 1 && r.place() == 1) {
                        win.setString(1, r.uuid().toString());
                        win.setString(2, r.name());
                        win.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    /** Classement final enregistré, du premier au dernier. Vide tant qu'il n'est pas écrit. */
    public List<Result> results(String runId) throws SQLException {
        List<Result> out = new ArrayList<>();
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, name, place, score, tier, paid FROM " + RESULTS
                 + " WHERE run_id = ? ORDER BY place")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Result(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3), rs.getDouble(4),
                        rs.getString(5), rs.getInt(6) != 0));
                }
            }
        }
        return out;
    }

    /**
     * Réserve la récompense d'un joueur. Vrai pour le seul appelant qui doit la donner : tous les autres (un autre
     * serveur, un meneur qui reprend après une panne) reçoivent faux.
     */
    public boolean claimReward(String runId, UUID uuid) throws SQLException {
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE " + RESULTS + " SET paid = 1 WHERE run_id = ? AND uuid = ? AND paid = 0")) {
            ps.setString(1, runId);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------------ victoires

    /** Victoires du joueur (0 sans ligne). */
    public int wins(UUID uuid) throws SQLException {
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT wins FROM " + WINS + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Reprend les victoires de l'ancien wins.yml. Un joueur qui a déjà une ligne n'est pas touché, donc
     * l'importation peut être relancée sans rien compter deux fois.
     *
     * @return {importés, déjà présents}
     */
    public int[] importWins(Map<UUID, Integer> byUuid, Map<UUID, String> names) throws SQLException {
        int imported = 0;
        int present = 0;
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + WINS + " (uuid, name, wins) VALUES (?, ?, ?)")) {
            for (Map.Entry<UUID, Integer> entry : byUuid.entrySet()) {
                if (entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                ps.setString(1, entry.getKey().toString());
                ps.setString(2, names.getOrDefault(entry.getKey(), ""));
                ps.setInt(3, entry.getValue());
                if (ps.executeUpdate() > 0) {
                    imported++;
                } else {
                    present++;
                }
            }
        }
        return new int[]{imported, present};
    }

    // ------------------------------------------------------------------ entretien

    /** Supprime les tournois terminés ou arrêtés dont la fin date d'avant {@code before} (millisecondes), avec leurs points et leur classement. */
    public int purge(long before) throws SQLException {
        String old = "SELECT run_id FROM " + RUNS + " WHERE status IN ('" + DONE + "', '" + STOPPED + "') AND ends_at < ?";
        try (Connection c = source.getConnection()) {
            for (String table : new String[]{SCORES, RESULTS}) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE run_id IN (" + old + ")")) {
                    ps.setLong(1, before);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + RUNS + " WHERE status IN ('" + DONE + "', '" + STOPPED + "') AND ends_at < ?")) {
                ps.setLong(1, before);
                return ps.executeUpdate();
            }
        }
    }
}
