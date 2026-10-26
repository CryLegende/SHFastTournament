package org.Cry_Legende.sHFastTournament;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.ChatColor;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.Map.Entry;

public class ActiveTournament extends BukkitRunnable {

    private final Tournament tournament;
    private final Map<UUID, Double> scores = new HashMap<>();
    // BossBar personnalisée pour chaque joueur
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();
    private final long startTime;
    private final long endTime;
    private int lastRemainingSeconds = -1;
    // Classement calculé une fois par changement de scores (et non une fois par joueur et par seconde)
    private Map<UUID, Integer> rankCache = new HashMap<>();
    private boolean ranksDirty = false;

    public ActiveTournament(Tournament tournament) {
        this.tournament = tournament;
        long durationMillis = tournament.getTime() * 60L * 1000L;
        this.startTime = System.currentTimeMillis();
        this.endTime = startTime + durationMillis;

        // Son et messages de début (inchangés)
        String soundStr = SHFastTournament.getInstance().getConfig().getString("settings.sounds.tournament-start", "ENTITY_EXPERIENCE_ORB_PICKUP");
        org.bukkit.Sound startSound = sound(soundStr);
        if (startSound != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(player.getLocation(), startSound, 1.0f, 1.0f);
            }
        } else {
            Bukkit.getLogger().warning("Son invalide pour tournament-start: " + soundStr);
        }
        List<String> startMessages = SHFastTournament.getInstance().getConfig().getStringList("messages.tournament-start");
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
        return new HashMap<>(scores);
    }

    public void addScore(Player player, double amount) {
        scores.put(player.getUniqueId(), scores.getOrDefault(player.getUniqueId(), 0.0) + amount);
        ranksDirty = true;
    }

    public double getScore(Player player) {
        return scores.getOrDefault(player.getUniqueId(), 0.0);
    }

    /**
     * Méthode pour générer le message de la BossBar pour un joueur donné.
     */
    private String getBossBarMessage(Player player) {
        FileConfiguration config = SHFastTournament.getInstance().getConfig();
        // Template de la bossbar, vous pouvez ajouter tous les placeholders nécessaires
        String template = config.getString("messages.boss-bar", "&5&lMini tournoi: &e%event-name% &7- %points% %points-name% - (&f%time%&7)");
        int remainingSeconds = getRemainingSeconds();
        int scoreDisplay = (int) Math.round(scores.getOrDefault(player.getUniqueId(), 0.0));
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
        if (!scores.containsKey(playerId)) {
            return -1;
        }

        if (ranksDirty) {
            List<Entry<UUID, Double>> ranking = new ArrayList<>(scores.entrySet());
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
            ranksDirty = false;
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
        FileConfiguration config = SHFastTournament.getInstance().getConfig();
        List<String> lines = config.getStringList("messages.tournament-join");
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
        int remainingSeconds = getRemainingSeconds();
        if (remainingSeconds <= 0) {
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

        // Envoi de l'actionbar (inchangé)
        String actionBarTemplate = SHFastTournament.getInstance().getConfig().getString("messages.actionbar", "&5&lMini tournoi: &e%points% &7%points-name% (&e%time% sec restantes&7)");

        // Vérifier si le message d'ActionBar est vide
        if (actionBarTemplate == null || actionBarTemplate.trim().isEmpty()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (scores.containsKey(player.getUniqueId())) {
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

        String soundStr = SHFastTournament.getInstance().getConfig().getString("settings.sounds.tournament-end", "ENTITY_PLAYER_LEVELUP");
        org.bukkit.Sound endSound = sound(soundStr);
        if (endSound != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(player.getLocation(), endSound, 1.0f, 1.0f);
            }
        } else {
            Bukkit.getLogger().warning("Son invalide pour tournament-end: " + soundStr);
        }
        List<Entry<UUID, Double>> ranking = new ArrayList<>(scores.entrySet());
        ranking.sort((a, b) -> {
            int cmp = Double.compare(b.getValue(), a.getValue());
            if (cmp == 0) {
                String nameA = getPlayerName(a.getKey());
                String nameB = getPlayerName(b.getKey());
                return nameA.compareToIgnoreCase(nameB);
            }
            return cmp;
        });
        FileConfiguration config = SHFastTournament.getInstance().getConfig();
        if (ranking.isEmpty()) {
            String noWinner = config.getString("messages.no-winner", "Personne n'a participé à ce tournoi");
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
            String resultLine = config.getString("messages.results", "&7(&e#%place%&7) &e%player% &7- %points% %points-name%");
            resultLine = resultLine.replace("%place%", String.valueOf(rank))
                .replace("%player%", getPlayerName(entry.getKey()))
                .replace("%points%", String.valueOf(scoreDisplay))
                .replace("%points-name%", tournament.getPointsName());
            resultsBuilder.append(resultLine).append("\n");
        }
        String resultsStr = resultsBuilder.toString();
        List<String> endMessages = config.getStringList("messages.tournament-end");
        for (String line : endMessages) {
            String msg = line.replace("%event-name%", tournament.getDisplayName())
                .replace("%results%", resultsStr);
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
        processRewards(ranking);
        String noParticipation = config.getString("messages.no-participation", "Vous n'avez pas participé à ce mini tournoi");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!scores.containsKey(player.getUniqueId())) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', noParticipation));
            }
        }
        SHFastTournament.getInstance().getTournamentManager().setActiveTournament(null);
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
                for (String originalCmd : commands) {
                    String cmd = originalCmd.trim().replace("%player%", playerName);
                    if (!cmd.isEmpty()) {
                        try {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                        } catch (RuntimeException e) {
                            // Une commande en échec ne doit pas priver les autres joueurs de leurs récompenses
                            SHFastTournament.getInstance().getLogger().warning("Commande de récompense en échec '" + cmd + "' : " + e.getMessage());
                        }
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
                String personalTemplate = config.getString("messages.personal-results", "Vous: &7(&e#%place%&7) - &e%points% %points-name%");
                String personalMsg = personalTemplate.replace("%place%", String.valueOf(rank))
                    .replace("%points%", String.valueOf(scoreDisplay))
                    .replace("%points-name%", tournament.getPointsName());
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', personalMsg));
            }
        }
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
        cancel();
        for (BossBar bossBar : playerBossBars.values()) {
            bossBar.removeAll();
        }
        playerBossBars.clear();
    }
}
