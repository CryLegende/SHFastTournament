package org.Cry_Legende.sHFastTournament;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.Cry_Legende.sHFastTournament.network.NetBus;
import org.Cry_Legende.sHFastTournament.network.TournamentDatabase;
import org.Cry_Legende.sHFastTournament.network.TournamentDatabase.Result;
import org.Cry_Legende.sHFastTournament.network.TournamentDatabase.Run;
import org.Cry_Legende.sHFastTournament.network.TournamentDatabase.Score;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.Cry_Legende.sHFastTournament.network.TournamentDatabase.ENDING;
import static org.Cry_Legende.sHFastTournament.network.TournamentDatabase.NO_TIER;
import static org.Cry_Legende.sHFastTournament.network.TournamentDatabase.RUNNING;
import static org.Cry_Legende.sHFastTournament.network.TournamentDatabase.STOPPED;

/**
 * Un seul tournoi pour tout le réseau. La base ({@link TournamentDatabase}) fait foi : le tournoi en cours, les points
 * de chaque joueur, le classement final et les récompenses déjà données y sont écrits par des opérations atomiques.
 * Le bus ne fait que prévenir les serveurs, qui relisent aussi la base à intervalle régulier au cas où un message se perdrait.
 *
 * <pre>
 * canal   n'importe quel serveur -> tous   start {run, tournament, startedAt, endsAt}
 *                                          stop  {run}
 *                                          end   {run, results: [{u, n, p, s, t}]}
 * </pre>
 *
 * <p>Tous les serveurs, le meneur compris, comptent les actions de leurs joueurs, affichent le même tournoi à la même heure et
 * envoient leurs points à la base ({@link NetworkScoreBook}). Le meneur (tâche {@code shfasttournament} de SHNetwork) décide
 * seul du reste : tirage et délai des tournois automatiques, conclusion (classement, victoire, récompenses). Les commandes
 * {@code start} et {@code stop} marchent depuis n'importe quel serveur : la base arbitre, donc elles ne dépendent pas du meneur.</p>
 *
 * <p>Conclusion, en quatre temps, chacun sans danger s'il est rejoué après une panne :</p>
 * <ol>
 *   <li>le meneur réserve la fin ({@code RUNNING -> ENDING}) quelques secondes après l'heure de fin, le temps que chaque
 *       serveur ait envoyé ses derniers points ;</li>
 *   <li>il écrit le classement complet une seule fois, avec la victoire du premier ;</li>
 *   <li>il publie {@code end} : chaque serveur annonce la fin à ses joueurs ;</li>
 *   <li>il réserve la récompense de chaque joueur ({@code paid = 1}) puis l'envoie par {@code runFor} : le serveur qui gagne
 *       la réservation donne, aucun autre.</li>
 * </ol>
 * <p>Si le meneur tombe en route, le suivant reprend les tournois {@code RUNNING} et {@code ENDING} de la base et termine
 * ce qui ne l'est pas.</p>
 */
final class NetworkTournaments implements Listener {

    /** Réponse à une commande d'administration. */
    enum Outcome {
        STARTED, FORCED, ALREADY_RUNNING, STOPPED, NONE, ERROR
    }

    /** Joueur visé par /tournament custom, connecté sur ce serveur ou sur un autre. */
    record Target(UUID uuid, String name) {
    }

    private static final String TASK = "shfasttournament";
    /** Un serveur qui n'a pas reçu l'annonce de fin ferme son tournoi de lui-même après cette marge, sans classement. */
    private static final long SAFETY_MS = 30_000L;
    private static final long RETRY_TICKS = 200L;
    private static final int MAX_FINALIZE_ATTEMPTS = 30;
    private static final Random RANDOM = new Random();

    private record CachedWins(int wins, long at) {
    }

    /** Tournoi suivi par ce serveur. */
    private record Live(String runId, NetworkScoreBook book, ActiveTournament active, long endsAt, AtomicBoolean flushed) {
    }

