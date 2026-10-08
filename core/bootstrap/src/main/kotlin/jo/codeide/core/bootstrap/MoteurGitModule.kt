package jo.codeide.core.bootstrap

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.MoteurGit
import jo.codeide.core.domain.NativeProcessLauncher
import java.io.File
import javax.inject.Singleton

/**
 * Module Hilt fournissant l'implémentation de [MoteurGit] (ADR 0092).
 * Construit [MoteurGitCli] avec le [NativeProcessLauncher] existant et
 * localise le binaire `git` dans `$PREFIX/bin/git`. L'identité est
 * `null` par défaut (configurable via les Paramètres — évolution future).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object MoteurGitModule {
    @Provides
    @Singleton
    fun provideMoteurGit(
        @ApplicationContext contexte: Context,
        lanceur: NativeProcessLauncher,
    ): MoteurGit {
        val prefix = DispositionsBootstrap.prefix(contexte.filesDir)
        val binaireGit = File(prefix, "bin/git").takeIf { it.exists() }?.absolutePath
        return MoteurGitCli(
            lanceur = lanceur,
            binaireGit = binaireGit,
            identite = null, // Évolution : depuis les Paramètres utilisateur.
        )
    }
}
