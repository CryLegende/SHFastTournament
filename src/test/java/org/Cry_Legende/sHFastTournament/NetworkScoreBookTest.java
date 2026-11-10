package org.Cry_Legende.sHFastTournament;

import org.Cry_Legende.sHFastTournament.network.TournamentDatabase.Score;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkScoreBookTest {

    /** Base partagée de deux serveurs : une carte de totaux, incrémentée atomiquement. */
    private static final class Shared {

        private final Map<UUID, Double> totals = new HashMap<>();
        private final Map<UUID, String> known = new HashMap<>();

        synchronized double total(UUID uuid) {
            return totals.getOrDefault(uuid, 0.0);
        }

        NetworkScoreBook.Store store(BooleanSupplier failWrite, BooleanSupplier failRead) {
            return new NetworkScoreBook.Store() {
                @Override
                public void add(Map<UUID, Double> deltas, Map<UUID, String> names) throws SQLException {
                    if (failWrite.getAsBoolean()) {
                        throw new SQLException("écriture refusée");
                    }
                    synchronized (Shared.this) {
                        deltas.forEach((uuid, points) -> totals.merge(uuid, points, Double::sum));
                        known.putAll(names);
                    }
                }

                @Override
                public List<Score> read() throws SQLException {
                    if (failRead.getAsBoolean()) {
                        throw new SQLException("lecture refusée");
                    }
                    synchronized (Shared.this) {
                        List<Score> rows = new ArrayList<>();
                        totals.forEach((uuid, points) -> rows.add(new Score(uuid, known.getOrDefault(uuid, "joueur"), points)));
                        return rows;
                    }
                }
            };
        }

        NetworkScoreBook.Store store() {
            return store(() -> false, () -> false);
        }
    }

    private static final UUID PLAYER = UUID.nameUUIDFromBytes("OfflinePlayer:Cry".getBytes());
    private static final UUID OTHER = UUID.nameUUIDFromBytes("OfflinePlayer:Other".getBytes());

    @Test
    void pointsOfTwoServersAddUp() throws Exception {
        Shared db = new Shared();
        NetworkScoreBook a = new NetworkScoreBook();
        NetworkScoreBook b = new NetworkScoreBook();
        a.add(PLAYER, "Cry", 5);
        b.add(PLAYER, "Cry", 7);
        a.cycle(db.store());
        b.cycle(db.store());
        a.cycle(db.store());
        assertEquals(12, db.total(PLAYER));
        assertEquals(12, a.get(PLAYER));
        assertEquals(12, b.get(PLAYER));
    }

    @Test
    void playerMovingToTheOtherServerLosesNothingAndCountsNothingTwice() throws Exception {
        Shared db = new Shared();
        NetworkScoreBook a = new NetworkScoreBook();
        NetworkScoreBook b = new NetworkScoreBook();
        a.add(PLAYER, "Cry", 10);          // marqués sur A, pas encore envoyés
        b.add(PLAYER, "Cry", 4);           // le joueur est passé sur B et continue
        b.cycle(db.store());
        assertEquals(4, b.get(PLAYER), "B ne voit pas encore les points de A, mais garde les siens");
        a.cycle(db.store());               // A envoie ce qu'il avait gardé
        b.cycle(db.store());
        a.cycle(db.store());
        assertEquals(14, db.total(PLAYER));
        assertEquals(14, a.get(PLAYER));
        assertEquals(14, b.get(PLAYER));
        assertEquals(1, b.size());
    }

    @Test
    void failedWriteIsRetriedWithoutLosingOrRepeatingPoints() throws Exception {
        Shared db = new Shared();
        AtomicBoolean broken = new AtomicBoolean(true);
        NetworkScoreBook book = new NetworkScoreBook();
        book.add(PLAYER, "Cry", 5);
        assertThrows(SQLException.class, () -> book.cycle(db.store(broken::get, () -> false)));
        assertEquals(5, book.get(PLAYER), "les points restent visibles");
        assertEquals(0, db.total(PLAYER));
        book.add(PLAYER, "Cry", 2);
        broken.set(false);
        book.cycle(db.store(broken::get, () -> false));
        assertEquals(7, db.total(PLAYER));
        assertEquals(7, book.get(PLAYER));
    }

    @Test
    void failedReadAfterAWriteDoesNotCountTheBatchTwice() throws Exception {
        Shared db = new Shared();
        AtomicBoolean broken = new AtomicBoolean(true);
        NetworkScoreBook book = new NetworkScoreBook();
        book.add(PLAYER, "Cry", 5);
        assertThrows(SQLException.class, () -> book.cycle(db.store(() -> false, broken::get)));
        assertEquals(5, db.total(PLAYER), "l'écriture a abouti");
        assertEquals(5, book.get(PLAYER));
        broken.set(false);
        book.cycle(db.store(() -> false, broken::get));
        assertEquals(5, db.total(PLAYER), "rien n'est renvoyé");
        assertEquals(5, book.get(PLAYER));
    }

    @Test
    void everyPlayerIsCountedOnceInSizeAndSnapshot() throws Exception {
        Shared db = new Shared();
        NetworkScoreBook a = new NetworkScoreBook();
        NetworkScoreBook b = new NetworkScoreBook();
        b.add(OTHER, "Other", 3);
        b.cycle(db.store());
        a.add(PLAYER, "Cry", 1);
        a.cycle(db.store());               // A connaît OTHER par la base
        a.add(OTHER, "Other", 2);          // et le fait marquer ici : un seul participant
        assertEquals(2, a.size());
        assertEquals(5.0, a.snapshot().get(OTHER).doubleValue());
        assertEquals(1.0, a.snapshot().get(PLAYER).doubleValue());
        assertTrue(a.has(OTHER));
        assertFalse(new NetworkScoreBook().has(PLAYER));
        assertEquals("Other", a.nameOf(OTHER));
    }

    @Test
    void concurrentActivityOnTwoServersWithFailuresIsExactAndNeverGoesBackwards() throws Exception {
        Shared db = new Shared();
        Random dice = new Random(42);
        BooleanSupplier flaky = () -> {
            synchronized (dice) {
                return dice.nextInt(10) == 0;
            }
        };
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            players.add(UUID.nameUUIDFromBytes(("OfflinePlayer:P" + i).getBytes()));
        }
        NetworkScoreBook[] books = {new NetworkScoreBook(), new NetworkScoreBook()};
        Map<UUID, Double> added = new HashMap<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();

        // Les joueurs marquent tantôt sur un serveur, tantôt sur l'autre (ils « changent de serveur » sans cesse)
        for (int t = 0; t < 4; t++) {
            int seed = t;
            workers.add(new Thread(() -> {
                Random random = new Random(seed);
                try {
                    go.await();
                    for (int i = 0; i < 5000; i++) {
                        UUID player = players.get(random.nextInt(players.size()));
                        int points = 1 + random.nextInt(5);
                        books[random.nextInt(2)].add(player, "joueur", points);
                        synchronized (added) {
                            added.merge(player, (double) points, Double::sum);
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
        }
        // Chaque serveur synchronise en boucle, avec des pannes de base au hasard
        for (int s = 0; s < 2; s++) {
            NetworkScoreBook book = books[s];
            workers.add(new Thread(() -> {
                try {
                    go.await();
                    while (!done.get()) {
                        try {
                            book.cycle(db.store(flaky, flaky));
                        } catch (SQLException expected) {
                            // panne simulée : le tour suivant reprend
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
        }
        // Un lecteur vérifie que le total d'un joueur ne baisse jamais, même pendant les pannes
        workers.add(new Thread(() -> {
            Map<UUID, Double> last = new HashMap<>();
            try {
                go.await();
                while (!done.get()) {
                    for (NetworkScoreBook book : books) {
                        for (UUID player : players) {
                            double now = book.get(player);
                            double before = last.getOrDefault(player, 0.0);
                            // un autre serveur peut avoir envoyé plus : seule la baisse est une faute
                            if (now + 1e-9 < before && book == books[0]) {
                                failure.compareAndSet(null, new AssertionError("total en baisse : " + before + " -> " + now));
                            }
                            if (book == books[0]) {
                                last.put(player, now);
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        }));

        workers.forEach(Thread::start);
        go.countDown();
        for (int i = 0; i < 4; i++) {
            workers.get(i).join();
        }
        done.set(true);
        for (int i = 4; i < workers.size(); i++) {
            workers.get(i).join();
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }

        // Plus aucune panne : deux tours pour que chaque serveur voie tout
        for (int round = 0; round < 2; round++) {
            for (NetworkScoreBook book : books) {
                book.cycle(db.store());
            }
        }
        for (UUID player : players) {
            double expected = added.get(player);
            assertEquals(expected, db.total(player), "total en base de " + player);
            assertEquals(expected, books[0].get(player), "vue du serveur A");
            assertEquals(expected, books[1].get(player), "vue du serveur B");
        }
    }
}
