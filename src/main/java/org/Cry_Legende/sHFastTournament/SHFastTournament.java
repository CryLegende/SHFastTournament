package org.Cry_Legende.sHFastTournament;

import org.Cry_Legende.sHFastTournament.network.NetBus;
import org.Cry_Legende.sHFastTournament.network.ShNetworkBus;
import org.Cry_Legende.sHFastTournament.network.TournamentDatabase;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

public class SHFastTournament extends JavaPlugin {

    private static SHFastTournament instance;
    private TournamentManager tournamentManager;
    private WinsManager winsManager;
    // Non nul seulement avec SHNetwork : un seul tournoi pour tout le réseau, points et victoires en base
    private volatile NetworkTournaments network;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        Messages.load(this);

        // --- initialisation winsManager
        winsManager = new WinsManager(this);

        // --- registration PlaceholderAPI
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderHook(this).register();
        }

        tournamentManager = new TournamentManager();
        tournamentManager.loadTournaments();
        enableNetwork();
        // Avec SHNetwork, seul le meneur lance des tournois automatiques
        if (network == null) {
            tournamentManager.startAutoScheduler();
        }

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
        if (network != null) {
            network.disable();
        }
        getLogger().info("SHFastTournament désactivé.");
    }

    /**
     * Relit config.yml et messages.yml. Les deux fichiers sont lus avant de remplacer quoi que ce soit : si l'un est
     * invalide, l'ancienne configuration reste active. Les définitions de tournois sont remplacées dans le gestionnaire
     * existant ; un tournoi en cours finit avec les anciennes. wins.yml et la base ne sont pas touchés ; la section
     * reseau n'est lue qu'au démarrage.
     *
     * @return null si tout est rechargé, sinon le fichier et l'erreur
     */
    String reload() {
        try {
            new YamlConfiguration().load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            return "config.yml : " + Messages.describe(e);
        }
        FileConfiguration loadedMessages;
        try {
            loadedMessages = Messages.read(this);
        } catch (IOException | InvalidConfigurationException e) {
            return "messages.yml : " + Messages.describe(e);
        }
        reloadConfig();
        Messages.apply(loadedMessages);
        tournamentManager.loadTournaments();
        return null;
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et 'reseau.actif' est vrai. Les classes qui touchent à SHNetwork
     * ne sont résolues qu'ici : sans lui, rien n'est chargé et le plugin garde son fonctionnement d'origine.
     */
    private void enableNetwork() {
        FileConfiguration config = getConfig();
        if (!config.getBoolean("reseau.actif", true)) return;
        if (!getServer().getPluginManager().isPluginEnabled("SHNetwork")) return;
        String url = config.getString("reseau.base-de-donnees.url");
        if (url == null || url.isBlank()) {
            getLogger().warning("SHNetwork présent mais 'reseau.base-de-donnees' manque dans config.yml : tournois locaux à ce serveur.");
            return;
        }
        TournamentDatabase db = null;
        NetworkTournaments games = null;
        try {
            NetBus bus = ShNetworkBus.open();
            db = TournamentDatabase.connect(
                url,
                config.getString("reseau.base-de-donnees.utilisateur"),
                config.getString("reseau.base-de-donnees.mot-de-passe"),
                Math.max(250, config.getInt("reseau.base-de-donnees.delai-ms", 3000)));
            games = new NetworkTournaments(this, tournamentManager, bus, db, config.getString("reseau.canal", "fasttournament"));
            games.enable();
            network = games;
            getLogger().info("SHNetwork détecté : un seul tournoi pour tout le réseau, points et victoires en base.");
        } catch (RuntimeException | LinkageError e) {
            network = null;
            if (games != null) {
                games.disable();
            } else if (db != null) {
                db.close();
            }
            getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable, tournois locaux à ce serveur : " + e);
        }
    }

    public WinsManager getWinsManager() {
        return winsManager;
    }

    /** Victoires d'un joueur : celles du réseau (base) avec SHNetwork, wins.yml sinon. */
    public int getWins(UUID uuid) {
        NetworkTournaments net = network;
        return net != null ? net.wins(uuid) : winsManager.getWins(uuid);
    }

    /** Le tournoi partagé du réseau, ou null sur un serveur seul. */
    NetworkTournaments network() {
        return network;
    }

    public static SHFastTournament getInstance() {
        return instance;
    }

    public TournamentManager getTournamentManager() {
        return tournamentManager;
    }
}
