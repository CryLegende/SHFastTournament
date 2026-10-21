package org.Cry_Legende.sHFastTournament;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class TournamentTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        // Premier argument : sous-commandes
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            List<String> subCommands = Arrays.asList("start", "stop", "info", "status", "custom");
            StringUtil.copyPartialMatches(partial, subCommands, completions);
        }
        // Deuxième argument pour "start" : IDs des tournois disponibles
        else if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            String partial = args[1].toLowerCase();
            List<String> tournamentIds = SHFastTournament.getInstance().getTournamentManager()
                .getTournaments().keySet().stream()
                .filter(id -> SHFastTournament.getInstance().getTournamentManager().getTournament(id).isEnabled())
                .collect(Collectors.toList());
            StringUtil.copyPartialMatches(partial, tournamentIds, completions);
        }
        // Troisième argument pour "start" : suggestion de "force"
        else if (args.length == 3 && args[0].equalsIgnoreCase("start")) {
            String partial = args[2].toLowerCase();
            if ("force".startsWith(partial)) {
                completions.add("force");
            }
        }
        // Deuxième argument pour "custom" : ID du tournoi CUSTOM actif
        else if (args.length == 2 && args[0].equalsIgnoreCase("custom")) {
            String partial = args[1].toLowerCase();
            ActiveTournament active = SHFastTournament.getInstance().getTournamentManager().getActiveTournament();
            if (active != null && "CUSTOM".equalsIgnoreCase(active.getTournament().getType())) {
                String id = active.getTournament().getId();
                if (id.toLowerCase().startsWith(partial)) {
                    completions.add(id);
                }
            }
        }
        // Troisième argument pour "custom" : noms des joueurs en ligne
        else if (args.length == 3 && args[0].equalsIgnoreCase("custom")) {
            String partial = args[2].toLowerCase();
            List<String> onlinePlayers = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
            StringUtil.copyPartialMatches(partial, onlinePlayers, completions);
        }

        // Trier les suggestions pour une meilleure lisibilité
        Collections.sort(completions);
        return completions;
    }
}
