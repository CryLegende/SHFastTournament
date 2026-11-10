package org.Cry_Legende.sHFastTournament;

import org.Cry_Legende.sHFastTournament.network.TournamentDatabase.Score;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Points d'un tournoi partagé entre serveurs. Chaque serveur garde ce que ses joueurs viennent de marquer
 * ({@code pending}), l'envoie à la base par incréments atomiques, puis relit les points de tout le monde
 * ({@code merged}). Ce que voit un joueur est la somme des trois couches :
 *
 * <pre>
 * merged    dernier relevé de la base : tous les serveurs, dont ce que ce serveur avait déjà envoyé
 * inFlight  lot en cours d'écriture, pas encore dans un relevé
 * pending   points marqués depuis le dernier lot
 * </pre>
 *
 * <p>Un point est dans une seule couche à la fois (le passage de {@code pending} à {@code inFlight} et de
 * {@code inFlight} à {@code merged} se fait sous verrou), donc le total d'un joueur ne baisse ni ne double jamais pendant
 * une synchronisation. Un joueur qui change de serveur ne perd rien : ses points en attente partent de l'ancien
 * serveur au prochain tour, ceux du nouveau s'y ajoutent, la base additionne.</p>
 *
 * <p>Un lot dont l'écriture échoue retourne dans {@code pending} (rien n'est perdu, il repart au tour suivant) ;
 * un lot écrit mais dont la relecture échoue est ajouté au relevé local, puis remplacé par le prochain relevé.</p>
 */
final class NetworkScoreBook implements ScoreBook {

    /** Là où les points sont écrits et relus : la base, ou une fausse base dans les tests. */
    interface Store {

        /** Ajoute les points, tout ou rien. */
        void add(Map<UUID, Double> deltas, Map<UUID, String> names) throws SQLException;

        /** Points cumulés de tous les joueurs. */
        List<Score> read() throws SQLException;
    }

    private final Object lock = new Object();
    private final Object cycleLock = new Object();
    private final ConcurrentHashMap<UUID, Double> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> names = new ConcurrentHashMap<>();
    // lock protège merged et inFlight, et le passage de pending à inFlight
    private Map<UUID, Double> merged = Map.of();
    private Map<UUID, Double> inFlight = Map.of();
    private volatile Runnable listener = () -> {
    };

    @Override
    public void add(UUID uuid, String name, double amount) {
        if (name != null && !name.isEmpty()) {
            names.putIfAbsent(uuid, name);
        }
        pending.merge(uuid, amount, Double::sum);
    }

    @Override
    public double get(UUID uuid) {
        synchronized (lock) {
            return merged.getOrDefault(uuid, 0.0) + inFlight.getOrDefault(uuid, 0.0) + pending.getOrDefault(uuid, 0.0);
        }
    }

    @Override
    public boolean has(UUID uuid) {
        synchronized (lock) {
            return merged.containsKey(uuid) || inFlight.containsKey(uuid) || pending.containsKey(uuid);
        }
    }

    @Override
    public int size() {
        synchronized (lock) {
            Set<UUID> all = new HashSet<>(merged.keySet());
            all.addAll(inFlight.keySet());
            all.addAll(pending.keySet());
            return all.size();
        }
    }

    @Override
    public Map<UUID, Double> snapshot() {
        synchronized (lock) {
            Map<UUID, Double> all = new HashMap<>(merged);
            inFlight.forEach((uuid, points) -> all.merge(uuid, points, Double::sum));
            pending.forEach((uuid, points) -> all.merge(uuid, points, Double::sum));
            return all;
        }
    }

    @Override
    public String nameOf(UUID uuid) {
        return names.get(uuid);
    }

    @Override
    public void onChange(Runnable listener) {
        this.listener = listener;
    }

    /**
     * Un tour de synchronisation : envoie les points en attente, puis relit ceux de tout le monde. À appeler hors du fil
     * principal ; les appels se mettent en file, un tour à la fois.
     */
    void cycle(Store store) throws SQLException {
        synchronized (cycleLock) {
            Map<UUID, Double> batch = take();
            if (!batch.isEmpty()) {
                try {
                    store.add(batch, namesOf(batch));
                } catch (SQLException | RuntimeException e) {
                    restore(batch);
                    throw e;
                }
            }
            List<Score> rows;
            try {
                rows = store.read();
            } catch (SQLException | RuntimeException e) {
                settle(batch);
                throw e;
            }
            install(rows);
        }
        listener.run();
    }

    /** Envoie seulement les points en attente (arrêt du serveur). */
    void flush(Store store) throws SQLException {
        synchronized (cycleLock) {
            Map<UUID, Double> batch = take();
            if (batch.isEmpty()) {
                return;
            }
            try {
                store.add(batch, namesOf(batch));
            } catch (SQLException | RuntimeException e) {
                restore(batch);
                throw e;
            }
            settle(batch);
        }
    }

    /** Sort les points en attente et les range dans {@code inFlight}, sans qu'un lecteur voie un instant sans eux. */
    private Map<UUID, Double> take() {
        synchronized (lock) {
            Map<UUID, Double> batch = new HashMap<>();
            for (UUID uuid : pending.keySet()) {
                Double points = pending.remove(uuid);
                if (points != null) {
                    batch.put(uuid, points);
                }
            }
            inFlight = batch;
            return batch;
        }
    }

    private Map<UUID, String> namesOf(Map<UUID, Double> batch) {
        Map<UUID, String> out = new HashMap<>();
        for (UUID uuid : batch.keySet()) {
            String name = names.get(uuid);
            if (name != null) {
                out.put(uuid, name);
            }
        }
        return out;
    }

    /** L'écriture a échoué : le lot repart au prochain tour. */
    private void restore(Map<UUID, Double> batch) {
        synchronized (lock) {
            for (Map.Entry<UUID, Double> entry : batch.entrySet()) {
                pending.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
            inFlight = Map.of();
        }
    }

    /** Le lot est écrit mais la relecture a échoué : on l'ajoute au dernier relevé en attendant le suivant. */
    private void settle(Map<UUID, Double> batch) {
        synchronized (lock) {
            Map<UUID, Double> all = new HashMap<>(merged);
            batch.forEach((uuid, points) -> all.merge(uuid, points, Double::sum));
            merged = all;
            inFlight = Map.of();
        }
    }

    private void install(List<Score> rows) {
        Map<UUID, Double> fresh = new HashMap<>();
        for (Score row : rows) {
            fresh.put(row.uuid(), row.score());
            if (row.name() != null && !row.name().isEmpty()) {
                names.putIfAbsent(row.uuid(), row.name());
            }
        }
        synchronized (lock) {
            merged = fresh;
            inFlight = Map.of();
        }
    }
}
