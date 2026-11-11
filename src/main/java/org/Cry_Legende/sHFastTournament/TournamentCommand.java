package org.Cry_Legende.sHFastTournament;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /shfasttournament (alias /tournament) : start, stop, info, status, custom, import (avec SHNetwork) et reload. */
public class TournamentCommand implements CommandExecutor {

    /** Sous-commandes dans l'ordre d'affichage ; chacune demande {@code shfasttournament.admin.<nom>}. */
    static final List<String> SUBS = List.of("start", "stop", "info", "status", "custom", "import", "reload");
    static final String PERMISSION_PREFIX = "shfasttournament.admin.";

    /** Sous-commandes que l'expéditeur peut utiliser ; l'importation n'est proposée qu'avec SHNetwork. */
    static List<String> allowed(CommandSender sender) {
        boolean network = SHFastTournament.getInstance().network() != null;
        List<String> names = new ArrayList<>();
        for (String sub : SUBS) {
            if (sender.hasPermission(PERMISSION_PREFIX + sub) && (network || !sub.equals("import"))) {
                names.add(sub);
            }
        }
        return names;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> allowed = allowed(sender);
        // Sans réseau, l'importation reste répondue (import-unavailable) à qui a le droit de la demander
        boolean importAsked = args.length > 0 && args[0].equalsIgnoreCase("import") && sender.hasPermission(PERMISSION_PREFIX + "import");
        if (allowed.isEmpty() && !importAsked) {
            sender.sendMessage(colorize(Messages.get("no-perm", "&cVous n'avez pas la permission d'utiliser cette commande.")));
            return true;
        }

        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!allowed.contains(sub) && !importAsked) {
            sender.sendMessage(message("usage", "&7&l« &c&lSH &8&l» &7Utilisation : &c/shfasttournament <%subs%>&7.", "%subs%", String.join("|", allowed)));
            return true;
        }

        // Non nul avec SHNetwork : un seul tournoi pour tout le réseau, quel que soit le serveur où la commande est tapée
        NetworkTournaments network = SHFastTournament.getInstance().network();

