package org.Cry_Legende.sHFastTournament;

import java.util.Map;
import java.util.UUID;

/**
 * Points d'un tournoi. Alimenté depuis le thread de chaque monde (mort d'un mob, dégâts, ramassage) : toutes les
 * méthodes sont utilisables depuis n'importe quel fil.
 */
interface ScoreBook {

    /** Ajoute des points à un joueur (il devient participant). */
    void add(UUID uuid, String name, double amount);

    /** Points du joueur, 0 s'il n'a pas encore marqué. */
    double get(UUID uuid);

    /** Vrai si le joueur a marqué au moins une fois. */
    boolean has(UUID uuid);

    /** Nombre de participants. */
    int size();

    /** Copie des points de tous les participants. */
    Map<UUID, Double> snapshot();

    /** Pseudo connu d'un participant, ou null (les points locaux ne gardent pas les pseudos). */
    default String nameOf(UUID uuid) {
        return null;
    }

    /** Appelé quand les points changent sans passer par {@link #add} (lecture de la base, en mode réseau). */
    default void onChange(Runnable listener) {
    }
}
