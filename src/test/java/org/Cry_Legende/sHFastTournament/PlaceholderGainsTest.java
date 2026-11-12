package org.Cry_Legende.sHFastTournament;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tournoi PLACEHOLDER : lecture des nombres et points gagnés entre deux lectures. */
class PlaceholderGainsTest {
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Test
    void numbersAreReadWhateverTheirFormat() {
        assertEquals(1_011_876_175_853.0, PlaceholderGains.parse("1,011,876,175,853"));
        assertEquals(1_234_567.0, PlaceholderGains.parse("1 234 567"));
        assertEquals(1_234_567.0, PlaceholderGains.parse("1 234 567"));
        assertEquals(42.0, PlaceholderGains.parse("§a42"));
        assertEquals(42.0, PlaceholderGains.parse("&#ff00aa42"));
        assertEquals(12.5, PlaceholderGains.parse(" 12.5 "));
        assertNull(PlaceholderGains.parse("N/A"));
        assertNull(PlaceholderGains.parse(""));
        assertNull(PlaceholderGains.parse(null));
        // Placeholder inconnu : PlaceholderAPI le rend tel quel
        assertNull(PlaceholderGains.parse("%cookieclicker_clickerclicks%"));
    }

    @Test
    void pointsAreTheRiseSinceTheFirstReading() {
        PlaceholderGains gains = new PlaceholderGains();
        Object session = new Object();
        assertEquals(0, gains.read(ALICE, session, "1,000"));
        assertEquals(15, gains.read(ALICE, session, "1,015"));
        assertEquals(0, gains.read(ALICE, session, "1,015"));
        assertEquals(3, gains.read(ALICE, session, "1,018"));
        // Les joueurs sont suivis chacun de leur côté
        assertEquals(0, gains.read(BOB, new Object(), "5"));
    }

    @Test
    void aDropOrAnUnreadableValueGivesNothing() {
        PlaceholderGains gains = new PlaceholderGains();
        Object session = new Object();
        gains.read(ALICE, session, "500");
        // Remise à zéro par un administrateur : on repart de la nouvelle valeur
        assertEquals(0, gains.read(ALICE, session, "0"));
        assertEquals(7, gains.read(ALICE, session, "7"));
        // Valeur illisible : rien, puis nouveau départ
        assertEquals(0, gains.read(ALICE, session, "N/A"));
        assertEquals(0, gains.read(ALICE, session, "100"));
        assertEquals(1, gains.read(ALICE, session, "101"));
    }

    @Test
    void aReconnectionStartsFromANewReading() {
        PlaceholderGains gains = new PlaceholderGains();
        gains.read(ALICE, new Object(), "100");
        // Clics faits ailleurs entre les deux connexions : pas comptés ici
        assertEquals(0, gains.read(ALICE, new Object(), "160"));
    }

    @Test
    void playersWhoLeftAreForgotten() {
        PlaceholderGains gains = new PlaceholderGains();
        Object session = new Object();
        gains.read(ALICE, session, "100");
        gains.read(BOB, new Object(), "100");
        gains.retain(Set.of(BOB));
        // Même session (cas impossible en jeu, pour le test) : Alice oubliée, la lecture repart de zéro
        assertEquals(0, gains.read(ALICE, session, "150"));
    }
}
