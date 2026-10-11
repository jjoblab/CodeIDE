package jo.codeide.core.bootstrap

import android.system.ErrnoException
import android.system.Os
import jo.codeide.core.domain.ProcessEnvironmentProvider
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sondes de l'environnement d'exécution de git (v0.90.1, mission
 * « section Git figée » étape A) — couture de test derrière les
 * lectures système du Diagnostic Git.
 *
 * Chaque sonde est **best effort** : indisponible = `null`, jamais une
 * exception — un diagnostic doit pouvoir se construire même quand une
 * lecture système échoue (c'est précisément le cas intéressant).
 *
 * L'interface est interne à `core:bootstrap` : le domaine ne connaît
 * que le [jo.codeide.core.domain.RapportDiagnosticGit] (données),
 * jamais la manière de les obtenir.
 */
internal interface SondesEnvironnementGit {
    /** uid effectif du processus applicatif — celui sous lequel git s'exécute. */
    fun uidEffectif(): Long?

    /** uid du propriétaire POSIX (`st_uid`) du dossier [chemin], ou `null`. */
    fun uidProprietaire(chemin: String): Long?

    /**
     * Contenu brut de la table des montages (`/proc/self/mounts`),
     * ou `null` si illisible — le parsing vit dans [MoteurGitCli]
     * (fonction pure testable).
     */
    fun contenuMonts(): String?

    /**
     * Environnement de base des sous-processus (source unique de
     * vérité : [ProcessEnvironmentProvider]) — le `HOME`, `PATH`,
     * `GIT_*` que git reçoit réellement.
     */
    fun environnement(): Map<String, String>
}

/**
 * Sondes de production sur Linux/Android : `android.os.Process.myUid()`
 * (API 1, documentée), `android.system.Os.stat` (API 21, documentée —
 * `st_uid` est le champ que git compare à son uid effectif pour le
 * contrôle de propriété), lecture directe de `/proc/self/mounts`.
 *
 * La lecture de `/proc/self/mounts` est une I/O : l'appelant du
 * diagnostic ([MoteurGitCli.diagnostiquer]) est suspendu et l'usage
 * embarque le tout dans le dispatcher IO du cas d'usage — jamais
 * appelée depuis le thread principal.
 *
 * Exemption detekt ciblée (règle 16) : SwallowedException — une sonde
 * en échec EST une donnée de diagnostic (le champ vaut `null` dans le
 * rapport, affiché « (indisponible) ») ; l'exception elle-même
 * (errno, message) n'ajoute rien au rapport et une sonde ne peut pas
 * faire échouer le diagnostic.
 */
@Suppress("SwallowedException")
@Singleton
internal class SondesEnvironnementLinux
    @Inject
    constructor(
        private val environnement: ProcessEnvironmentProvider,
    ) : SondesEnvironnementGit {
        override fun uidEffectif(): Long? =
            try {
                android.os.Process
                    .myUid()
                    .toLong()
            } catch (e: RuntimeException) {
                // myUid est une lecture triviale, la garde reste par
                // prudence : une sonde ne peut pas faire échouer le diagnostic.
                null
            }

        override fun uidProprietaire(chemin: String): Long? =
            try {
                Os.stat(chemin).st_uid.toLong()
            } catch (e: ErrnoException) {
                null
            } catch (e: RuntimeException) {
                null
            }

        override fun contenuMonts(): String? =
            try {
                File(CHEMIN_MONTS).readText()
            } catch (e: IOException) {
                null
            }

        override fun environnement(): Map<String, String> = environnement.baseEnvironment()

        private companion object {
            /** Table des montages du processus (vue des FUSE de stockage). */
            private const val CHEMIN_MONTS = "/proc/self/mounts"
        }
    }
