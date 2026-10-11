package jo.codeide.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.model.AppResult
import jo.codeide.lsp.host.DossierIndexLsp
import jo.codeide.lsp.host.SourceClasspathLsp
import jo.codeide.tooling.client.GradleApiImpl
import java.io.File
import javax.inject.Singleton

/**
 * Liaisons Hilt de l'hôte LSP (mission « LSP Java » étape 4 lot h2,
 * docs/LSP_JAVA.md §16.2) — le PONT entre le tooling et l'hôte :
 *
 * - [SourceClasspathLsp] : l'accesseur protocole BRUT de `tooling:client`
 *   ([GradleApiImpl.classpathBrut]) habillé en couture de `:lsp:host`.
 *   L'app est le SEUL endroit où les deux mondes se rencontrent (règle
 *   §2.2 : les features consomment le tooling par interfaces du domaine
 *   ; ici l'interface vit dans `:lsp:host`, l'implémentation appelle le
 *   client — l'échec se traduit en `null` journalisé, jamais une
 *   exception vers la session) ;
 * - [DossierIndexLsp] : le stockage d'index partagé injecté (ADR 0111
 *   §2) — `filesDir/codeide/lsp/index`, répertoire PRIVÉ stable : les
 *   segments survaux aux redémarrages, la déduplication par empreinte
 *   profite à tous les projets, et l'auto-guérison reconstruit tout
 *   segment perdu (l'index est un CACHE, jamais une autorité).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ModuleLsp {
    /** Résolution du classpath brut (protocole v8) pour la session LSP. */
    @Provides
    @Singleton
    internal fun sourceClasspathLsp(
        api: GradleApiImpl,
        journal: AppLogger,
    ): SourceClasspathLsp =
        SourceClasspathLsp { dossier, arguments ->
            when (val resultat = api.classpathBrut(dossier, arguments)) {
                is AppResult.Success -> {
                    resultat.value
                }

                is AppResult.Failure -> {
                    journal.w(TAG) {
                        "classpath brut non résolu (${resultat.error}) — session LSP non nourrie"
                    }
                    null
                }
            }
        }

    /** Stockage d'index partagé : répertoire privé de l'app. */
    @Provides
    @Singleton
    internal fun dossierIndexLsp(
        @ApplicationContext context: Context,
    ): DossierIndexLsp = DossierIndexLsp { File(context.filesDir, "codeide/lsp/index") }

    private const val TAG = "ModuleLsp"
}
