package jo.codeide.applog;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Tests de l'anneau borné (ADR 0103 §5 — pertes comptées, jamais muettes). */
public class AnneauTramesTest {

    @Test
    public void lextractionRendLesTramesDansLordreArrivee() {
        AnneauTrames anneau = new AnneauTrames(8);
        anneau.ajouter("a");
        anneau.ajouter("b");
        anneau.ajouter("c");

        List<String> sorties = new ArrayList<String>();
        int prises = anneau.extraire(sorties, 10, 10_000);

        assertEquals(3, prises);
        assertEquals(java.util.Arrays.asList("a", "b", "c"), sorties);
        assertEquals(0, anneau.enAttente());
    }

    @Test
    public void leDebordementPerdLesPlusVieillesEtCompte() {
        AnneauTrames anneau = new AnneauTrames(3);
        anneau.ajouter("a");
        anneau.ajouter("b");
        anneau.ajouter("c");
        anneau.ajouter("d"); // « a » déborde
        anneau.ajouter("e"); // « b » déborde

        assertEquals(2, anneau.prendrePertes());
        // Le compteur est REMIS à zéro après le rapport.
        assertEquals(0, anneau.prendrePertes());

        List<String> sorties = new ArrayList<String>();
        anneau.extraire(sorties, 10, 10_000);
        assertEquals(java.util.Arrays.asList("c", "d", "e"), sorties);
    }

    @Test
    public void laLimiteDeTramesArreteLextractionLeResteAttend() {
        AnneauTrames anneau = new AnneauTrames(100);
        for (int i = 0; i < 10; i++) {
            anneau.ajouter("trame-" + i);
        }

        List<String> premierLot = new ArrayList<String>();
        anneau.extraire(premierLot, 4, 100_000);

        assertEquals(4, premierLot.size());
        assertEquals(6, anneau.enAttente());

        List<String> secondLot = new ArrayList<String>();
        anneau.extraire(secondLot, 100, 100_000);
        assertEquals(6, secondLot.size());
        assertEquals("trame-4", secondLot.get(0));
    }

    @Test
    public void laLimiteDeCaracteresNeCoupeJamaisUnLotVide() {
        AnneauTrames anneau = new AnneauTrames(100);
        anneau.ajouter("une-trame-tres-longue-de-plus-de-dix-caracteres");

        // Plafond de caractères INFÉRIEUR à la trame : elle part quand
        // même (un lot d'une seule trame n'est jamais vide par construction).
        List<String> lot = new ArrayList<String>();
        int prises = anneau.extraire(lot, 10, 5);

        assertEquals(1, prises);
        assertEquals(0, anneau.enAttente());
    }

    @Test
    public void laLimiteDeCaracteresArreteAPresLaPremiereTrame() {
        AnneauTrames anneau = new AnneauTrames(100);
        anneau.ajouter("0123456789012345678901234567890");
        anneau.ajouter("0123456789012345678901234567890");
        anneau.ajouter("0123456789");

        List<String> lot = new ArrayList<String>();
        int prises = anneau.extraire(lot, 10, 40);

        // 30 + 30 > 40 : la deuxième attend ; la première est partie.
        assertEquals(1, prises);
        assertEquals(2, anneau.enAttente());
    }
}
