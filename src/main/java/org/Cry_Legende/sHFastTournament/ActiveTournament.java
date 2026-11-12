package org.Cry_Legende.sHFastTournament;

import org.Cry_Legende.sHFastTournament.network.TournamentDatabase.Result;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.ChatColor;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ActiveTournament extends BukkitRunnable {

    /** Tournoi partagé entre serveurs (SHNetwork) : c'est le meneur qui conclut, pas ce serveur. */
    interface NetworkRun {

        /** Appelé chaque seconde une fois l'heure de fin passée, tant que l'annonce du meneur n'est pas arrivée. */
        void timeUp(ActiveTournament tournament);
    }

    private final Tournament tournament;
    // Alimenté depuis le thread de chaque monde (mort d'un mob, dégâts, ramassage), d'où une collection concurrente
    private final ScoreBook scores;
    // Nul pour un tournoi local à ce serveur
    private final NetworkRun network;
    // BossBar personnalisée pour chaque joueur
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();
    private final long startTime;
    private final long endTime;
    private int lastRemainingSeconds = -1;
    // Classement calculé une fois par changement de scores (et non une fois par joueur et par seconde)
    private volatile Map<UUID, Integer> rankCache = new HashMap<>();
    private volatile boolean ranksDirty = false;
    private volatile boolean closed = false;
    // Blocs posés pendant le tournoi : les casser ne rapporte rien (boucle pose/casse, minerai au Toucher de soie)
    private final Set<BlockKey> placedBlocks = ConcurrentHashMap.newKeySet();
    // Positions déjà comptées en BLOCK_PLACE : reposer au même endroit ne rapporte plus
    private final Set<BlockKey> scoredPlacements = ConcurrentHashMap.newKeySet();
    // Tournoi PLACEHOLDER : dernière valeur lue de chaque joueur connecté (nul pour les autres types)
    private final PlaceholderGains gains;
    private boolean placeholderWarned = false;
    // Les commandes de fin ne partent qu'une fois, quelle que soit la façon dont le tournoi se ferme
    private final AtomicBoolean endCommandsRun = new AtomicBoolean();

    /** Position d'un bloc, indépendante de l'objet Block (qui ne doit pas être conservé). */
    record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    /** Tournoi local : démarre maintenant, points gardés sur ce serveur. */
    public ActiveTournament(Tournament tournament) {
        this(tournament, new LocalScoreBook(), null, System.currentTimeMillis(), tournament.getTime() * 60L * 1000L, true);
    }

    /**
     * @param network  non nul pour un tournoi partagé
     * @param announce vrai pour annoncer le début (son et messages) ; faux quand ce serveur rejoint un tournoi déjà commencé
     */
    ActiveTournament(Tournament tournament, ScoreBook scores, NetworkRun network, long startTime, long durationMillis, boolean announce) {
        this.tournament = tournament;
        this.scores = scores;
        this.network = network;
        this.startTime = startTime;
        this.endTime = startTime + durationMillis;
        scores.onChange(() -> ranksDirty = true);
        this.gains = tournament.isPlaceholder() ? new PlaceholderGains() : null;
        if (announce) {
            announceStart();
        }
        runCommands(tournament.getStartCommands(), "début");
        // Valeurs de départ des joueurs déjà connectés : leurs gains comptent dès maintenant
        pollPlaceholder();
    }

    /**
     * Commandes console du tournoi. %seconds% = secondes restantes (pour qu'une commande de début s'arrête d'elle-même
     * à la fin, même si la commande de fin ne part pas), %tournament% = id du tournoi.
     */
    private void runCommands(List<String> commands, String when) {
        String seconds = String.valueOf(Math.max(1, getRemainingSeconds()));
        for (String raw : commands) {
            String cmd = raw.trim().replace("%seconds%", seconds).replace("%tournament%", tournament.getId());
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            if (cmd.isEmpty()) {
                continue;
            }
            try {
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)) {
                    SHFastTournament.getInstance().getLogger().warning("Commande de " + when + " du tournoi " + tournament.getId() + " refusée '" + cmd + "'");
                }
            } catch (RuntimeException e) {
                SHFastTournament.getInstance().getLogger().warning("Commande de " + when + " du tournoi " + tournament.getId() + " en échec '" + cmd + "' : " + e.getMessage());
            }
        }
    }

    /** Commandes de fin, une seule fois (fin normale, arrêt, remplacement par un autre tournoi, arrêt du plugin). */
    private void runEndCommands() {
        if (endCommandsRun.compareAndSet(false, true)) {
            runCommands(tournament.getEndCommands(), "fin");
        }
    }

    /** Tournoi PLACEHOLDER, fil principal : ajoute à chaque joueur connecté ce que son placeholder a gagné depuis la dernière lecture. */
    private void pollPlaceholder() {
        if (gains == null) {
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            if (!placeholderWarned) {
                placeholderWarned = true;
                SHFastTournament.getInstance().getLogger().warning("Tournoi " + tournament.getId() + " de type PLACEHOLDER sans PlaceholderAPI : aucun point ne sera compté.");
            }
            return;
        }
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            double gain = gains.read(player.getUniqueId(), player, PlaceholderHook.parse(player, tournament.getObjective()));
            if (gain > 0) {
                addScore(player, gain);
            }
        }
        gains.retain(online);
    }

    /** Son et messages de début. */
    private void announceStart() {
        String soundStr = SHFastTournament.getInstance().getConfig().getString("settings.sounds.tournament-start", "ENTITY_EXPERIENCE_ORB_PICKUP");
        org.bukkit.Sound startSound = sound(soundStr);
        if (startSound != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(player.getLocation(), startSound, 1.0f, 1.0f);
            }
        } else {
            Bukkit.getLogger().warning("Son invalide pour tournament-start: " + soundStr);
        }
        List<String> startMessages = Messages.getList("tournament-start");
        for (String line : startMessages) {
            String msg = line.replace("%event-name%", tournament.getDisplayName())
                .replace("%duration%", tournament.getTime() + " min")
                .replace("%description%", tournament.getDescription());
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
    }

    /** Son lu en config : ancien nom (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup). */
    private static org.bukkit.Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (org.bukkit.Sound) org.bukkit.Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    public Tournament getTournament() {
        return tournament;
    }

    public int getRemainingSeconds() {
        long currentTime = System.currentTimeMillis();
        int remaining = (int) ((endTime - currentTime) / 1000);
        return Math.max(0, remaining);
    }

    public int getElapsedSeconds() {
        long currentTime = System.currentTimeMillis();
        int elapsed = (int) ((currentTime - startTime) / 1000);
        return Math.max(0, elapsed);
    }

    public int getParticipantCount() {
        return scores.size();
    }

    public Map<UUID, Double> getScoresSnapshot() {
        return scores.snapshot();
    }

    /** Pseudo d'un participant d'un autre serveur, ou null (tournoi local, ou pseudo inconnu). */
    String knownName(UUID uuid) {
        return scores.nameOf(uuid);
    }

    public void addScore(Player player, double amount) {
        addScore(player.getUniqueId(), player.getName(), amount);
    }

    /** Ajoute des points à un joueur, connecté sur ce serveur ou sur un autre. */
    void addScore(UUID uuid, String name, double amount) {
        // Tournoi partagé : un point marqué après l'heure de fin n'est compté nulle part, tous les serveurs s'arrêtent à la même heure
        if (network != null && System.currentTimeMillis() >= endTime) return;
        scores.add(uuid, name, amount);
        ranksDirty = true;
    }

    public double getScore(Player player) {
        return scores.get(player.getUniqueId());
    }

    /** Retient un bloc posé par un joueur pendant le tournoi. */
    public void markPlaced(Block block) {
        placedBlocks.add(BlockKey.of(block));
    }

    /** {@code true} si le bloc avait été posé pendant le tournoi ; il est oublié dans la foulée. */
    public boolean consumePlaced(Block block) {
        return placedBlocks.remove(BlockKey.of(block));
    }

    /** Suit les blocs posés que pousse ou tire un piston, pour qu'ils restent non comptés à leur nouvelle place. */
    public void movePlaced(List<Block> moved, BlockFace direction) {
        if (placedBlocks.isEmpty() || moved.isEmpty()) return;
        List<Block> tracked = new ArrayList<>();
        for (Block block : moved) {
            if (placedBlocks.remove(BlockKey.of(block))) tracked.add(block);
        }
        for (Block block : tracked) {
            placedBlocks.add(BlockKey.of(block.getRelative(direction)));
        }
    }

    /** {@code true} la première fois qu'une position est comptée en BLOCK_PLACE pendant ce tournoi. */
    public boolean firstPlacementAt(Block block) {
        return scoredPlacements.add(BlockKey.of(block));
    }

    /**
     * Méthode pour générer le message de la BossBar pour un joueur donné.
     */
    private String getBossBarMessage(Player player) {
        String template = Messages.get("boss-bar", "&5&lMini tournoi: &e%event-name% &7- %points% %points-name% - (&f%time%&7)");
        int remainingSeconds = getRemainingSeconds();
        int scoreDisplay = (int) Math.round(scores.get(player.getUniqueId()));
        int participants = getParticipantCount();
        String rank = "-";
        int computedRank = getPlayerRank(player.getUniqueId());
        if (computedRank > 0) {
            rank = String.valueOf(computedRank);
        }
        return ChatColor.translateAlternateColorCodes('&',
            template.replace("%event-name%", tournament.getDisplayName())
                .replace("%time%", formatTime(remainingSeconds))
                .replace("%points%", String.valueOf(scoreDisplay))
                .replace("%points-name%", tournament.getPointsName())
                .replace("%rank%", rank)
                .replace("%participants%", String.valueOf(participants))
        );
    }

    private int getPlayerRank(UUID playerId) {
        if (!scores.has(playerId)) {
            return -1;
        }

        if (ranksDirty) {
            // Remis à faux avant le calcul : un point marqué pendant le tri relance un calcul au prochain appel
            ranksDirty = false;
            List<Entry<UUID, Double>> ranking = new ArrayList<>(scores.snapshot().entrySet());
            ranking.sort((a, b) -> {
                int cmp = Double.compare(b.getValue(), a.getValue());
                if (cmp == 0) {
                    return a.getKey().toString().compareTo(b.getKey().toString());
                }
                return cmp;
            });
            Map<UUID, Integer> ranks = new HashMap<>();
            for (int i = 0; i < ranking.size(); i++) {
                ranks.put(ranking.get(i).getKey(), i + 1);
            }
            rankCache = ranks;
        }
        return rankCache.getOrDefault(playerId, -1);
    }

    private String formatTime(int seconds) {
        int min = seconds / 60;
        int sec = seconds % 60;
        return String.format("%02d:%02d", min, sec);
    }

    private BarColor parseBarColor(String colorStr) {
        try {
            return BarColor.valueOf(colorStr.toUpperCase());
        } catch (Exception e) {
            return BarColor.WHITE;
        }
    }

    private double getProgress() {
        double progress = (double) getRemainingSeconds() / (tournament.getTime() * 60);
        return Math.max(0, Math.min(1.0, progress));
    }

    /**
     * Vérifie que la BossBar est bien attachée à cette instance de joueur.
     * Après une reconnexion, l'ancienne BossBar pointe sur l'instance déconnectée :
     * la comparaison doit donc se faire par référence et non par UUID.
     */
    private boolean isAttached(BossBar bossBar, Player player) {
        for (Player viewer : bossBar.getPlayers()) {
            if (viewer == player) {
                return true;
            }
        }
        return false;
    }

    /**
     * Crée une BossBar neuve pour le joueur et jette l'éventuelle ancienne.
     * Appelé à la connexion pour que la BossBar réapparaisse après une reconnexion.
     */
    public void attachBossBar(Player player) {
        detachBossBar(player);
        BossBar bossBar = Bukkit.createBossBar(getBossBarMessage(player), parseBarColor(tournament.getBossBar()), BarStyle.SOLID);
        bossBar.setProgress(getProgress());
        bossBar.addPlayer(player);
        playerBossBars.put(player.getUniqueId(), bossBar);
    }

    /**
     * Retire la BossBar du joueur (déconnexion) pour ne pas garder une barre orpheline.
     */
    public void detachBossBar(Player player) {
        BossBar previous = playerBossBars.remove(player.getUniqueId());
        if (previous != null) {
            previous.removeAll();
        }
    }

    /**
     * Informe un joueur qui vient de se connecter qu'un mini tournoi est en cours.
     */
    public void sendJoinAnnouncement(Player player) {
        List<String> lines = Messages.getList("tournament-join");
        if (lines.isEmpty()) {
            return;
        }
        for (String line : lines) {
            String msg = line.replace("%event-id%", tournament.getId())
                .replace("%event-name%", tournament.getDisplayName())
                .replace("%event-type%", tournament.getType())
                .replace("%description%", tournament.getDescription())
                .replace("%points-name%", tournament.getPointsName())
                .replace("%participants%", String.valueOf(getParticipantCount()))
                .replace("%time-left%", formatTime(getRemainingSeconds()))
                .replace("%time-elapsed%", formatTime(getElapsedSeconds()))
                .replace("%time-total%", formatTime(tournament.getTime() * 60));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
    }

    @Override
    public void run() {
        pollPlaceholder();
        int remainingSeconds = getRemainingSeconds();
        if (remainingSeconds <= 0 && network != null) {
            // Tournoi partagé : le meneur conclut et annonce la fin à tous les serveurs, celui-ci attend son annonce
            network.timeUp(this);
            if (closed) return;
        } else if (remainingSeconds <= 0) {
            // Arrêtée avant la conclusion : une erreur pendant les récompenses ne doit pas relancer le tirage chaque seconde
            cancel();
            try {
                finishTournament();
            } catch (RuntimeException e) {
                SHFastTournament.getInstance().getLogger().log(java.util.logging.Level.SEVERE, "Erreur à la fin du tournoi " + tournament.getId(), e);
            } finally {
                for (BossBar bossBar : playerBossBars.values()) {
                    bossBar.removeAll();
                }
                playerBossBars.clear();
                SHFastTournament.getInstance().getTournamentManager().setActiveTournament(null);
                runEndCommands();
            }
            return;
        }
        if (remainingSeconds != lastRemainingSeconds) {
            lastRemainingSeconds = remainingSeconds;
        }
        double progress = getProgress();

        // Mise à jour de la BossBar pour chaque joueur
        for (Player player : Bukkit.getOnlinePlayers()) {
            BossBar bossBar = playerBossBars.get(player.getUniqueId());
            // Une barre existante mais non attachée signifie que le joueur s'est reconnecté
            if (bossBar == null || !isAttached(bossBar, player)) {
                attachBossBar(player);
                bossBar = playerBossBars.get(player.getUniqueId());
            }
            bossBar.setTitle(getBossBarMessage(player));
            bossBar.setProgress(progress);
        }

        // Envoi de l'actionbar
        String actionBarTemplate = Messages.get("actionbar", "&5&lMini tournoi: &e%points% &7%points-name% (&e%time% sec restantes&7)");

        // Vérifier si le message d'ActionBar est vide
        if (actionBarTemplate == null || actionBarTemplate.trim().isEmpty()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (scores.has(player.getUniqueId())) {
                int scoreDisplay = (int) Math.round(scores.get(player.getUniqueId()));
                String message = actionBarTemplate
                    .replace("%points%", String.valueOf(scoreDisplay))
                    .replace("%points-name%", tournament.getPointsName())
                    .replace("%time%", String.valueOf(remainingSeconds));

                if (!message.trim().isEmpty()) {
                    player.sendActionBar(ChatColor.translateAlternateColorCodes('&', message));
                }
            }
        }
    }

    private void finishTournament() {
        // Supprimer toutes les bossbars individuelles
        for (BossBar bossBar : playerBossBars.values()) {
            bossBar.removeAll();
        }
        playerBossBars.clear();

        playEndSound();
        List<Entry<UUID, Double>> ranking = new ArrayList<>(scores.snapshot().entrySet());
        ranking.sort((a, b) -> {
            int cmp = Double.compare(b.getValue(), a.getValue());
            if (cmp == 0) {
                String nameA = getPlayerName(a.getKey());
                String nameB = getPlayerName(b.getKey());
                return nameA.compareToIgnoreCase(nameB);
            }
            return cmp;
        });
        if (ranking.isEmpty()) {
            String noWinner = Messages.get("no-winner", "Personne n'a participé à ce tournoi");
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', noWinner));
            SHFastTournament.getInstance().getTournamentManager().setActiveTournament(null);
            return;
        }
        StringBuilder resultsBuilder = new StringBuilder();
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            Entry<UUID, Double> entry = ranking.get(i);
            int rank = i + 1;
            if (rank == 1) {
                SHFastTournament.getInstance()
                    .getWinsManager()
                    .addWin(entry.getKey());
            }
            int scoreDisplay = (int) Math.round(entry.getValue());
            String resultLine = Messages.get("results", "&7(&e#%place%&7) &e%player% &7- %points% %points-name%");
            resultLine = resultLine.replace("%place%", String.valueOf(rank))
                .replace("%player%", getPlayerName(entry.getKey()))
                .replace("%points%", String.valueOf(scoreDisplay))
                .replace("%points-name%", tournament.getPointsName());
            resultsBuilder.append(resultLine).append("\n");
        }
        String resultsStr = resultsBuilder.toString();
        List<String> endMessages = Messages.getList("tournament-end");
        for (String line : endMessages) {
            String msg = line.replace("%event-name%", tournament.getDisplayName())
                .replace("%results%", resultsStr);
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
        processRewards(ranking);
        String noParticipation = Messages.get("no-participation", "Vous n'avez pas participé à ce mini tournoi");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!scores.has(player.getUniqueId())) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', noParticipation));
            }
        }
        SHFastTournament.getInstance().getTournamentManager().setActiveTournament(null);
    }

    private static void playEndSound() {
        String soundStr = SHFastTournament.getInstance().getConfig().getString("settings.sounds.tournament-end", "ENTITY_PLAYER_LEVELUP");
        org.bukkit.Sound endSound = sound(soundStr);
        if (endSound != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(player.getLocation(), endSound, 1.0f, 1.0f);
            }
        } else {
            Bukkit.getLogger().warning("Son invalide pour tournament-end: " + soundStr);
        }
    }

    /**
     * Tournoi partagé : annonce la fin telle que le meneur l'a publiée (classement complet, du premier au dernier).
     * Mêmes textes et même ordre que la fin d'un tournoi local ; les récompenses sont données par le meneur.
     */
    void showEnd(List<Result> ranking) {
        // Supprimer toutes les bossbars individuelles
        for (BossBar bossBar : playerBossBars.values()) {
            bossBar.removeAll();
        }
        playerBossBars.clear();

        playEndSound();
        if (ranking.isEmpty()) {
            String noWinner = Messages.get("no-winner", "Personne n'a participé à ce tournoi");
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', noWinner));
            return;
        }
        StringBuilder resultsBuilder = new StringBuilder();
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            Result entry = ranking.get(i);
            int scoreDisplay = (int) Math.round(entry.score());
            String resultLine = Messages.get("results", "&7(&e#%place%&7) &e%player% &7- %points% %points-name%");
            resultLine = resultLine.replace("%place%", String.valueOf(entry.place()))
                .replace("%player%", entry.name())
                .replace("%points%", String.valueOf(scoreDisplay))
                .replace("%points-name%", tournament.getPointsName());
            resultsBuilder.append(resultLine).append("\n");
        }
        String resultsStr = resultsBuilder.toString();
        for (String line : Messages.getList("tournament-end")) {
            String msg = line.replace("%event-name%", tournament.getDisplayName())
                .replace("%results%", resultsStr);
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
        // Message personnel pour les joueurs hors top3 qui reçoivent une récompense
        Set<UUID> participants = new HashSet<>();
        for (Result entry : ranking) {
            participants.add(entry.uuid());
            if (entry.place() <= 3 || !"participation".equals(entry.tier())) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.uuid());
            if (player == null) {
                continue;
            }
            int scoreDisplay = (int) Math.round(entry.score());
            String personalTemplate = Messages.get("personal-results", "Vous: &7(&e#%place%&7) - &e%points% %points-name%");
            String personalMsg = personalTemplate.replace("%place%", String.valueOf(entry.place()))
                .replace("%points%", String.valueOf(scoreDisplay))
                .replace("%points-name%", tournament.getPointsName());
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', personalMsg));
        }
        String noParticipation = Messages.get("no-participation", "Vous n'avez pas participé à ce mini tournoi");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!participants.contains(player.getUniqueId())) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', noParticipation));
            }
        }
    }

    private void processRewards(List<Entry<UUID, Double>> ranking) {
        FileConfiguration config = SHFastTournament.getInstance().getConfig();
        // Calculer le seuil de récompense de participation
        double participationThreshold = 0;
        if (ranking.size() >= 3) {
            participationThreshold = ranking.get(2).getValue() * 0.5;
        }

        for (int i = 0; i < ranking.size(); i++) {
            Entry<UUID, Double> entry = ranking.get(i);
            int rank = i + 1;
            String playerName = getPlayerName(entry.getKey());
            List<String> commands = null;

            if (rank == 1) {
                commands = config.getStringList("settings.rewards.top1");
            } else if (rank == 2) {
                commands = config.getStringList("settings.rewards.top2");
            } else if (rank == 3) {
                commands = config.getStringList("settings.rewards.top3");
            } else {
                // Pour la récompense de participation, on vérifie que le joueur a atteint le seuil
                if (entry.getValue() < participationThreshold) {
                    continue;
                }
                commands = config.getStringList("settings.rewards.participation");
            }

            if (commands != null && !commands.isEmpty()) {
                boolean dispatched = false;
                boolean failed = false;
                for (String originalCmd : commands) {
                    String cmd = originalCmd.trim().replace("%player%", playerName);
                    if (!cmd.isEmpty()) {
                        try {
                            if (Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)) {
                                dispatched = true;
                            } else {
                                failed = true;
                                SHFastTournament.getInstance().getLogger().warning("Commande de récompense refusée '" + cmd + "'");
                            }
                        } catch (RuntimeException e) {
                            // Une commande en échec ne doit pas priver les autres joueurs de leurs récompenses
                            failed = true;
                            SHFastTournament.getInstance().getLogger().warning("Commande de récompense en échec '" + cmd + "' : " + e.getMessage());
                        }
                    }
                }
                if (dispatched && !failed) {
                    try {
                        logReward(config, playerName, rank);
                    } catch (RuntimeException e) {
                        SHFastTournament.getInstance().getLogger().warning("Log de récompense impossible pour " + playerName + " : " + e.getMessage());
                    }
                }
            }
            // Message personnel pour les joueurs hors top3 qui reçoivent une récompense
            if (rank > 3 && entry.getValue() >= participationThreshold) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player == null) {
                    continue;
                }
                int scoreDisplay = (int) Math.round(entry.getValue());
                String personalTemplate = Messages.get("personal-results", "Vous: &7(&e#%place%&7) - &e%points% %points-name%");
                String personalMsg = personalTemplate.replace("%place%", String.valueOf(rank))
                    .replace("%points%", String.valueOf(scoreDisplay))
                    .replace("%points-name%", tournament.getPointsName());
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', personalMsg));
            }
        }
    }

    /** Écrit dans SHLogs la récompense d'un joueur, une fois toutes ses commandes exécutées. */
    private void logReward(FileConfiguration config, String playerName, int rank) {
        logReward(tournament, config, playerName, rank);
    }

    /** Écrit dans SHLogs la récompense d'un joueur de ce tournoi (aussi utilisé par le meneur d'un tournoi partagé). */
    static void logReward(Tournament tournament, FileConfiguration config, String playerName, int rank) {
        String tier = rank <= 3 ? "top" + rank : "participation";
        String file = config.getString("settings.log.file", "recompenses");
        String format = config.getString("settings.log.format",
            "%player% -> reçoit la récompense du tournoi %tournament% (place %place% : %reward%)");
        String reward = config.getString("settings.log.rewards." + tier, tier);
        String tournamentName = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', tournament.getDisplayName()));
        ActionLog.write(SHFastTournament.getInstance(), file, format
            .replace("%player%", playerName)
            .replace("%tournament%", tournamentName)
            .replace("%place%", String.valueOf(rank))
            .replace("%reward%", reward));
    }

    private String getPlayerName(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
        String name = offline.getName();
        return (name != null && !name.isEmpty()) ? name : playerId.toString();
    }

    public void stopTournament() {
        closed = true;
        cancel();
        for (BossBar bossBar : playerBossBars.values()) {
            bossBar.removeAll();
        }
        playerBossBars.clear();
        runEndCommands();
    }
}
