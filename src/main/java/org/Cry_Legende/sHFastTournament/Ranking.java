package org.Cry_Legende.sHFastTournament;

import org.Cry_Legende.sHFastTournament.network.TournamentDatabase.Result;
import org.Cry_Legende.sHFastTournament.network.TournamentDatabase.Score;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import static org.Cry_Legende.sHFastTournament.network.TournamentDatabase.NO_TIER;

/**
 * Classement final d'un tournoi en réseau, avec les mêmes règles qu'un tournoi local : points décroissants, à égalité
 * l'ordre alphabétique des pseudos ; les trois premiers ont leur récompense, les suivants celle de participation s'ils
 * atteignent la moitié des points du troisième (sans seuil quand ils ne sont pas trois).
 */
final class Ranking {

    private Ranking() {
    }

    /** Classe tous les participants. La première place est la 1. */
    static List<Result> rank(Collection<Score> scores) {
        List<Score> sorted = new ArrayList<>(scores);
        sorted.sort(Comparator.comparingDouble(Score::score).reversed()
            .thenComparing(Score::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(s -> s.uuid().toString()));
        double threshold = sorted.size() >= 3 ? sorted.get(2).score() * 0.5 : 0;
        List<Result> out = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Score s = sorted.get(i);
            int place = i + 1;
            out.add(new Result(s.uuid(), s.name(), place, s.score(), tier(place, s.score(), threshold), false));
        }
        return out;
    }

    /** Palier de récompense : top1, top2, top3, participation, ou aucun. */
    static String tier(int place, double score, double threshold) {
        if (place <= 3) {
            return "top" + place;
        }
        return score >= threshold ? "participation" : NO_TIER;
    }
}
