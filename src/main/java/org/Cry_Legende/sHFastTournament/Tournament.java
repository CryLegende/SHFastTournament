package org.Cry_Legende.sHFastTournament;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class Tournament {

    private final String id;
    private final boolean enabled;
    private final int time; // durée en minutes
    private final String displayName;
    private final String description;
    private final String type;
    private final String objective;
    private final String blacklist;
    private final String pointsName;
    private final String bossBar;
    private final List<String> worldsBlacklist;
    // Listes d'IDs lues une fois (et non à chaque événement) : en majuscules, sans espaces autour des virgules
    private final boolean anyObjective;
    private final Set<String> objectiveIds;
    private final Set<String> blacklistIds;

    public Tournament(String id, ConfigurationSection section) {
        this.id = id;
        this.enabled = section.getBoolean("enabled", false);
        this.time = Math.max(1, section.getInt("time", 1)); // 0 ferait diviser la progression par zéro
        this.displayName = section.getString("display-name", "Tournament");
        this.description = section.getString("description", "");
        this.type = section.getString("type", "NONE");
        this.objective = section.getString("objective", "NONE");
        this.blacklist = section.getString("blacklist", "NONE");
        this.pointsName = section.getString("points-name", "points");
        this.bossBar = section.getString("boss-bar", "NONE");
        this.worldsBlacklist = section.getStringList("worlds-blacklist");
        this.anyObjective = "NONE".equalsIgnoreCase(objective);
        this.objectiveIds = parseIds(objective);
        this.blacklistIds = parseIds(blacklist);
    }

    private static Set<String> parseIds(String raw) {
        Set<String> ids = new HashSet<>();
        if (raw == null || "NONE".equalsIgnoreCase(raw)) {
            return ids;
        }
        for (String part : raw.split(",")) {
            String id = part.trim().toUpperCase(Locale.ROOT);
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Vrai si l'objectif est "NONE" ou si l'ID fait partie de la liste (ID en majuscules). */
    public boolean acceptsObjective(String id) {
        return anyObjective || objectiveIds.contains(id);
    }

    /** Vrai si l'ID est dans la blacklist du tournoi (ID en majuscules). */
    public boolean isBlacklisted(String id) {
        return blacklistIds.contains(id);
    }

    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getTime() {
        return time;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getType() {
        return type;
    }

    public String getObjective() {
        return objective;
    }

    public String getBlacklist() {
        return blacklist;
    }

    public String getPointsName() {
        return pointsName;
    }

    public String getBossBar() {
        return bossBar;
    }

    public List<String> getWorldsBlacklist() {
        return worldsBlacklist;
    }
}