    private final SHFastTournament plugin;
    private final TournamentManager manager;
    private final NetBus bus;
    private final TournamentDatabase db;
    private final String channel;

    private volatile NetBus.Leader leader;
    private volatile Live live;
    /** Tournois déjà fermés sur ce serveur : une annonce tardive ne les relance pas. */
    private final Set<String> closedRuns = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cycling = new AtomicBoolean();
    private volatile boolean tablesReady;
    private int idleCycles;

    // Fil principal uniquement
    private BukkitTask cycleTask;
    private BukkitTask schedulerTask;
    private BukkitTask pendingStart;
    private BukkitTask finalizeTask;
    private String finalizeFor;

    private final ConcurrentHashMap<UUID, CachedWins> winsCache = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private volatile long lastDbWarning;

    NetworkTournaments(SHFastTournament plugin, TournamentManager manager, NetBus bus, TournamentDatabase db, String channel) {
        this.plugin = plugin;
        this.manager = manager;
        this.bus = bus;
        this.db = db;
        this.channel = channel;
    }

    // ------------------------------------------------------------------ cycle de vie

    void enable() {
        bus.listen(channel, this::onEvent, true);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        leader = bus.leader(TASK, this::acquired, this::lost);
        long period = Math.max(5L, syncMs() / 50L);
        cycleTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::cycle, 20L, period);
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshWins(player.getUniqueId());
        }
    }

    void disable() {
        if (cycleTask != null) {
            cycleTask.cancel();
        }
        stopScheduler();
        cancelPending();
        cancelFinalize();
        NetBus.Leader current = leader;
        if (current != null) {
            current.release();
        }
        // Les points marqués depuis le dernier envoi partent avec l'arrêt ; le tournoi continue sur les autres serveurs
        Live running = live;
        if (running != null) {
            try {
                running.book().flush(storeFor(running.runId()));
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().warning("Points du tournoi non envoyés à l'arrêt : " + e.getMessage());
            }
        }
        db.close();
    }

    boolean isLeader() {
        NetBus.Leader current = leader;
        return current != null && current.isLeader();
    }

    /** Ce serveur devient meneur : il planifie les tournois automatiques et reprend ce que son prédécesseur n'a pas fini. */
    private void acquired() {
        startScheduler();
        async(this::resumeAsLeader);
    }

    /** Ce serveur n'est plus meneur : il ne planifie ni ne conclut plus rien, sans rien annoncer. */
    private void lost() {
        stopScheduler();
        cancelPending();
        cancelFinalize();
    }

    private long syncMs() {
        return Math.max(250L, plugin.getConfig().getLong("reseau.synchro-ms", 1000L));
    }

    private long graceMs() {
        return Math.max(0L, plugin.getConfig().getLong("reseau.delai-conclusion-secondes", 3L)) * 1000L;
    }

    private int idleEvery() {
        long watch = Math.max(1L, plugin.getConfig().getLong("reseau.veille-secondes", 5L)) * 1000L;
        return (int) Math.max(1L, watch / syncMs());
    }

    private void ensureTables() throws SQLException {
        if (!tablesReady) {
            db.createTables();
            tablesReady = true;
        }
    }

    // ------------------------------------------------------------------ meneur : tournois automatiques

    private void startScheduler() {
        stopScheduler();
        schedulerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void stopScheduler() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
            schedulerTask = null;
        }
    }

    private void cancelPending() {
        if (pendingStart != null) {
            pendingStart.cancel();
            pendingStart = null;
        }
    }

    /** Chaque seconde, sur le meneur : même règles qu'un serveur seul, avec les joueurs de tout le réseau. */
    private void tick() {
        if (!isLeader() || manager.getActiveTournament() != null) {
            return;
        }
        int minimumPlayers = plugin.getConfig().getInt("settings.minimum-players", 10);
        if (bus.online() < minimumPlayers) {
            cancelPending();
            return;
        }
        if (pendingStart == null) {
            int delayMinutes = manager.parseInterval(plugin.getConfig().get("settings.interval", "10"));
            plugin.getLogger().info("Tournoi automatique programmé dans " + delayMinutes + " minute(s).");
            pendingStart = Bukkit.getScheduler().runTaskLater(plugin, this::launchRandom, delayMinutes * 60L * 20L);
        }
    }

    private void launchRandom() {
        pendingStart = null;
        if (!isLeader()) {
            return;
        }
        List<Tournament> enabled = manager.getTournaments().values().stream()
            .filter(Tournament::isEnabled)
            .collect(Collectors.toList());
        if (enabled.isEmpty()) {
            plugin.getLogger().warning("Aucun tournoi activé dans la configuration !");
            return;
        }
        start(enabled.get(RANDOM.nextInt(enabled.size())), false, null);
    }

    // ------------------------------------------------------------------ commandes d'administration

    /**
     * Lance un tournoi pour tout le réseau. {@code reply} reçoit sur le fil principal {@code STARTED}, ou {@code FORCED} puis
     * {@code STARTED} quand un tournoi en cours a été arrêté, ou {@code ALREADY_RUNNING} / {@code ERROR}.
     */
    void start(Tournament tournament, boolean force, Consumer<Outcome> reply) {
        async(() -> {
            try {
                ensureTables();
                Run open = db.openRun();
                if (open != null) {
                    if (!force) {
                        respond(reply, Outcome.ALREADY_RUNNING);
                        return;
                    }
                    if (db.stopRun(open.id())) {
                        publish("stop", open.id());
                    }
                    respond(reply, Outcome.FORCED);
                }
                long now = System.currentTimeMillis();
                String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                long endsAt = now + tournament.getTime() * 60L * 1000L;
                // Un autre serveur a ouvert un tournoi à la même seconde : l'index unique de la base refuse le deuxième
                if (!db.insertRun(id, tournament.getId(), now, endsAt)) {
                    respond(reply, Outcome.ALREADY_RUNNING);
                    return;
                }
                JsonObject message = new JsonObject();
                message.addProperty("event", "start");
                message.addProperty("run", id);
                message.addProperty("tournament", tournament.getId());
                message.addProperty("startedAt", now);
                message.addProperty("endsAt", endsAt);
                bus.publish(channel, message);
                respond(reply, Outcome.STARTED);
            } catch (SQLException | RuntimeException e) {
                warnDb("Lancement du tournoi impossible", e);
                respond(reply, Outcome.ERROR);
            }
        });
    }

    /** Arrête le tournoi en cours, sans récompense. {@code reply} reçoit {@code STOPPED}, {@code NONE} ou {@code ERROR}. */
    void stop(Consumer<Outcome> reply) {
        async(() -> {
            try {
                ensureTables();
                Run open = db.openRun();
                if (open == null || !db.stopRun(open.id())) {
                    respond(reply, Outcome.NONE);
                    return;
                }
                publish("stop", open.id());
                respond(reply, Outcome.STOPPED);
            } catch (SQLException | RuntimeException e) {
                warnDb("Arrêt du tournoi impossible", e);
                respond(reply, Outcome.ERROR);
            }
        });
    }

    /** Fil principal : joueur visé par /tournament custom, sur ce serveur ou ailleurs. Null s'il n'est connecté nulle part. */
    Target findTarget(String name) {
        Player local = Bukkit.getPlayerExact(name);
        if (local != null) {
            return new Target(local.getUniqueId(), local.getName());
        }
        return bus.find(name).map(found -> new Target(found.uuid(), found.name())).orElse(null);
    }

    private void publish(String event, String runId) {
        JsonObject message = new JsonObject();
        message.addProperty("event", event);
        message.addProperty("run", runId);
        bus.publish(channel, message);
    }

    private void respond(Consumer<Outcome> reply, Outcome outcome) {
        if (reply != null) {
            onMain(() -> reply.accept(outcome));
        }
    }

    // ------------------------------------------------------------------ tous les serveurs : suivre le tournoi

    /** Fil principal : début, arrêt ou fin d'un tournoi, annoncés par le serveur qui l'a décidé. */
    private void onEvent(JsonObject o) {
        try {
            String event = text(o, "event");
            if (event == null) {
                return;
            }
            switch (event) {
                case "start" -> onStart(o);
                case "stop" -> onStop(o);
                case "end" -> onEnd(o);
                default -> {
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Message illisible sur " + channel + " : " + e);
        }
    }

    private void onStart(JsonObject o) {
        String runId = text(o, "run");
        String tournamentId = text(o, "tournament");
        long startedAt = number(o, "startedAt", 0L);
        long endsAt = number(o, "endsAt", 0L);
        if (runId == null || tournamentId == null || startedAt <= 0L || endsAt <= startedAt) {
            return;
        }
        Live current = live;
        if ((current != null && current.runId().equals(runId)) || closedRuns.contains(runId)) {
            return;
        }
        if (endsAt <= System.currentTimeMillis()) {
            return;
        }
        begin(new Run(runId, tournamentId, startedAt, endsAt, RUNNING), true);
    }

    private void onStop(JsonObject o) {
        String runId = text(o, "run");
        if (runId != null) {
            teardown(runId);
        }
    }

    private void onEnd(JsonObject o) {
        String runId = text(o, "run");
        Live current = live;
        // Un serveur qui n'a pas ce tournoi (ou l'a déjà fermé) n'annonce rien
        if (runId == null || current == null || !current.runId().equals(runId)) {
            return;
        }
        List<Result> results = new ArrayList<>();
        JsonElement array = o.get("results");
        if (array != null && array.isJsonArray()) {
            for (JsonElement element : array.getAsJsonArray()) {
                JsonObject r = element.getAsJsonObject();
                results.add(new Result(UUID.fromString(text(r, "u")), text(r, "n"), (int) number(r, "p", 0L),
                    r.get("s").getAsDouble(), text(r, "t"), false));
            }
        }
        current.active().showEnd(results);
        teardown(runId);
    }

    /** Fil principal : ce serveur se met à suivre un tournoi. {@code announce} faux = il le rejoint en cours de route (pas d'annonce de début). */
    private void begin(Run run, boolean announce) {
        Tournament tournament = manager.getTournament(run.tournament());
        if (tournament == null) {
            plugin.getLogger().warning("Tournoi '" + run.tournament() + "' inconnu sur ce serveur : la section 'tournaments' de config.yml doit être la même partout.");
            rememberClosed(run.id());
            return;
        }
        Live previous = live;
        if (previous != null) {
            // Un autre tournoi prend la place de celui-ci (démarrage forcé)
            teardown(previous.runId());
        }
        NetworkScoreBook book = new NetworkScoreBook();
        ActiveTournament active = new ActiveTournament(tournament, book, new Hooks(), run.startedAt(),
            run.endsAt() - run.startedAt(), announce);
        live = new Live(run.id(), book, active, run.endsAt(), new AtomicBoolean());
        manager.setActiveTournament(active);
        active.runTaskTimer(plugin, 20L, 20L);

        if (!announce) {
            // Le joueur connecté avant que ce serveur ne connaisse le tournoi doit être prévenu comme un retardataire
            int delaySeconds = Math.max(0, plugin.getConfig().getInt("settings.join-message-delay", 3));
            for (Player player : Bukkit.getOnlinePlayers()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Live now = live;
                    if (player.isOnline() && now != null && now.active() == active) {
                        active.sendJoinAnnouncement(player);
                    }
                }, delaySeconds * 20L);
            }
        }
        if (isLeader()) {
            cancelPending();
            scheduleFinalize(run.id(), run.endsAt());
        }
        async(this::cycle);
    }

    /** Fil principal : ce serveur cesse de suivre le tournoi, sans rien annoncer. */
    private void teardown(String runId) {
        Live current = live;
        if (current == null || !current.runId().equals(runId)) {
            return;
        }
        live = null;
        current.active().stopTournament();
        if (manager.getActiveTournament() == current.active()) {
            manager.setActiveTournament(null);
        }
        rememberClosed(runId);
        if (runId.equals(finalizeFor)) {
            cancelFinalize();
        }
    }

    private void rememberClosed(String runId) {
        if (closedRuns.size() > 64) {
            closedRuns.clear();
        }
        closedRuns.add(runId);
    }

    /** Appelé chaque seconde par le tournoi une fois son heure de fin passée. */
    private final class Hooks implements ActiveTournament.NetworkRun {

        @Override
        public void timeUp(ActiveTournament tournament) {
            Live current = live;
            if (current == null || current.active() != tournament) {
                tournament.stopTournament();
                return;
            }
            // Dernier envoi des points tout de suite, sans attendre le prochain tour
            if (current.flushed().compareAndSet(false, true)) {
                async(NetworkTournaments.this::cycle);
            }
            if (System.currentTimeMillis() > current.endsAt() + graceMs() + SAFETY_MS) {
                plugin.getLogger().warning("Fin du tournoi non annoncée par le meneur : tournoi fermé sur ce serveur, sans classement.");
                teardown(current.runId());
            }
        }
    }

    // ------------------------------------------------------------------ synchronisation avec la base

    private NetworkScoreBook.Store storeFor(String runId) {
        return new NetworkScoreBook.Store() {
            @Override
            public void add(Map<UUID, Double> deltas, Map<UUID, String> names) throws SQLException {
                db.addScores(runId, deltas, names);
            }

            @Override
            public List<Score> read() throws SQLException {
                return db.scores(runId);
            }
        };
    }

    /**
     * Fil asynchrone, un tour à la fois. Avec un tournoi : envoie les points de ce serveur, relit ceux de tout le monde et
     * vérifie que le tournoi n'a pas été arrêté (message perdu). Sans tournoi : cherche de temps en temps un tournoi ouvert
     * que ce serveur ne connaîtrait pas, par exemple parce qu'il vient de démarrer.
     */
    private void cycle() {
        if (!cycling.compareAndSet(false, true)) {
            return;
        }
        try {
            ensureTables();
            Live current = live;
            if (current != null) {
                current.book().cycle(storeFor(current.runId()));
                Run row = db.run(current.runId());
                if (row != null && STOPPED.equals(row.status())) {
                    onMain(() -> teardown(current.runId()));
                }
            } else if (++idleCycles >= idleEvery()) {
                idleCycles = 0;
                Run open = db.openRun();
                if (open != null && open.endsAt() > System.currentTimeMillis()) {
                    onMain(() -> {
                        if (live == null && !closedRuns.contains(open.id())) {
                            begin(open, false);
                        }
                    });
                }
            }
        } catch (SQLException | RuntimeException e) {
            warnDb("Synchronisation des tournois impossible", e);
        } finally {
            cycling.set(false);
        }
    }

    // ------------------------------------------------------------------ meneur : conclusion

    private void cancelFinalize() {
        if (finalizeTask != null) {
            finalizeTask.cancel();
            finalizeTask = null;
        }
        finalizeFor = null;
    }

    /** Fil principal, meneur : conclura le tournoi {@code delai-conclusion-secondes} après son heure de fin. */
    private void scheduleFinalize(String runId, long endsAt) {
        cancelFinalize();
        finalizeFor = runId;
        long wait = Math.max(0L, endsAt + graceMs() - System.currentTimeMillis());
        finalizeTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            finalizeTask = null;
            if (isLeader()) {
                async(() -> finalizeRun(runId, 0));
            }
        }, Math.max(1L, wait / 50L));
    }

    /** Asynchrone, au moment d'être meneur : entretien de la base, puis reprise de ce que le meneur précédent n'a pas fini. */
    private void resumeAsLeader() {
        try {
            ensureTables();
            int days = Math.max(1, plugin.getConfig().getInt("reseau.conservation-jours", 14));
            db.purge(System.currentTimeMillis() - days * 86_400_000L);
            for (Run run : db.unfinished()) {
                if (RUNNING.equals(run.status())) {
                    onMain(() -> {
                        if (isLeader()) {
                            scheduleFinalize(run.id(), run.endsAt());
                        }
                    });
                } else {
                    finalizeRun(run.id(), 0);
                }
            }
        } catch (SQLException | RuntimeException e) {
            warnDb("Reprise des tournois impossible", e);
            onMainLater(() -> {
                if (isLeader()) {
                    async(this::resumeAsLeader);
                }
            }, RETRY_TICKS);
        }
    }

    /**
     * Conclut un tournoi, sur le meneur uniquement. Rejouable : ce qui est déjà fait (classement écrit, victoire comptée,
     * récompense réservée) n'est jamais refait, donc un meneur qui reprend après une panne, ou un nouvel essai après une erreur de base,
     * ne donne jamais deux fois la même chose.
     */
    private void finalizeRun(String runId, int attempt) {
        if (!isLeader()) {
            return;
        }
        try {
            Run run = db.run(runId);
            if (run == null) {
                return;
            }
            if (RUNNING.equals(run.status())) {
                long abandonMs = Math.max(1L, plugin.getConfig().getLong("reseau.abandon-apres-minutes", 60L)) * 60_000L;
                if (System.currentTimeMillis() - run.endsAt() > abandonMs) {
                    // Le réseau est resté sans meneur bien après la fin : pas de récompense pour un tournoi de ce passé
                    if (db.stopRun(runId)) {
                        plugin.getLogger().warning("Tournoi " + run.tournament() + " abandonné : terminé depuis plus de " + (abandonMs / 60_000L) + " minutes sans conclusion.");
                        publish("stop", runId);
                    }
                    return;
                }
                if (!db.claimEnding(runId)) {
                    // Un autre serveur l'a réservé, ou un administrateur vient de l'arrêter
                    Run again = db.run(runId);
                    if (again == null || !ENDING.equals(again.status())) {
                        return;
                    }
                }
            } else if (!ENDING.equals(run.status())) {
                return;
            }
            List<Result> results = db.results(runId);
            if (results.isEmpty()) {
                List<Score> scores = db.scores(runId);
                if (!scores.isEmpty()) {
                    db.saveResults(runId, Ranking.rank(scores));
                    results = db.results(runId);
                }
            }
            publishEnd(runId, results);
            pay(run, results);
            db.markDone(runId);
        } catch (SQLException | RuntimeException e) {
            warnDb("Conclusion du tournoi impossible", e);
            if (attempt + 1 < MAX_FINALIZE_ATTEMPTS) {
                onMainLater(() -> {
                    if (isLeader()) {
                        async(() -> finalizeRun(runId, attempt + 1));
                    }
                }, RETRY_TICKS);
            } else {
                plugin.getLogger().severe("Conclusion du tournoi " + runId + " abandonnée après " + MAX_FINALIZE_ATTEMPTS + " essais : le classement reste dans " + "fasttournament_results.");
            }
        }
    }

    private void publishEnd(String runId, List<Result> results) {
        JsonArray array = new JsonArray();
        for (Result r : results) {
            JsonObject o = new JsonObject();
            o.addProperty("u", r.uuid().toString());
            o.addProperty("n", r.name());
            o.addProperty("p", r.place());
            o.addProperty("s", r.score());
            o.addProperty("t", r.tier());
            array.add(o);
        }
        JsonObject message = new JsonObject();
        message.addProperty("event", "end");
        message.addProperty("run", runId);
        message.add("results", array);
        bus.publish(channel, message);
    }

    /** Réserve puis envoie la récompense de chaque joueur : un joueur déjà payé, ou réservé par un autre, est sauté. */
    private void pay(Run run, List<Result> results) throws SQLException {
        FileConfiguration config = plugin.getConfig();
        Tournament tournament = manager.getTournament(run.tournament());
        for (Result r : results) {
            if (r.paid() || NO_TIER.equals(r.tier())) {
                continue;
            }
            if (!db.claimReward(run.id(), r.uuid())) {
                continue;
            }
            String playerName = r.name().isEmpty() ? r.uuid().toString() : r.name();
            boolean dispatched = false;
            boolean failed = false;
            for (String originalCmd : config.getStringList("settings.rewards." + r.tier())) {
                String cmd = originalCmd.trim().replace("%player%", playerName);
                if (cmd.isEmpty()) {
                    continue;
                }
                try {
                    // Exécutée là où se trouve le joueur, ou à sa prochaine connexion s'il est parti
                    bus.runFor(r.uuid().toString(), cmd);
                    dispatched = true;
                } catch (RuntimeException e) {
                    failed = true;
                    plugin.getLogger().warning("Commande de récompense refusée '" + cmd + "' : " + e.getMessage());
                }
            }
            if (dispatched && !failed && tournament != null) {
                int place = r.place();
                onMain(() -> {
                    try {
                        ActiveTournament.logReward(tournament, config, playerName, place);
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Log de récompense impossible pour " + playerName + " : " + e.getMessage());
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------ victoires

    /**
     * Victoires d'un joueur, pour le placeholder : jamais d'attente. La valeur gardée est rendue tout de suite ; si elle est
     * trop vieille, une relecture en base part en arrière-plan.
     */
    int wins(UUID uuid) {
        CachedWins cached = winsCache.get(uuid);
        long ttl = Math.max(1, plugin.getConfig().getInt("reseau.cache-victoires-secondes", 30)) * 1000L;
        if (cached == null || System.currentTimeMillis() - cached.at() > ttl) {
            refreshWins(uuid);
        }
        return cached == null ? 0 : cached.wins();
    }

    private void refreshWins(UUID uuid) {
        if (!loading.add(uuid)) {
            return;
        }
        async(() -> {
            try {
                winsCache.put(uuid, new CachedWins(db.wins(uuid), System.currentTimeMillis()));
            } catch (SQLException | RuntimeException e) {
                warnDb("Victoires illisibles", e);
            } finally {
                loading.remove(uuid);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refreshWins(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        winsCache.remove(event.getPlayer().getUniqueId());
    }

    /** Fil principal : reprend les victoires de wins.yml dans la base ; {@code done} reçoit {importés, déjà présents}, ou null en cas d'échec. */
    void importWins(Map<UUID, Integer> byUuid, Consumer<int[]> done) {
        Map<UUID, String> names = new HashMap<>();
        for (UUID uuid : byUuid.keySet()) {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            if (name != null) {
                names.put(uuid, name);
            }
        }
        async(() -> {
            int[] result = null;
            try {
                ensureTables();
                result = db.importWins(byUuid, names);
            } catch (SQLException | RuntimeException e) {
                warnDb("Importation des victoires impossible", e);
            }
            int[] counts = result;
            onMain(() -> done.accept(counts));
        });
    }

    // ------------------------------------------------------------------ outils

    private void async(Runnable task) {
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } catch (IllegalPluginAccessException ignored) {
            // plugin en cours d'arrêt
        }
    }

    private void onMain(Runnable task) {
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException ignored) {
            // plugin en cours d'arrêt
        }
    }

    private void onMainLater(Runnable task, long ticks) {
        try {
            Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
        } catch (IllegalPluginAccessException ignored) {
            // plugin en cours d'arrêt
        }
    }

    /** Une base qui tombe ne doit pas remplir la console : un avertissement toutes les 30 secondes au plus. */
    private void warnDb(String what, Exception error) {
        long now = System.currentTimeMillis();
        if (now - lastDbWarning > 30_000L) {
            lastDbWarning = now;
            plugin.getLogger().warning(what + " : " + error.getMessage());
        }
    }

    private static String text(JsonObject o, String key) {
        JsonElement element = o.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static long number(JsonObject o, String key, long fallback) {
        JsonElement element = o.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
