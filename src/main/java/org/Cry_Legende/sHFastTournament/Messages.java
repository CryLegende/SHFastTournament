package org.Cry_Legende.sHFastTournament;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Lecture de messages.yml. */
public final class Messages {

    private static FileConfiguration messages = new YamlConfiguration();

    private Messages() {
    }

    /** Lit messages.yml au démarrage. Illisible : les textes par défaut du code sont utilisés et l'erreur est écrite dans la console. */
    public static void load(JavaPlugin plugin) {
        try {
            messages = read(plugin);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("messages.yml illisible, textes par défaut utilisés : " + describe(e));
            messages = new YamlConfiguration();
        }
    }

    /** Lit messages.yml sans rien remplacer : à appliquer avec {@link #apply} une fois tous les fichiers relus. */
    public static FileConfiguration read(JavaPlugin plugin) throws IOException, InvalidConfigurationException {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.load(file);
        return loaded;
    }

    public static void apply(FileConfiguration loaded) {
        messages = loaded;
    }

    /** Message d'une exception de lecture YAML sur une seule ligne. */
    public static String describe(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.replaceAll("\\s*\\R\\s*", " ").trim();
    }

    public static String get(String key, String def) {
        return messages.getString(key, def);
    }

    public static List<String> getList(String key) {
        return messages.getStringList(key);
    }
}
