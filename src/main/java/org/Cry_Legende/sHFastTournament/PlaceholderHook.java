package org.Cry_Legende.sHFastTournament;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public class PlaceholderHook extends PlaceholderExpansion {

    private final SHFastTournament plugin;

    public PlaceholderHook(SHFastTournament plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String getIdentifier() {
        return "shfasttournament";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /** Texte avec les placeholders du joueur remplacés (PlaceholderAPI doit être activé). */
    static String parse(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) return "";
        if ("wins".equals(identifier)) {
            return String.valueOf(plugin.getWins(player.getUniqueId()));
        }
        return null;
    }
}
