package org.Cry_Legende.sHFastTournament;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

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

    public Tournament(String id, ConfigurationSection section) {
        this.id = id;
        this.enabled = section.getBoolean("enabled", false);
        this.time = section.getInt("time", 1);
        this.displayName = section.getString("display-name", "Tournament");
        this.description = section.getString("description", "");
        this.type = section.getString("type", "NONE");
        this.objective = section.getString("objective", "NONE");
        this.blacklist = section.getString("blacklist", "NONE");
        this.pointsName = section.getString("points-name", "points");
        this.bossBar = section.getString("boss-bar", "NONE");
        this.worldsBlacklist = section.getStringList("worlds-blacklist");
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
