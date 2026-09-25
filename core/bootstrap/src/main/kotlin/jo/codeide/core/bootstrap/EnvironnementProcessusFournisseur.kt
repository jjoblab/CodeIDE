package jo.codeide.core.bootstrap

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.ProcessEnvironmentProvider
import jo.codeide.core.domain.ToolchainLocator
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation de référence de [ProcessEnvironmentProvider] (prompt
 * compagnon Terminal-1, section 3.2).
 *
 * Assemble l'environnement à partir de la racine physique, de
 * l'environnement hérité du processus Android et des outils réellement
 * détectés par [ToolchainBootstrap] — la construction elle-même est la
 * fonction pure [EnvironnementProcessus.construire], rejouée par les
 * tests. Unique source de vérité : consommée par les sessions shell et
 * le tooling (tout [NativeProcessLauncher] passe par ici), jamais
 * dupliquée.
 *
 * **Garantie physique** ([assurerRepertoiresProcessus], v0.31.3, ADR
 * 0047) : l'archive publiée par `codeide-packages` n'embarque pas
 * l'entrée `tmp/` que le bootstrap officiel Termux contient — le
 * répertoire `$PREFIX/tmp` (visé par `TMPDIR`) et `$HOME` sont donc
 * (re)créés à CHAQUE appel, avant tout lancement de sous-processus.
 * C'est la deuxième couche de défense : la première (création à
 * l'extraction) couvre les installations neuves, celle-ci couvre un
 * préfixe posé avant v0.31.3 ou un `tmp` supprimé à la main (panne
 * documentée par la FAQ Termux) — sans elle, le premier `apt update`
 * meurt en `mkstemp` ENOENT, errno 2 : un répertoire absent, pas une
 * permission refusée.
 */
@Singleton
internal class EnvironnementProcessusFournisseur
    @Inject
    constructor(
        @ApplicationContext contexte: Context,
        private val toolchain: ToolchainLocator,
    ) : ProcessEnvironmentProvider {
        private val racine: File = contexte.filesDir

        override fun baseEnvironment(): Map<String, String> {
            assurerRepertoiresProcessus(racine)
            return EnvironnementProcessus.construire(
                racine = racine,
                herite = System.getenv(),
                javaHome = toolchain.javaHome(),
                androidHome = toolchain.androidHome(),
            )
        }
    }

/**
 * Garantit l'existence des répertoires attendus par l'environnement des
 * sous-processus (`$PREFIX/tmp` pour `TMPDIR`, `$HOME` du shell) —
 * idempotent et volontairement silencieux : un échec de création
 * remonte de toute façon par l'échec réel du sous-processus, avec son
 * message propre (l'appelant n'a rien à décider ici).
 *
 * Fonction de fichier (hors classe) : testable sans Android (dossier
 * temporaire JUnit), la classe restant l'adaptateur Hilt.
 */
internal fun assurerRepertoiresProcessus(racine: File) {
    DispositionsBootstrap.tmpdir(racine).mkdirs()
    DispositionsBootstrap.home(racine).mkdirs()
}
