package org.Cry_Legende.sHFastTournament;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Règles de comptage des sauts d'un tournoi JUMP : un saut doit être espacé du précédent
 * et monter à la hauteur d'un vrai saut. Sans état Bukkit, pour rester testable.
 */
final class JumpFilter {

    /** Vitesse verticale d'un saut vanilla : le joueur monte de 0,42 bloc au premier tick. */
    static final double VANILLA_JUMP_RISE = 0.42;
    /** Chaque niveau de Saut surélevé ajoute 0,1 à la vitesse verticale du saut. */
    static final double JUMP_BOOST_PER_LEVEL = 0.1;

    // Alimenté depuis le thread de chaque région : collection concurrente
    private final Map<UUID, Long> lastCounted = new ConcurrentHashMap<>();

    /**
     * Vrai (et mémorise l'instant) si le joueur n'a pas eu de saut compté depuis {@code minIntervalMillis}.
     */
    boolean tryCount(UUID player, long nowMillis, long minIntervalMillis) {
        boolean[] counted = {false};
        lastCounted.compute(player, (id, last) -> {
            if (last == null || nowMillis - last >= minIntervalMillis) {
                counted[0] = true;
                return nowMillis;
            }
            return last;
        });
        return counted[0];
    }

    void forget(UUID player) {
        lastCounted.remove(player);
    }

    /**
     * Vrai si la montée du premier déplacement correspond à un saut : strictement positive et sous
     * {@code maxRise} (+ 0,1 par niveau de Saut surélevé). Au-delà, c'est un rebond, un souffle ou un canon.
     *
     * @param boostLevel niveau de l'effet Saut surélevé (0 sans effet)
     */
    static boolean riseIsJump(double rise, double maxRise, int boostLevel) {
        if (rise <= 0) return false;
        return rise <= maxRise + Math.max(0, boostLevel) * JUMP_BOOST_PER_LEVEL;
    }
}
