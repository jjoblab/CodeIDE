package jo.codeide.core.bootstrap

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.MoteurGit
import jo.codeide.core.domain.NativeProcessLauncher
import java.io.File
import javax.inject.Singleton

/**
 * Module Hilt fournissant l'implémentation de [MoteurGit] (ADR 0092).
 * Construit [MoteurGitCli] avec le [NativeProcessLauncher] existant et
 * localise le binaire `git` dans `$PREFIX/bin/git`. L'identité est
 * `null` par défaut (configurable via les Paramètres — évolution future).
 *
 * v0.80.5 : la localisation est passée comme RÉSOLVEUR (une fonction),
 * pas comme un chemin figé — le singleton vit aussi longtemps que le
 * processus, et `pkg install git` dans le terminal doit être découvert
 * sans redémarrer l'application.
 *
 * v0.90.1 (mission « section Git figée » étape A) : le moteur reçoit
 * aussi l'[AppLogger] (commande/code/stderr de chaque exécution git,
 * observabilité exigée par l'étape A) et les sondes d'environnement du
 * Diagnostic Git ([SondesEnvironnementLinux]).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object MoteurGitModule {
    @Provides
    @Singleton
    fun provideMoteurGit(
        @ApplicationContext contexte: Context,
        lanceur: NativeProcessLauncher,
        journal: AppLogger,
        sondes: SondesEnvironnementLinux,
    ): MoteurGit {
        val prefix = DispositionsBootstrap.prefix(contexte.filesDir)
        return MoteurGitCli(
            lanceur = lanceur,
            resoudreBinaireGit = { File(prefix, "bin/git").takeIf { it.exists() }?.absolutePath },
            identite = null, // Évolution : depuis les Paramètres utilisateur.
            journal = journal,
            sondes = sondes,
        )
    }
}
