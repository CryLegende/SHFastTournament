package org.Cry_Legende.sHFastTournament;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JumpFilterTest {

    private static final UUID A = UUID.nameUUIDFromBytes("OfflinePlayer:a".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("OfflinePlayer:b".getBytes());

    @Test
    void secondJumpInsideTheIntervalIsRefused() {
        JumpFilter filter = new JumpFilter();
        assertTrue(filter.tryCount(A, 1_000, 500));
        assertFalse(filter.tryCount(A, 1_300, 500));
        // le refus ne repousse pas l'échéance : 500 ms après le saut compté, le suivant passe
        assertTrue(filter.tryCount(A, 1_500, 500));
    }

    @Test
    void playersDoNotShareTheirInterval() {
        JumpFilter filter = new JumpFilter();
        assertTrue(filter.tryCount(A, 1_000, 500));
        assertTrue(filter.tryCount(B, 1_100, 500));
    }

    @Test
    void forgottenPlayerCountsAgainAtOnce() {
        JumpFilter filter = new JumpFilter();
        assertTrue(filter.tryCount(A, 1_000, 500));
        filter.forget(A);
        assertTrue(filter.tryCount(A, 1_001, 500));
    }

    @Test
    void vanillaJumpIsAcceptedAndStepsOrLaunchesAreNot() {
        assertTrue(JumpFilter.riseIsJump(0.42, 0.5, 0));
        // pas d'une marche d'escalier ou rebond sur slime
        assertFalse(JumpFilter.riseIsJump(0.9, 0.5, 0));
        assertFalse(JumpFilter.riseIsJump(0, 0.5, 0));
        assertFalse(JumpFilter.riseIsJump(-0.1, 0.5, 0));
    }

    @Test
    void jumpBoostRaisesTheCeiling() {
        // Saut surélevé II (niveau 2) : 0,42 + 0,2 = 0,62 doit passer
        assertFalse(JumpFilter.riseIsJump(0.62, 0.5, 0));
        assertTrue(JumpFilter.riseIsJump(0.62, 0.5, 2));
    }
}
