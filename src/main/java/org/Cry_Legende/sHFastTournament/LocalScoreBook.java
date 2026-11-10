package org.Cry_Legende.sHFastTournament;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Points gardés en mémoire sur ce serveur seul : le fonctionnement sans SHNetwork. */
final class LocalScoreBook implements ScoreBook {

    private final Map<UUID, Double> scores = new ConcurrentHashMap<>();

    @Override
    public void add(UUID uuid, String name, double amount) {
        scores.merge(uuid, amount, Double::sum);
    }

    @Override
    public double get(UUID uuid) {
        return scores.getOrDefault(uuid, 0.0);
    }

    @Override
    public boolean has(UUID uuid) {
        return scores.containsKey(uuid);
    }

    @Override
    public int size() {
        return scores.size();
    }

    @Override
    public Map<UUID, Double> snapshot() {
        return new HashMap<>(scores);
    }
}
