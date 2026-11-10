package org.Cry_Legende.sHFastTournament.network;

import com.google.gson.JsonObject;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Ce dont les tournois en réseau ont besoin du bus. Seule {@link ShNetworkBus} connaît SHNetwork : sans lui, aucune
 * classe de ce paquet qui touche à son API n'est chargée.
 */
public interface NetBus {

    /** Candidature à une tâche de meneur. */
    interface Leader {

        boolean isLeader();

        void release();
    }

    /** Joueur connecté quelque part sur le réseau. */
    record Found(UUID uuid, String name) {
    }

    /** Joueurs connectés sur tout le réseau, ce serveur compris. */
    int online();

    /** Cherche un joueur connecté, sur ce serveur ou sur un autre. Ne bloque jamais. */
    Optional<Found> find(String nameOrUuid);

    /** Publie sur un canal ; ce serveur reçoit aussi son propre message. Ne bloque jamais. */
    void publish(String channel, JsonObject message);

    /**
     * Écoute un canal.
     *
     * @param mainThread vrai pour recevoir sur le fil principal ; faux pour recevoir sur le fil du bus, où le
     *                   gestionnaire ne doit ni attendre ni toucher au disque ou à la base
     */
    void listen(String channel, Consumer<JsonObject> handler, boolean mainThread);

    /** Exécute une commande console là où se trouve le joueur, ou à sa prochaine connexion s'il est hors ligne. */
    void runFor(String player, String command);

    /** Se porte candidat pour une tâche qui ne tourne que sur un serveur à la fois. */
    Leader leader(String task, Runnable onAcquire, Runnable onLose);
}