        switch (sub) {
            case "reload":
                String error = SHFastTournament.getInstance().reload();
                if (error == null) {
                    sender.sendMessage(message("reloaded", "&7&l« &a&lSH &8&l» &7Configuration et messages &arechargés&7."));
                } else {
                    sender.sendMessage(message("reload-failed", "&7&l« &c&lSH &8&l» &7Rechargement annulé, l'ancienne configuration reste active : &c%error%&7.", "%error%", error));
                }
                break;
            case "start":
                if (args.length < 2) {
                    sender.sendMessage(message("usage-start", "&7&l« &c&lSH &8&l» &7Utilisation : &c/shfasttournament start <id> [force]&7."));
                    return true;
                }
                String id = args[1];
                Tournament tournament = SHFastTournament.getInstance().getTournamentManager().getTournament(id);
                if (tournament == null) {
                    sender.sendMessage(message("not-an-id", "&7&l« &c&lSH &8&l» &7Le tournoi d'id &c%event-id%&7 n'existe pas.", "%event-id%", id));
                    return true;
                }
                if (network != null) {
                    boolean force = args.length >= 3 && args[2].equalsIgnoreCase("force");
                    network.start(tournament, force, outcome -> sender.sendMessage(startMessage(outcome, tournament)));
                    return true;
                }
                // Si un tournoi est déjà actif
                if (SHFastTournament.getInstance().getTournamentManager().getActiveTournament() != null) {
                    if (args.length >= 3 && args[2].equalsIgnoreCase("force")) {
                        SHFastTournament.getInstance().getTournamentManager().getActiveTournament().stopTournament();
                        SHFastTournament.getInstance().getTournamentManager().setActiveTournament(null);
                        sender.sendMessage(message("start-forced", "&7&l« &e&lSH &8&l» &7Le tournoi en cours a été arrêté par la commande &eforce&7."));
                    } else {
                        sender.sendMessage(message("already-playing", "&7&l« &c&lSH &8&l» &7Un tournoi est déjà en cours. Utilisez &c/shfasttournament start %event-id% force&7 pour forcer son démarrage.", "%event-id%", id));
                        return true;
                    }
                }
                // Utilisation de la méthode startTournament du TournamentManager
                SHFastTournament.getInstance().getTournamentManager().startTournament(tournament);
                sender.sendMessage(message("start-success", "&7&l« &a&lSH &8&l» &7Le tournoi &a%event-name%&7 a commencé.", "%event-name%", tournament.getDisplayName()));
                break;
            case "stop":
                if (network != null) {
                    network.stop(outcome -> sender.sendMessage(stopMessage(outcome)));
                    return true;
                }
                ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
                if (active == null) {
                    sender.sendMessage(message("stop-fail", "&7&l« &c&lSH &8&l» &7Aucun tournoi n'est en cours."));
                    return true;
                }
                active.stopTournament();
                SHFastTournament.getInstance().getTournamentManager().setActiveTournament(null);
                sender.sendMessage(message("stop-success", "&7&l« &a&lSH &8&l» &7Le mini tournoi en cours a été arrêté."));
                break;
            case "info":
                sender.sendMessage(message("info-header", "&7&l« &e&lSH &8&l» &7Liste des tournois disponibles :"));
                SHFastTournament.getInstance().getTournamentManager().getTournaments().values().stream()
                    .filter(Tournament::isEnabled)
                    .forEach(t -> sender.sendMessage(message("info-line", "&7- &e%event-id%&7 : %event-name%", "%event-id%", t.getId(), "%event-name%", t.getDisplayName())));
                break;
            case "custom":
                // Utilisation : /shfasttournament custom <id> <player>
                if (args.length < 3) {
                    sender.sendMessage(message("usage-custom", "&7&l« &c&lSH &8&l» &7Utilisation : &c/shfasttournament custom <id> <player>&7."));
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
                if (network != null) {
                    // Le joueur peut être sur un autre serveur : ses points sont comptés ici et rejoignent le total du réseau
                    NetworkTournaments.Target found = network.findTarget(playerName);
                    if (found == null) {
                        sender.sendMessage(message("custom-offline", "&7&l« &c&lSH &8&l» &7Le joueur &c%player%&7 n'est pas en ligne.", "%player%", playerName));
                        return true;
                    }
                    activeCustom.addScore(found.uuid(), found.name(), 1);
                    if (sender instanceof org.bukkit.entity.Player) {
                        sender.sendMessage(message("custom-point-added", "&7&l« &a&lSH &8&l» &7Un point a été ajouté pour &a%player%&7.", "%player%", found.name()));
                    }
                    break;
                }
                var target = org.bukkit.Bukkit.getPlayerExact(playerName);
                if (target == null) {
                    sender.sendMessage(message("custom-offline", "&7&l« &c&lSH &8&l» &7Le joueur &c%player%&7 n'est pas en ligne.", "%player%", playerName));
                    return true;
                }
                activeCustom.addScore(target, 1);
                // Appel par un autre plugin depuis la console (ex. une seconde de règne du roi du spawn) : pas de spam
                if (sender instanceof org.bukkit.entity.Player) {
                    sender.sendMessage(message("custom-point-added", "&7&l« &a&lSH &8&l» &7Un point a été ajouté pour &a%player%&7.", "%player%", target.getName()));
                }
                break;
            case "status":
                sendTournamentStatus(sender);
                break;
            case "import":
                // Reprend les victoires de wins.yml dans la base du réseau
                if (network == null) {
                    sender.sendMessage(message("import-unavailable", "&7&l« &6&lSH &8&l» &7L'importation ne sert qu'&6avec SHNetwork&7 : ce serveur compte les victoires dans wins.yml."));
                    return true;
                }
                network.importWins(SHFastTournament.getInstance().getWinsManager().snapshot(), counts -> {
                    if (counts == null) {
                        sender.sendMessage(message("import-error", "&7&l« &c&lSH &8&l» &7Importation &céchouée&7 : base injoignable, voir la console."));
                    } else {
                        sender.sendMessage(message("import-success", "&7&l« &a&lSH &8&l» &a%imported%&7 joueurs importés dans la base du réseau (&a%present%&7 déjà présents).",
                            "%imported%", String.valueOf(counts[0]), "%present%", String.valueOf(counts[1])));
                    }
                });
                break;
            default:
                sender.sendMessage(message("usage", "&7&l« &c&lSH &8&l» &7Utilisation : &c/shfasttournament <%subs%>&7.", "%subs%", String.join("|", allowed)));
        }
        return true;
    }

