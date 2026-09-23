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
 * par le futur tooling, jamais dupliquée.
 */
@Singleton
internal class EnvironnementProcessusFournisseur
    @Inject
    constructor(
        @ApplicationContext contexte: Context,
        private val toolchain: ToolchainLocator,
    ) : ProcessEnvironmentProvider {
        private val racine: File = contexte.filesDir

        override fun baseEnvironment(): Map<String, String> =
            EnvironnementProcessus.construire(
                racine = racine,
                herite = System.getenv(),
                javaHome = toolchain.javaHome(),
                androidHome = toolchain.androidHome(),
            )
    }
