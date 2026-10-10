package jo.codeide.applog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Tests de l'analyse des lignes threadtime du lecteur logcat (ADR 0103 §4)
 * — l'analyse est le cœur du format : elle doit accepter les lignes
 * réelles et ignorer proprement tout le reste.
 */
public class LecteurLogcatParseTest {

    private final AnneauTrames anneau = new AnneauTrames(100);

    private final LecteurLogcat lecteur = new LecteurLogcat(anneau, 4321);

    private List<String> extraireTout() {
        List<String> sorties = new ArrayList<String>();
        anneau.extraire(sorties, 100, 100_000);
        return sorties;
    }

    @Test
    public void uneLigneThreadtimeStandardDevientUneTrameComplete() {
        lecteur.consommer("10-10 14:03:21.512  4321  4402 I MonApp : démarrage terminé");

        List<String> trames = extraireTout();
        assertEquals(1, trames.size());
        String[] champs = trames.get(0).split("\t", -1);
        assertEquals("L", champs[0]);
        assertEquals("I", champs[1]);
        assertEquals("4321", champs[3]);
        assertEquals("4402", champs[4]);
        assertEquals("MonApp", champs[5]);
        assertEquals("démarrage terminé", champs[6]);
        // L'horodatage est bien présent et numérique.
        assertTrue(champs[2].length() >= 10);
    }

    @Test
    public void lesLignesDAutresProcessusSignorent() {
        lecteur.consommer("10-10 14:03:21.512  9999  4402 I MonApp : ailleurs");
        lecteur.consommer("10-10 14:03:21.512  4321  4402 I MonApp : ici");

        assertEquals(1, extraireTout().size());
    }

    @Test
    public void lePidEnErreurDeFormatNePlanteJamais() {
        lecteur.consommer("10-10 14:03:21.512  pid!  4402 I MonApp : corrompu");
        // Ligne vide, ligne hors format, message null : silencieusement ignorées.
        lecteur.consommer("");
        lecteur.consommer("n'importe quoi au milieu d'un vidage");
        lecteur.consommer(null);

        assertEquals(0, extraireTout().size());
    }

    @Test
    public void lesMiroirsSystemOutEtSystemErrNeSontPasDoubles() {
        lecteur.consommer("10-10 14:03:21.512  4321  4402 I System.out : bonjour");
        lecteur.consommer("10-10 14:03:21.513  4321  4403 W System.err : au revoir");

        assertEquals(0, extraireTout().size());
    }

    @Test
    public void letiquetteVideEtLeMessageVideRestentCoherents() {
        lecteur.consommer("10-10 14:03:21.512  4321  4402 D tag-sans-message:");

        List<String> trames = extraireTout();
        assertEquals(1, trames.size());
        String[] champs = trames.get(0).split("\t", -1);
        assertEquals("tag-sans-message", champs[5]);
        assertEquals("", champs[6]);
    }

    @Test
    public void leMessagePeutContenirDesDeuxPointsAEtiquettesMultiples() {
        lecteur.consommer("10-10 14:03:21.512  4321  4402 E OkHttp : erreur : timeout : réseau");

        List<String> trames = extraireTout();
        String[] champs = trames.get(0).split("\t", -1);
        assertEquals("OkHttp", champs[5]);
        assertEquals("erreur : timeout : réseau", champs[6]);
    }

    @Test
    public void lesNiveauxFatalEtAssertPassentTelsQuels() {
        lecteur.consommer("10-10 14:03:21.512  4321  4402 F libc : Fatal signal");

        List<String> trames = extraireTout();
        String[] champs = trames.get(0).split("\t", -1);
        assertEquals("F", champs[1]);
    }

    @Test
    public void lesLignesChattyDuSystemePassentCommeLesAutres() {
        // « chatty » est une vraie ligne du pid, pas un artéfact : elle
        // doit être visible dans l'IDE comme dans le logcat.
        lecteur.consommer("10-10 14:03:21.512  4321  4402 I chatty: expire 1 line");

        assertEquals(1, extraireTout().size());
    }

    @Test
    public void lhorodatageReconstruitRestePlausible() {
        lecteur.consommer("10-10 14:03:21.512  4321  4402 I MonApp : horloge");

        List<String> trames = extraireTout();
        String[] champs = trames.get(0).split("\t", -1);
        long epoch = Long.parseLong(champs[2]);
        long maintenant = System.currentTimeMillis();
        // L'année courante (ou l'année précédente au roulement du
        // 1er janvier) : jamais un horodatage délirant.
        assertTrue(epoch <= maintenant);
        assertTrue(epoch > maintenant - 400L * 24L * 60L * 60L * 1000L);
    }
}
