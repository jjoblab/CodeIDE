package jo.codeide.feature.ai.agent

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Démarreur du service premier plan Agent (patron `DemarreurService…` :
 * la décision de démarrage reste testable hors Android — le contrôleur
 * appelle ce port, jamais le contexte directement).
 */
public class DemarreurServiceAgentIa
@Inject
constructor(@param:ApplicationContext private val contexte: Context) {
    /** Démarre le service premier plan (notification visible). */
    public fun demarrer() {
        contexte.startForegroundService(Intent(contexte, ServiceAgentIa::class.java))
    }

    /** Arrête le service (harmless si absent). */
    public fun arreter() {
        contexte.stopService(Intent(contexte, ServiceAgentIa::class.java))
    }
}
