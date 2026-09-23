package jo.codeide.core.bootstrap

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.ToolchainLocator
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation de référence de [ToolchainLocator] (prompt compagnon
 * Terminal-1, section 3.1).
 *
 * Simple adaptateur : toute l'intelligence de résolution vit dans
 * [LocalisationOutils] et [CachesGradle] (fonctions pures testées en JVM) —
 * cette classe n'apporte que la racine physique (`context.filesDir`) et la
 * délégation.
 */
@Suppress("TooManyFunctions") // Contrat ToolchainLocator : 13 méthodes (Terminal-1, section 2.2).
@Singleton
internal class ToolchainBootstrap
    @Inject
    constructor(
        @ApplicationContext contexte: Context,
    ) : ToolchainLocator {
        private val racine: File = contexte.filesDir

        override fun isBootstrapInstalled(): Boolean = LocalisationOutils.bootstrapInstalle(racine)

        override fun isJdkInstalled(): Boolean = javaHome() != null

        override fun javaHome(): File? = LocalisationOutils.trouverJavaHome(racine)

        override fun isGradleInstalled(): Boolean = gradleHome() != null

        override fun gradleHome(): File? = LocalisationOutils.trouverGradleHome(racine)

        override fun isAndroidSdkInstalled(): Boolean = androidHome() != null

        override fun androidHome(): File? = LocalisationOutils.trouverAndroidHome(racine)

        override fun androidJar(): File? = androidHome()?.let(LocalisationOutils::trouverAndroidJar)

        override fun aapt2Binary(): File? = LocalisationOutils.trouverAapt2(racine)

        override fun isAapt2Installed(): Boolean = aapt2Binary() != null

        override fun gradleUserHome(): File = DispositionsBootstrap.gradleUserHome(racine)

        override fun findCachedGradleDistribution(version: String?): File? =
            CachesGradle.trouverDistribution(gradleUserHome(), version)

        override fun defaultShell(): String = LocalisationOutils.shellParDefaut(racine)
    }