    private void sendTournamentStatus(CommandSender sender) {
        ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
        if (active == null) {
            sender.sendMessage(colorize(Messages.get("status-no-active", "&7&l« &c&lSH &8&l» &cAucun mini tournoi en cours.")));
            return;
        }

        Tournament tournament = active.getTournament();
        int remaining = active.getRemainingSeconds();
        int elapsed = active.getElapsedSeconds();
        int duration = tournament.getTime() * 60;

        String leaderName = Messages.get("status-no-leader", "Aucun");
        String leaderPoints = "0";
        Map<UUID, Double> scores = active.getScoresSnapshot();
        if (!scores.isEmpty()) {
            Map.Entry<UUID, Double> topEntry = scores.entrySet().stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .orElse(null);
            if (topEntry != null) {
                Player online = org.bukkit.Bukkit.getPlayer(topEntry.getKey());
                // Participant d'un autre serveur : son pseudo vient de la base du réseau
                String known = active.knownName(topEntry.getKey());
                if (online != null) {
                    leaderName = online.getName();
                } else if (known != null) {
                    leaderName = known;
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

        for (String line : Messages.getList("tournament-status")) {
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

    /** Réponse de /shfasttournament start avec SHNetwork : mêmes textes qu'un serveur seul. */
    private String startMessage(NetworkTournaments.Outcome outcome, Tournament tournament) {
        return switch (outcome) {
            case FORCED -> message("start-forced", "&7&l« &e&lSH &8&l» &7Le tournoi en cours a été arrêté par la commande &eforce&7.");
            case STARTED -> message("start-success", "&7&l« &a&lSH &8&l» &7Le tournoi &a%event-name%&7 a commencé.", "%event-name%", tournament.getDisplayName());
            case ALREADY_RUNNING -> message("already-playing", "&7&l« &c&lSH &8&l» &7Un tournoi est déjà en cours. Utilisez &c/shfasttournament start %event-id% force&7 pour forcer son démarrage.", "%event-id%", tournament.getId());
            default -> networkError();
        };
    }

    /** Réponse de /shfasttournament stop avec SHNetwork : mêmes textes qu'un serveur seul. */
    private String stopMessage(NetworkTournaments.Outcome outcome) {
        return switch (outcome) {
            case STOPPED -> message("stop-success", "&7&l« &a&lSH &8&l» &7Le mini tournoi en cours a été arrêté.");
            case NONE -> message("stop-fail", "&7&l« &c&lSH &8&l» &7Aucun tournoi n'est en cours.");
            default -> networkError();
        };
    }

    private String networkError() {
        return message("network-error", "&7&l« &c&lSH &8&l» &7La base du réseau ne répond pas : &cdemande annulée&7, voir la console.");
    }

    private String formatTime(int seconds) {
        int min = seconds / 60;
        int sec = seconds % 60;
        return String.format("%02d:%02d", min, sec);
    }

    private String message(String key, String def, String... placeholders) {
        String text = Messages.get(key, def);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace(placeholders[i], placeholders[i + 1]);
        }
        return colorize(text);
    }

    private String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }
}
