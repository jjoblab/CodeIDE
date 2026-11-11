package jo.codeide

import jo.codeide.core.domain.CycleDaemonTooling
import jo.codeide.tooling.daemon.DaemonManager
import kotlinx.coroutines.CoroutineScope

/**
 * Adaptateur applicatif du port [CycleDaemonTooling] (v0.183.0) : le
 * `DaemonManager` exigerait une portée pour `demarrer` — la couche `app`
 * est la SEULE qui possède une portée qui survit aux écrans
 * (`porteeDemarrage`), elle la capture ICI une fois pour toutes.
 *
 * [DaemonManager.demarrer] comme [DaemonManager.arreter] sont idempotents
 * — l'espace de travail peut appeler le port sans garder d'état : « le
 * tooling démarre avec le projet, meurt avec la fermeture du DERNIER
 * espace vivant » (retour propriétaire : pas seulement les LSP doivent
 * être fermés).
 *
 * @param daemon le gestionnaire du process orchestrateur (singleton Hilt).
 * @param portee la portée de démarrage de l'app — survit à TOUT écran.
 */
internal class CycleDaemonToolingApp(private val daemon: DaemonManager, private val portee: CoroutineScope) :
    CycleDaemonTooling {
    override fun demarrer() {
        daemon.demarrer(portee)
    }

    override fun arreter() {
        daemon.arreter()
    }
}
