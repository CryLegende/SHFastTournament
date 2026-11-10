package org.Cry_Legende.sHFastTournament;

import org.Cry_Legende.sHFastTournament.network.TournamentDatabase.Result;
import org.Cry_Legende.sHFastTournament.network.TournamentDatabase.Score;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingTest {

    private static Score score(String name, double points) {
        return new Score(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes()), name, points);
    }

    private static List<String> names(List<Result> ranking) {
        return ranking.stream().map(Result::name).toList();
    }

    private static List<String> tiers(List<Result> ranking) {
        return ranking.stream().map(Result::tier).toList();
    }

    @Test
    void podiumThenParticipationFromHalfTheThirdScore() {
        // le 3e a 10 points : la participation commence à 5
        List<Result> ranking = Ranking.rank(List.of(score("a", 10), score("b", 30), score("c", 20), score("d", 9), score("e", 4)));
        assertEquals(List.of("b", "c", "a", "d", "e"), names(ranking));
        assertEquals(List.of("top1", "top2", "top3", "participation", "none"), tiers(ranking));
        assertEquals(List.of(1, 2, 3, 4, 5), ranking.stream().map(Result::place).toList());
    }

    @Test
    void scoreEqualToTheThresholdIsRewarded() {
        List<Result> ranking = Ranking.rank(List.of(score("a", 10), score("b", 20), score("c", 30), score("d", 5), score("e", 4.9)));
        assertEquals(List.of("top1", "top2", "top3", "participation", "none"), tiers(ranking));
    }

    @Test
    void tiesAreBrokenByNameIgnoringCase() {
        List<Result> ranking = Ranking.rank(List.of(score("zed", 5), score("Bob", 5), score("alice", 5)));
        assertEquals(List.of("alice", "Bob", "zed"), names(ranking));
    }

    @Test
    void fewerThanThreePlayersAreAllOnTheirPodium() {
        assertEquals(List.of("top1", "top2"), tiers(Ranking.rank(List.of(score("a", 1), score("b", 2)))));
        assertEquals(List.of("top1"), tiers(Ranking.rank(List.of(score("a", 0)))));
    }

    @Test
    void thirdWithZeroPointsRewardsEveryParticipant() {
        List<Result> ranking = Ranking.rank(List.of(score("a", 3), score("b", 2), score("c", 0), score("d", 0)));
        assertEquals("participation", ranking.get(3).tier());
    }

    @Test
    void nobodyMeansNoRanking() {
        assertTrue(Ranking.rank(List.of()).isEmpty());
    }
}
