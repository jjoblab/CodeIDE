package jo.codeide.core.bootstrap.installation

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Démarrage du service de premier plan de l'installation (indirection
 * interne, pattern `DemarreurService` du terminal — ADR 0087 § 8) :
 * l'orchestrateur sollicite l'Android sans en dépendre dans les tests,
 * qui doublent ce port par un simple compteur.
 */
internal interface DemarreurServiceInstallation {
    /** Garantit que le service foreground tourne (idempotent). */
    fun demarrer()
}

/**
 * Implémentation Android : `startForegroundService` — la restriction
 * Android 12+ (`ForegroundServiceStartNotAllowedException`) s'applique
 * aux applications ciblant 31+ ; la cible est 28 (ADR 0045), non
 * concernée. Comportement Android 15 : non vérifié sur appareil.
 */
@Singleton
internal class DemarreurServiceInstallationAndroid
    @Inject
    constructor(
        // Annotation sans `private val` (leçon T1) : champ dérivé ci-dessous.
        @ApplicationContext contexte: Context,
    ) : DemarreurServiceInstallation {
        private val contexteApplication: Context = contexte.applicationContext

        override fun demarrer() {
            contexteApplication.startForegroundService(
                Intent(contexteApplication, ServiceInstallationEnvironnement::class.java),
            )
        }
    }
