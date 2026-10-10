package jo.codeide.applog;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.Process;

/**
 * Le pont de journaux de l'application (ADR 0103) — point d'entrée PUBLIC
 * de la bibliothèque, démarré par l'amorce du provider, ou par l'app
 * elle-même dans un processus secondaire ({@link #demarrer(Context)} est
 * idempotent et sûr à appeler depuis n'importe quel fil).
 *
 * <p>Ce que fait le pont, dans l'ordre :
 * <ol>
 *   <li>vérifie {@code FLAG_DEBUGGABLE} — hors debug, il ne fait RIEN
 *       (défense en profondeur : même si la bibliothèque fuyait dans un
 *       build release, elle reste muette) ;</li>
 *   <li>annonce la raison de fin du processus PRÉCÉDENT
 *       (ApplicationExitInfo, API 30+) ;</li>
 *   <li>pose le miroir de la console standard (System.out/err) ;</li>
 *   <li>pose le gestionnaire d'exceptions non interceptées ;</li>
 *   <li>lit le logcat de SON PROPRE processus ;</li>
 *   <li>expédie tout ça vers l'IDE, par lots Binder oneway.</li>
 * </ol>
 *
 * <p>Politique d'échec : le pont est un OBSERVATEUR MUET — aucun de ses
 * échecs (logcat absent, IDE disparu, liaison refusée) ne remonte jamais
 * vers l'application hôte.
 */
public final class Pont {

    /** Version du protocole de trames (incrémentée à chaque rupture). */
    public static final int VERSION_PROTOCOLE = 1;

    /** Capacité de l'anneau (trames en attente — ADR 0103 §5 : borné). */
    private static final int CAPACITE_ANNEAU = 5000;

    /** L'instance du processus (null : pas démarré / pas debug). */
    private static Pont instance;

    private final AnneauTrames anneau;

    private final LecteurLogcat lecteur;

    private final Expediteur expediteur;

    private final ControleLocal controle;

    private Pont(Context contexte) {
        Context application = contexte.getApplicationContext();
        this.anneau = new AnneauTrames(CAPACITE_ANNEAU);
        int pid = Process.myPid();
        String nomPaquet = application.getPackageName();
        String nomProcessus = nomProcessus(application);
        this.controle = new ControleLocal();
        this.expediteur =
                new Expediteur(application, anneau, nomPaquet, pid, nomProcessus, controle.asBinder());
        InfosSortiePrecedente.annoncer(application, anneau, nomProcessus);
        TeeConsole.brancher(anneau, pid);
        this.lecteur = new LecteurLogcat(anneau, pid);
        GestionnaireCrash.brancher(anneau, pid, expediteur);
        this.lecteur.start();
        this.expediteur.start();
    }

    /**
     * Démarre le pont dans CE processus — appelé par l'amorce du provider,
     * ou directement par une application multi-processus pour chacun de
     * ses processus.
     *
     * @param contexte n'importe quel contexte de l'application
     * @return {@code true} si le pont tourne, {@code false} si le build
     *         n'est pas déboguable (ou déjà démarré : {@code true})
     */
    public static synchronized boolean demarrer(Context contexte) {
        if (instance != null) {
            return true;
        }
        if (contexte == null) {
            return false;
        }
        ApplicationInfo infos = contexte.getApplicationInfo();
        if (infos == null || (infos.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            // Build release : silence total — la bibliothèque ne fait rien.
            return false;
        }
        try {
            instance = new Pont(contexte);
            return true;
        } catch (Throwable echecDemarrage) {
            // Mieux vaut un pont absent qu'une application cassée par
            // l'outil de diagnostic : l'échec est définitif et muet.
            instance = null;
            return false;
        }
    }

    /** Le pont est-il démarré dans ce processus ? */
    public static synchronized boolean estDemarre() {
        return instance != null;
    }

    /**
     * Binder de contrôle reçu par l'IDE (IControlePont) : la veille
     * commandée suspend la collecte (personne n'écoute, batterie
     * économisée), la reprise la relance.
     */
    private final class ControleLocal extends IControlePont.Stub {
        @Override
        public void suspendre() {
            if (lecteur != null) {
                lecteur.suspendre();
            }
        }

        @Override
        public void reprendre() {
            if (lecteur != null) {
                lecteur.reprendre();
            }
        }
    }

    /**
     * Le nom du processus courant : celui déclaré sur l'entrée par laquelle
     * la bibliothèque s'exécute (processus principal pour l'amorce — pour
     * un appel direct depuis un autre processus, c'est le nom du
     * fournisseur, ce qui reste le parent le plus utile).
     */
    private static String nomProcessus(Context application) {
        ApplicationInfo infos = application.getApplicationInfo();
        if (infos != null && infos.processName != null && !infos.processName.isEmpty()) {
            return infos.processName;
        }
        return application.getPackageName();
    }
}
