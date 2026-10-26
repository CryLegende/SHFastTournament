package org.Cry_Legende.sHFastTournament;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class WinsManager {

    private final SHFastTournament plugin;
    private final File file;
    private FileConfiguration config;

    public WinsManager(SHFastTournament plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "wins.yml");
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Impossible de créer wins.yml");
            }
        }
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    // PlaceholderAPI peut interroger hors du thread principal
    public synchronized int getWins(UUID uuid) {
        return config.getInt(uuid.toString(), 0);
    }

    public synchronized void addWin(UUID uuid) {
        int wins = getWins(uuid) + 1;
        config.set(uuid.toString(), wins);
        save();
    }

    private void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder wins.yml");
        }
    }
}
