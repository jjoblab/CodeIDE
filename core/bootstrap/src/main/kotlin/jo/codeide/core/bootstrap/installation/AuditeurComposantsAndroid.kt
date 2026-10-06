package jo.codeide.core.bootstrap.installation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.bootstrap.LocalisationOutils
import jo.codeide.core.domain.AuditeurComposants
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.InstalledComponent
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audit des composants installés (E5, § 7 — écran Environnement, ADR
 * 0090) : marche récursive de l'`installPath` sous la racine du SDK et
 * du `JAVA_HOME` résolu par la règle unique de [LocalisationOutils].
 * Lecture seule — l'orchestrateur reste le seul décideur.
 */
@Singleton
internal class AuditeurComposantsAndroid
    @Inject
    constructor(
        @ApplicationContext contexte: Context,
        private val dispatchers: DispatcherProvider,
    ) : AuditeurComposants {
        private val racine: File = contexte.filesDir
        private val localisateur = LocalisationOutils

        /** Taille de l'`installPath` du composant — `null` si absent du disque. */
        override suspend fun tailleOctets(composant: InstalledComponent): Long? =
            withContext(dispatchers.io) {
                val installPath =
                    composant.installPath
                        ?: return@withContext null
                val cible = File(racineSdk(racine), installPath)
                if (!cible.exists()) {
                    null
                } else {
                    cible.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                }
            }

        /** Taille du JDK (`JAVA_HOME` résolu) — `null` s'il est absent. */
        override suspend fun tailleJdkOctets(): Long? =
            withContext(dispatchers.io) {
                val javaHome = localisateur.trouverJavaHome(racine) ?: return@withContext null
                javaHome.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            }
    }
