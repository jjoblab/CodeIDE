package jo.codeide.feature.ai.agent

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Port de démarrage du service premier plan de l'agent (A2, rapport IA
 * lot 2) : le contrôleur appelle CE port, jamais le contexte — le
 * contrat devient testable hors Android (un double no-op suffit) et
 * la décision de démarrage reste pure.
 */
public interface ServiceAgentIaPremierPlan {
    /** Démarre le service premier plan (notification visible). */
    public fun demarrer()

    /** Arrête le service (inoffensif si absent). */
    public fun arreter()
}

/**
 * Démarreur RÉEL du service premier plan Agent (patron
 * `DemarreurService…`) : port de l'espace d'édition, le service vit
 * avec lui.
 */
@Singleton
public class DemarreurServiceAgentIa
@Inject
constructor(@param:ApplicationContext private val contexte: Context) :
    ServiceAgentIaPremierPlan {
    override fun demarrer() {
        contexte.startForegroundService(Intent(contexte, ServiceAgentIa::class.java))
    }

    override fun arreter() {
        contexte.stopService(Intent(contexte, ServiceAgentIa::class.java))
    }
}
