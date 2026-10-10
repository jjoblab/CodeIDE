package jo.codeide.applog;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Gestionnaire des exceptions non interceptées (ADR 0103 §4) : la trace
 * part VERS L'IDE AVANT la délégation — l'application plante ensuite
 * NORMALEMENT (le gestionnaire précédent, y compris celui du système,
 * garde la main).
 *
 * <p>L'envoi est un meilleur effort borné : le processus va mourir, le
 * pont tente UN vidage immédiat de l'anneau ; ce qui part part, le reste
 * est dans l'anneau (et le logcat système conserve de toute façon la
 * trace sous « AndroidRuntime »).
 */
final class GestionnaireCrash implements Thread.UncaughtExceptionHandler {

    private final AnneauTrames anneau;

    private final int pid;

    private final Thread.UncaughtExceptionHandler precedent;

    private final Expediteur expediteur;

    private GestionnaireCrash(
            AnneauTrames anneau,
            int pid,
            Thread.UncaughtExceptionHandler precedent,
            Expediteur expediteur) {
        this.anneau = anneau;
        this.pid = pid;
        this.precedent = precedent;
        this.expediteur = expediteur;
    }

    /**
     * Pose le gestionnaire (le précédent est conservé pour la délégation).
     */
    static void brancher(AnneauTrames anneau, int pid, Expediteur expediteur) {
        Thread.UncaughtExceptionHandler precedent = Thread.getDefaultUncaughtExceptionHandler();
        if (precedent instanceof GestionnaireCrash) {
            return; // déjà branché — aucun double emploi
        }
        Thread.setDefaultUncaughtExceptionHandler(
                new GestionnaireCrash(anneau, pid, precedent, expediteur));
    }

    @Override
    public void uncaughtException(Thread fil, Throwable exception) {
        try {
            int tid = (int) fil.getId();
            long maintenant = System.currentTimeMillis();
            anneau.ajouter(
                    Trames.ligne(
                            'E',
                            maintenant,
                            pid,
                            tid,
                            "Plantage",
                            "Exception non interceptée dans le fil « " + fil.getName() + " »"));
            anneau.ajouter(
                    Trames.ligne(
                            'E',
                            maintenant,
                            pid,
                            tid,
                            "Plantage",
                            Trames.tronquer(trace(exception))));
            // Le processus va mourir : partir TOUT DE SUITE (pas au
            // prochain tick de 100 ms).
            expediteur.viderAvantMort();
        } catch (Throwable meilleurEffort) {
            // Le rapport de plantage ne doit JAMAIS empêcher le plantage
            // réel d'être traité par le gestionnaire précédent.
        } finally {
            if (precedent != null) {
                precedent.uncaughtException(fil, exception);
            }
        }
    }

    /** Trace complète sous forme de texte. */
    private static String trace(Throwable exception) {
        StringWriter texte = new StringWriter(1024);
        exception.printStackTrace(new PrintWriter(texte));
        return texte.toString();
    }
}
