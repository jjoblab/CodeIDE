package jo.codeide.applog;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import java.util.List;

/**
 * Information de fin du processus PRÉCÉDENT (ADR 0103 §4 — ce qu'aucune
 * des deux références ne montre).
 *
 * <p>ApplicationExitInfo (API 30+) conserve, pour chaque paquet, la raison
 * de la mort des processus passés : plantage natif (avec trace), ANR,
 * manque mémoire, arrêt demandé… Le pont la lit à son démarrage et
 * l'annonce à l'IDE par une trame « X » : la session s'ouvre en disant
 * POURQUOI la précédente s'est terminée.
 *
 * <p>API 29 et moins : rien (l'absence d'information est l'état normal,
 * pas une erreur).
 */
final class InfosSortiePrecedente {

    private InfosSortiePrecedente() {
        // Classe utilitaire — jamais instanciée.
    }

    /**
     * Ajoute à l'anneau la trame de sortie du processus précédent DU MÊME
     * NOM de processus, s'il y en a une et si l'appareil sait la donner.
     *
     * @param contexte    contexte de l'amorce
     * @param anneau      anneau du pont
     * @param nomProcessus nom du processus courant (celui du pont)
     */
    static void annoncer(Context contexte, AnneauTrames anneau, String nomProcessus) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            ActivityManager gestionnaire =
                    (ActivityManager) contexte.getSystemService(Context.ACTIVITY_SERVICE);
            if (gestionnaire == null) {
                return;
            }
            // Les 5 dernières morts du PAQUET suffisent : on cherche la
            // plus récente du processus courant.
            List<ApplicationExitInfo> sorties =
                    gestionnaire.getHistoricalProcessExitReasons(contexte.getPackageName(), 0, 5);
            for (ApplicationExitInfo sortie : sorties) {
                if (nomProcessus.equals(sortie.getProcessName())) {
                    anneau.ajouter(
                            Trames.sortiePrecedente(
                                    sortie.getReason(),
                                    libelle(sortie.getReason()) + " (" + sortie.getTimestamp() + ")"));
                    return;
                }
            }
        } catch (Throwable silencieuse) {
            // L'information de sortie est un BONUS : son échec ne doit
            // jamais toucher le démarrage du pont.
        }
    }

    /**
     * Libellé court de la raison (les codes sont les constantes
     * ApplicationExitInfo.REASON_* ; le texte complet est reconstruit par
     * l'IDE à partir du code — la trame porte les deux).
     */
    private static String libelle(int raison) {
        switch (raison) {
            case ApplicationExitInfo.REASON_ANR:
                return "application ne répondant plus (ANR)";
            case ApplicationExitInfo.REASON_CRASH:
                return "exception non interceptée";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "plantage natif";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "consommation excessive de ressources";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "manque de mémoire";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "signal système";
            case ApplicationExitInfo.REASON_OTHER:
                return "autre raison système";
            default:
                return "raison inconnue (" + raison + ")";
        }
    }
}
