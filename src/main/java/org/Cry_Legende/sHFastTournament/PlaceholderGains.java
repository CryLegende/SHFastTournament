package org.Cry_Legende.sHFastTournament;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tournoi PLACEHOLDER : les points d'un joueur sont ce que gagne une valeur de PlaceholderAPI pendant le tournoi
 * (des clics, des niveaux...). Chaque lecture est comparée à la précédente du même joueur ; la première sert de
 * départ. Une reconnexion repart d'une nouvelle lecture : ce que le joueur a gagné ailleurs entre-temps (sur un autre
 * serveur, par exemple) n'est pas compté ici. Une valeur qui baisse ou devient illisible ne retire rien.
 * Sans serveur : le fil principal appelle {@link #read} pour chaque joueur connecté.
 */
final class PlaceholderGains {
    // Codes couleur et séparateurs de milliers (1,234,567 · 1 234 567 · 1_234_567)
    private static final Pattern NOISE = Pattern.compile("(?i)[§&](#[0-9a-f]{6}|[0-9a-fk-orx])|[\\s\\u00A0\\u202F,_]");

    private final Map<UUID, Seen> seen = new HashMap<>();

    /** Dernière lecture d'un joueur, pour une session donnée (l'objet Player change à chaque connexion). */
    private record Seen(Object session, double value) {
    }

    /** Nouvelle lecture : rend la hausse depuis la lecture précédente de la même session, 0 sinon. */
    double read(UUID uuid, Object session, String raw) {
        Double value = parse(raw);
        if (value == null) {
            seen.remove(uuid);
            return 0;
        }
        Seen before = seen.put(uuid, new Seen(session, value));
        if (before == null || before.session() != session || value <= before.value()) {
            return 0;
        }
        return value - before.value();
    }

    /** Oublie les joueurs qui ne sont plus connectés. */
    void retain(Set<UUID> online) {
        seen.keySet().retainAll(online);
    }

    /** Nombre lu dans le texte d'un placeholder, ou null (placeholder inconnu, N/A...). */
    static Double parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = NOISE.matcher(raw).replaceAll("");
        if (text.isEmpty() || text.indexOf('%') >= 0) {
            return null;
        }
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
