package org.Cry_Legende.sHFastTournament;

import org.bukkit.plugin.java.JavaPlugin;

public class SHFastTournament extends JavaPlugin {

    private static SHFastTournament instance;
    private TournamentManager tournamentManager;
    private WinsManager winsManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        saveConfig();

        // --- initialisation winsManager
        winsManager = new WinsManager(this);

        // --- registration PlaceholderAPI
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderHook(this).register();
        }

        tournamentManager = new TournamentManager();
        tournamentManager.init();

        if (getCommand("tournament") != null) {
            getCommand("tournament").setExecutor(new TournamentCommand());
            getCommand("tournament").setTabCompleter(new TournamentTabCompleter()); // Ajout du TabCompleter
        } else {
            getLogger().warning("La commande 'tournament' n'est pas définie dans plugin.yml");
        }

        getServer().getPluginManager().registerEvents(new TournamentListener(), this);

        getLogger().info("SHFastTournament activé.");
    }

    @Override
    public void onDisable() {
        if (tournamentManager != null) {
            tournamentManager.shutdown();
        }
        getLogger().info("SHFastTournament désactivé.");
    }

    public WinsManager getWinsManager() {
        return winsManager;
    }

    public static SHFastTournament getInstance() {
        return instance;
    }

    public TournamentManager getTournamentManager() {
        return tournamentManager;
    }
}