package jo.codeide.applog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests du format des trames (ADR 0103 — tabulaire à message-reste). */
public class TramesTest {

    @Test
    public void laTrameDeLignePorteLesSeptChampsDansLordre() {
        String trame = Trames.ligne('W', 1760000123456L, 1234, 1250, "MonEtiquette", "un message");

        assertEquals("L\tW\t1760000123456\t1234\t1250\tMonEtiquette\tun message", trame);
    }

    @Test
    public void leMessageEstLeReste_ILPeutContenirDesTabulations() {
        String trame = Trames.ligne('I', 1L, 2, 3, "Etq", "colonne1\tcolonne2\tcolonne3");

        // Le message commence après la SIXIÈME tabulation, puis reste entier.
        int seps = 0;
        int position = 0;
        while (seps < 6 && position < trame.length()) {
            if (trame.charAt(position) == Trames.SEP) {
                seps++;
            }
            position++;
        }
        assertEquals("colonne1\tcolonne2\tcolonne3", trame.substring(position));
    }

    @Test
    public void lesValeursNullesDeviennentVidesPasDerreur() {
        String trame = Trames.ligne('D', 0L, 1, 2, null, null);

        assertEquals("L\tD\t0\t1\t2\t\t", trame);
    }

    @Test
    public void laTrameDeSortiePrecedentePorteCodeEtDescription() {
        String trame = Trames.sortiePrecedente(3, "manque de mémoire");

        assertEquals("X\t3\tmanque de mémoire", trame);
    }

    @Test
    public void lesMessagesGeantsSontTronquesAvecUnMarqueurVisible() {
        StringBuilder geant = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            geant.append('x');
        }

        String coupe = Trames.tronquer(geant.toString());

        assertEquals(Trames.TAILLE_MESSAGE_MAX + "… [tronqué]".length(), coupe.length());
        assertTrue(coupe.endsWith("… [tronqué]"));
    }

    @Test
    public void unMessageCourtResteIntact() {
        assertEquals("court", Trames.tronquer("court"));
    }
}
