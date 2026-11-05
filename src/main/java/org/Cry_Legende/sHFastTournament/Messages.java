package org.Cry_Legende.sHFastTournament;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

/** Lecture de messages.yml. */
public final class Messages {

    private static FileConfiguration messages = new YamlConfiguration();

    private Messages() {
    }

    public static void load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
    }

    public static String get(String key, String def) {
        return messages.getString(key, def);
    }

    public static List<String> getList(String key) {
        return messages.getStringList(key);
    }
}
