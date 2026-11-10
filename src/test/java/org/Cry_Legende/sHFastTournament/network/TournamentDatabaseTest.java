package org.Cry_Legende.sHFastTournament.network;

import org.Cry_Legende.sHFastTournament.network.TournamentDatabase.Result;
import org.Cry_Legende.sHFastTournament.network.TournamentDatabase.Run;
import org.Cry_Legende.sHFastTournament.network.TournamentDatabase.Score;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Les règles « une seule fois » de la base, sur H2 en mode MariaDB : un seul tournoi ouvert, une seule conclusion,
 * un classement écrit une fois, une récompense réservée par un seul serveur, des points qui s'additionnent.
 */
class TournamentDatabaseTest {

    private static final UUID CRY = UUID.nameUUIDFromBytes("OfflinePlayer:Cry".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("OfflinePlayer:Bob".getBytes());
    private static final UUID ZED = UUID.nameUUIDFromBytes("OfflinePlayer:Zed".getBytes());

    private JdbcConnectionPool pool;
    private TournamentDatabase db;

    @BeforeEach
    void setUp() throws Exception {
        pool = JdbcConnectionPool.create("jdbc:h2:mem:ft" + UUID.randomUUID() + ";MODE=MariaDB;DB_CLOSE_DELAY=-1;IGNORECASE=TRUE", "sa", "");
        pool.setMaxConnections(40);
        db = new TournamentDatabase(pool);
        db.createTables();
    }

    @AfterEach
    void tearDown() {
        pool.dispose();
    }

    private static <T> List<T> inParallel(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                go.await();
                return task.call();
            }));
        }
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> future : futures) {
            out.add(future.get(60, TimeUnit.SECONDS));
        }
        executor.shutdown();
        return out;
    }

    private static List<Result> podium() {
        return List.of(
            new Result(CRY, "Cry", 1, 30, "top1", false),
            new Result(BOB, "Bob", 2, 20, "top2", false),
            new Result(ZED, "Zed", 3, 1, "none", false));
    }

    @Test
    void createTablesTwiceIsHarmless() throws Exception {
        db.createTables();
        assertNull(db.openRun());
    }

    @Test
    void onlyOneTournamentIsOpenAtATime() throws Exception {
        assertTrue(db.insertRun("aaaaaaaaaaaa", "Bucheron", 1_000, 601_000));
        assertFalse(db.insertRun("bbbbbbbbbbbb", "Mineur", 2_000, 602_000));
        Run open = db.openRun();
        assertNotNull(open);
        assertEquals("aaaaaaaaaaaa", open.id());
        assertEquals("Bucheron", open.tournament());
        assertEquals(601_000, open.endsAt());
        assertEquals(TournamentDatabase.RUNNING, open.status());
        // une fois la conclusion réservée, un nouveau tournoi peut s'ouvrir
        assertTrue(db.claimEnding("aaaaaaaaaaaa"));
        assertNull(db.openRun());
        assertTrue(db.insertRun("bbbbbbbbbbbb", "Mineur", 2_000, 602_000));
    }

    @Test
    void twoServersStartingAtTheSameInstantOpenOnlyOne() throws Exception {
        AtomicInteger ids = new AtomicInteger();
        List<Boolean> results = inParallel(8, () -> db.insertRun(String.format("%012d", ids.incrementAndGet()), "Bucheron", 1_000, 601_000));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
    }

    @Test
    void endingIsClaimedByOneCallerAndNeverAfterAStop() throws Exception {
        db.insertRun("aaaaaaaaaaaa", "Bucheron", 1_000, 601_000);
        List<Boolean> claims = inParallel(8, () -> db.claimEnding("aaaaaaaaaaaa"));
        assertEquals(1, claims.stream().filter(Boolean::booleanValue).count());
        assertFalse(db.stopRun("aaaaaaaaaaaa"), "un tournoi en cours de conclusion ne s'arrête plus");
        assertTrue(db.markDone("aaaaaaaaaaaa"));
        assertFalse(db.markDone("aaaaaaaaaaaa"));
        assertEquals(TournamentDatabase.DONE, db.run("aaaaaaaaaaaa").status());
    }

    @Test
    void stoppedTournamentCannotBeConcluded() throws Exception {
        db.insertRun("aaaaaaaaaaaa", "Bucheron", 1_000, 601_000);
        assertTrue(db.stopRun("aaaaaaaaaaaa"));
        assertFalse(db.stopRun("aaaaaaaaaaaa"));
        assertFalse(db.claimEnding("aaaaaaaaaaaa"));
        assertNull(db.openRun());
    }

    @Test
    void unfinishedListsRunningAndEndingOnly() throws Exception {
        db.insertRun("aaaaaaaaaaaa", "Bucheron", 1_000, 601_000);
        db.claimEnding("aaaaaaaaaaaa");
        db.insertRun("bbbbbbbbbbbb", "Mineur", 2_000, 602_000);
        db.insertRun("cccccccccccc", "Jogger", 0, 1);   // refusé : bbbb est ouvert
        db.stopRun("bbbbbbbbbbbb");
        db.insertRun("dddddddddddd", "Jogger", 3_000, 603_000);
        List<Run> unfinished = db.unfinished();
        assertEquals(List.of("aaaaaaaaaaaa", "dddddddddddd"), unfinished.stream().map(Run::id).toList());
        assertEquals(List.of(TournamentDatabase.ENDING, TournamentDatabase.RUNNING), unfinished.stream().map(Run::status).toList());
    }

    @Test
    void pointsFromTwoServersAddUpWhateverTheOrder() throws Exception {
        db.insertRun("aaaaaaaaaaaa", "Bucheron", 1_000, 601_000);
        db.addScores("aaaaaaaaaaaa", Map.of(CRY, 0.0), Map.of(CRY, "Cry"));   // ligne créée avant la course
        int writes = 200;
        inParallel(2, () -> {
            for (int i = 0; i < writes; i++) {
                db.addScores("aaaaaaaaaaaa", Map.of(CRY, 2.0, BOB, 1.0), Map.of(CRY, "Cry", BOB, "Bob"));
            }
            return null;
        });
        List<Score> scores = db.scores("aaaaaaaaaaaa");
        assertEquals(2, scores.size());
        for (Score s : scores) {
            assertEquals(s.uuid().equals(CRY) ? 2.0 * writes * 2 : 1.0 * writes * 2, s.score(), s.name());
        }
    }

    @Test
    void anUnknownNameNeverErasesTheKnownOne() throws Exception {
        db.addScores("aaaaaaaaaaaa", Map.of(CRY, 1.0), Map.of(CRY, "Cry"));
        db.addScores("aaaaaaaaaaaa", Map.of(CRY, 1.0), Map.of());
        Score score = db.scores("aaaaaaaaaaaa").get(0);
        assertEquals("Cry", score.name());
        assertEquals(2.0, score.score());
    }

    @Test
    void pointsOfAnotherTournamentAreKeptApart() throws Exception {
        db.addScores("aaaaaaaaaaaa", Map.of(CRY, 5.0), Map.of(CRY, "Cry"));
        db.addScores("bbbbbbbbbbbb", Map.of(CRY, 7.0), Map.of(CRY, "Cry"));
        assertEquals(5.0, db.scores("aaaaaaaaaaaa").get(0).score());
        assertEquals(7.0, db.scores("bbbbbbbbbbbb").get(0).score());
    }

    @Test
    void resultsAreWrittenOnceAndTheWinIsCountedOnce() throws Exception {
        db.insertRun("aaaaaaaaaaaa", "Bucheron", 1_000, 601_000);
        db.claimEnding("aaaaaaaaaaaa");
        db.saveResults("aaaaaaaaaaaa", podium());
        // La conclusion est rejouée après une panne, avec des points qui auraient changé entre-temps : rien ne change
        db.saveResults("aaaaaaaaaaaa", List.of(
            new Result(BOB, "Bob", 1, 99, "top1", false),
            new Result(CRY, "Cry", 2, 98, "top2", false),
            new Result(ZED, "Zed", 3, 1, "none", false)));
        List<Result> results = db.results("aaaaaaaaaaaa");
        assertEquals(List.of("Cry", "Bob", "Zed"), results.stream().map(Result::name).toList());
        assertEquals(1, db.wins(CRY));
        assertEquals(0, db.wins(BOB));
        // sans récompense : marqué payé d'office
        assertTrue(results.get(2).paid());
        assertFalse(results.get(0).paid());
    }

    @Test
    void winsOfDifferentTournamentsAdd() throws Exception {
        db.saveResults("aaaaaaaaaaaa", podium());
        db.saveResults("bbbbbbbbbbbb", podium());
        assertEquals(2, db.wins(CRY));
    }

    @Test
    void concurrentConclusionsCountTheWinOnce() throws Exception {
        inParallel(6, () -> {
            db.saveResults("aaaaaaaaaaaa", podium());
            return null;
        });
        assertEquals(3, db.results("aaaaaaaaaaaa").size());
        assertEquals(1, db.wins(CRY));
    }

    @Test
    void rewardIsClaimedByOneCallerOnly() throws Exception {
        db.saveResults("aaaaaaaaaaaa", podium());
        List<Boolean> claims = inParallel(8, () -> db.claimReward("aaaaaaaaaaaa", CRY));
        assertEquals(1, claims.stream().filter(Boolean::booleanValue).count());
        assertFalse(db.claimReward("aaaaaaaaaaaa", CRY));
        assertFalse(db.claimReward("aaaaaaaaaaaa", ZED), "un joueur sans récompense n'en réserve pas");
        assertTrue(db.claimReward("aaaaaaaaaaaa", BOB));
        assertTrue(db.results("aaaaaaaaaaaa").stream().allMatch(Result::paid));
    }

    @Test
    void importKeepsTheTotalsAlreadyInTheDatabase() throws Exception {
        db.saveResults("aaaaaaaaaaaa", podium());                       // Cry a déjà 1 victoire en base
        int[] first = db.importWins(Map.of(CRY, 40, BOB, 3, ZED, 0), Map.of(CRY, "Cry", BOB, "Bob"));
        assertEquals(1, first[0]);                                       // Bob
        assertEquals(1, first[1]);                                       // Cry déjà là ; Zed à 0 est ignoré
        assertEquals(1, db.wins(CRY));
        assertEquals(3, db.wins(BOB));
        int[] again = db.importWins(Map.of(CRY, 40, BOB, 3), Map.of());
        assertEquals(0, again[0]);
        assertEquals(3, db.wins(BOB));
    }

    @Test
    void purgeRemovesOldFinishedTournamentsAndKeepsTheOthers() throws Exception {
        db.insertRun("aaaaaaaaaaaa", "Bucheron", 1_000, 2_000);
        db.addScores("aaaaaaaaaaaa", Map.of(CRY, 5.0), Map.of(CRY, "Cry"));
        db.claimEnding("aaaaaaaaaaaa");
        db.saveResults("aaaaaaaaaaaa", podium());
        db.markDone("aaaaaaaaaaaa");
        db.insertRun("bbbbbbbbbbbb", "Mineur", 3_000, 4_000);           // encore en cours : jamais purgé
        assertEquals(1, db.purge(10_000));
        assertNull(db.run("aaaaaaaaaaaa"));
        assertTrue(db.scores("aaaaaaaaaaaa").isEmpty());
        assertTrue(db.results("aaaaaaaaaaaa").isEmpty());
        assertNotNull(db.run("bbbbbbbbbbbb"));
        assertEquals(1, db.wins(CRY), "les victoires restent");
    }
}
