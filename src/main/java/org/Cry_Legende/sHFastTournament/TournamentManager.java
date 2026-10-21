package org.Cry_Legende.sHFastTournament;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.stream.Collectors;

public class TournamentManager {

    private final Map<String, Tournament> tournaments = new HashMap<>();
    private ActiveTournament activeTournament = null;
    private BukkitRunnable autoSchedulerTask;
    private BukkitRunnable pendingTournamentTask;

    public void init() {
        loadTournaments();
        startAutoScheduler();
    }

    public void shutdown() {
        if (autoSchedulerTask != null) {
            autoSchedulerTask.cancel();
        }
        if (pendingTournamentTask != null) {
            pendingTournamentTask.cancel();
        }
        if (activeTournament != null) {
            activeTournament.stopTournament();
        }
    }

    private void loadTournaments() {
        FileConfiguration config = SHFastTournament.getInstance().getConfig();
        ConfigurationSection tournamentsSection = config.getConfigurationSection("tournaments");
        if (tournamentsSection == null) {
            SHFastTournament.getInstance().getLogger().warning("Aucune configuration de tournois trouvée !");
            return;
        }
        for (String key : tournamentsSection.getKeys(false)) {
            ConfigurationSection section = tournamentsSection.getConfigurationSection(key);
            if (section != null) {
                Tournament tournament = new Tournament(key, section);
                tournaments.put(key, tournament);
            }
        }
    }

    public Tournament getTournament(String id) {
        return tournaments.get(id);
    }

    public Map<String, Tournament> getTournaments() {
        return tournaments;
    }

    public ActiveTournament getActiveTournament() {
        return activeTournament;
    }

    public void setActiveTournament(ActiveTournament at) {
        activeTournament = at;
    }

    /**
     * Démarre le tournoi demandé.
     */
    public void startTournament(Tournament tournament) {
        if (activeTournament != null) {
            Bukkit.getLogger().warning("Un tournoi est déjà en cours !");
            return;
        }
        if (pendingTournamentTask != null) {
            pendingTournamentTask.cancel();
            pendingTournamentTask = null;
        }
        activeTournament = new ActiveTournament(tournament);
        activeTournament.runTaskTimer(SHFastTournament.getInstance(), 20L, 20L);
    }

    /**
     * Lance une tâche récurrente pour démarrer automatiquement un tournoi si les conditions sont remplies.
     */
    private void startAutoScheduler() {
        autoSchedulerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (activeTournament != null) return;

                int onlineCount = Bukkit.getOnlinePlayers().size();
                int minimumPlayers = SHFastTournament.getInstance().getConfig().getInt("settings.minimum-players", 10);
                if (onlineCount < minimumPlayers) {
                    if (pendingTournamentTask != null) {
                        pendingTournamentTask.cancel();
                        pendingTournamentTask = null;
                    }
                    return;
                }

                if (pendingTournamentTask == null) {
                    Object intervalObj = SHFastTournament.getInstance().getConfig().get("settings.interval", "10");
                    int delayMinutes = parseInterval(intervalObj);
                    Bukkit.getLogger().info("Tournoi automatique programmé dans " + delayMinutes + " minute(s).");
                    pendingTournamentTask = new BukkitRunnable() {
                        @Override
                        public void run() {
                            List<Tournament> enabledTournaments = tournaments.values().stream()
                                .filter(Tournament::isEnabled)
                                .collect(Collectors.toList());
                            if (enabledTournaments.isEmpty()) {
                                Bukkit.getLogger().warning("Aucun tournoi activé dans la configuration !");
                                return;
                            }
                            Tournament randomTournament = enabledTournaments.get(new Random().nextInt(enabledTournaments.size()));
                            startTournament(randomTournament);
                            pendingTournamentTask = null;
                        }
                    };
                    pendingTournamentTask.runTaskLater(SHFastTournament.getInstance(), delayMinutes * 60L * 20L);
                }
            }
        };
        autoSchedulerTask.runTaskTimer(SHFastTournament.getInstance(), 20L, 20L);
    }

    /**
     * Analyse l'objet interval (chaîne ou nombre) et renvoie un délai en minutes.
     */
    private int parseInterval(Object intervalObj) {
        String intervalStr = String.valueOf(intervalObj);
        if (intervalStr.contains("-")) {
            String[] parts = intervalStr.split("-");
            try {
                int min = Integer.parseInt(parts[0].trim());
                int max = Integer.parseInt(parts[1].trim());
                return new Random().nextInt(max - min + 1) + min;
            } catch (NumberFormatException e) {
                return 10;
            }
        } else {
            try {
                return Integer.parseInt(intervalStr.trim());
            } catch (NumberFormatException e) {
                return 10;
            }
        }
    }
}
