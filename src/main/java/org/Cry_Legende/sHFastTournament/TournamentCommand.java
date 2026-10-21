package org.Cry_Legende.sHFastTournament;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

public class TournamentCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        FileConfiguration config = SHFastTournament.getInstance().getConfig();
        // Vérification de permission
        if (!sender.hasPermission("shfasttournament.admin")) {
            sender.sendMessage(colorize(config.getString("messages.no-perm", "&cVous n'avez pas la permission d'utiliser cette commande.")));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Utilisation : /tournament <start|stop|info|status|custom>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start":
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Utilisation : /tournament start <id> [force]");
                    return true;
                }
                String id = args[1];
                Tournament tournament = SHFastTournament.getInstance().getTournamentManager().getTournament(id);
                if (tournament == null) {
                    sender.sendMessage(ChatColor.RED + "Le tournoi d'id " + id + " n'existe pas !");
                    return true;
                }
                // Si un tournoi est déjà actif
                if (SHFastTournament.getInstance().getTournamentManager().getActiveTournament() != null) {
                    if (args.length >= 3 && args[2].equalsIgnoreCase("force")) {
                        SHFastTournament.getInstance().getTournamentManager().getActiveTournament().stopTournament();
                        SHFastTournament.getInstance().getTournamentManager().setActiveTournament(null);
                        sender.sendMessage(ChatColor.YELLOW + "Le tournoi en cours a été arrêté par commande force.");
                    } else {
                        sender.sendMessage(ChatColor.RED + "Un tournoi est déjà en cours ! Utilisez '/tournament start " + id + " force' pour forcer son démarrage.");
                        return true;
                    }
                }
                // Utilisation de la méthode startTournament du TournamentManager
                SHFastTournament.getInstance().getTournamentManager().startTournament(tournament);
                sender.sendMessage(ChatColor.GREEN + "Le tournoi \"" + tournament.getDisplayName() + "\" a commencé !");
                break;
            case "stop":
                ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
                if (active == null) {
                    sender.sendMessage(ChatColor.RED + "Aucun tournoi n'est en cours.");
                    return true;
                }
                active.stopTournament();
                SHFastTournament.getInstance().getTournamentManager().setActiveTournament(null);
                sender.sendMessage(ChatColor.YELLOW + "Le tournoi en cours a été arrêté.");
                break;
            case "info":
                sender.sendMessage(ChatColor.AQUA + "Liste des tournois disponibles :");
                SHFastTournament.getInstance().getTournamentManager().getTournaments().values().stream()
                    .filter(Tournament::isEnabled)
                    .forEach(t -> sender.sendMessage(ChatColor.GRAY + "- " + t.getId() + " : " + t.getDisplayName()));
                break;
            case "custom":
                // Utilisation : /tournament custom <id> <player>
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Utilisation : /tournament custom <id> <player>");
                    return true;
                }
                String customId = args[1];
                String playerName = args[2];
                ActiveTournament activeCustom = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
                if (activeCustom == null) {
                    return true;
                }
                Tournament currentTournament = activeCustom.getTournament();
                // Vérifier que le tournoi est de type CUSTOM
                if (!"CUSTOM".equalsIgnoreCase(currentTournament.getType())) {
                    return true;
                }
                // Utilisation de la variable "objective" comme identifiant pour le tournoi CUSTOM
                if (!customId.equalsIgnoreCase(currentTournament.getObjective())) {
                    return true;
                }
                var target = org.bukkit.Bukkit.getPlayerExact(playerName);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Le joueur " + playerName + " n'est pas en ligne.");
                    return true;
                }
                activeCustom.addScore(target, 1);
                sender.sendMessage(ChatColor.GREEN + "Un point a été ajouté pour " + target.getName() + ".");
                break;
            case "status":
                sendTournamentStatus(sender, config);
                break;
            default:
                sender.sendMessage(colorize(config.getString("messages.unknown-command", "&cCommande inconnue.")));
        }
        return true;
    }

    private void sendTournamentStatus(CommandSender sender, FileConfiguration config) {
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) {
            sender.sendMessage(colorize(config.getString("messages.status-no-active", "&7&l« &c&lSH &8&l» &cAucun mini tournoi en cours.")));
            return;
        }

        Tournament tournament = active.getTournament();
        int remaining = active.getRemainingSeconds();
        int elapsed = active.getElapsedSeconds();
        int duration = tournament.getTime() * 60;

        String leaderName = config.getString("messages.status-no-leader", "Aucun");
        String leaderPoints = "0";
        Map<UUID, Double> scores = active.getScoresSnapshot();
        if (!scores.isEmpty()) {
            Map.Entry<UUID, Double> topEntry = scores.entrySet().stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .orElse(null);
            if (topEntry != null) {
                Player online = org.bukkit.Bukkit.getPlayer(topEntry.getKey());
                if (online != null) {
                    leaderName = online.getName();
                } else {
                    var offline = org.bukkit.Bukkit.getOfflinePlayer(topEntry.getKey());
                    if (offline.getName() != null && !offline.getName().isEmpty()) {
                        leaderName = offline.getName();
                    } else {
                        leaderName = topEntry.getKey().toString();
                    }
                }
                leaderPoints = String.valueOf((int) Math.round(topEntry.getValue()));
            }
        }

        for (String line : config.getStringList("messages.tournament-status")) {
            String msg = line
                .replace("%event-id%", tournament.getId())
                .replace("%event-name%", tournament.getDisplayName())
                .replace("%event-type%", tournament.getType())
                .replace("%points-name%", tournament.getPointsName())
                .replace("%participants%", String.valueOf(active.getParticipantCount()))
                .replace("%time-left%", formatTime(remaining))
                .replace("%time-elapsed%", formatTime(elapsed))
                .replace("%time-total%", formatTime(duration))
                .replace("%leader%", leaderName)
                .replace("%leader-points%", leaderPoints);
            sender.sendMessage(colorize(msg));
        }
    }

    private String formatTime(int seconds) {
        int min = seconds / 60;
        int sec = seconds % 60;
        return String.format("%02d:%02d", min, sec);
    }

    private String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }
}
