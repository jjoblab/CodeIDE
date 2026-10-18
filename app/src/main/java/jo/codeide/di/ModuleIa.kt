package jo.codeide.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.ai.providers.AiHttpTransport
import jo.codeide.ai.providers.AnthropicProvider
import jo.codeide.ai.providers.OpenAiCompatibleProvider
import jo.codeide.core.ai.AiProvider
import jo.codeide.core.ai.FabriqueFournisseurIa
import jo.codeide.core.ai.FournisseurIaActif
import jo.codeide.core.aistorage.SourceCleFournisseur
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.CoffreClesIa
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.FournisseursIa
import jo.codeide.core.domain.ValidationUrlIa
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Liaisons Hilt de la **mission IA** (ADR 0122, lot IA-2) — l'app est le
 * SEUL endroit où les mondes IA se rencontrent :
 *
 * - [AiHttpTransport] (le point de sortie réseau IA unique, ADR 0121
 *   point 2) est fourni ici : les adaptateurs de `:ai:providers` ne
 *   dépendent d'aucun autre module d'infrastructure ;
 * - [FabriqueFournisseurIa] est implémentée ici : elle choisit
 *   l'adaptateur (Anthropic / compatible OpenAI) selon les réglages et
 *   lui attache la source de clé **par fournisseur** (coffre Keystore,
 *   ADR 0124 §1) — la clé ne circule jamais dans la requête (ADR 0123) ;
 * - [FournisseurIaActif] (domaine : garde consentement + configuration,
 *   sélection du fournisseur actif) est lié sur le port [AiProvider] :
 *   tous les consommateurs (cas d'usage, ViewModels IA-3+) l'injectent
 *   sans savoir qui répond derrière.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ModuleIa {
    /** Transport HTTP IA — un seul pour tout le processus. */
    @Provides
    @Singleton
    internal fun transportIa(dispatchers: DispatcherProvider): AiHttpTransport = AiHttpTransport(dispatchers)
}

/** Liaisons abstraites : le routeur du domaine est LE fournisseur IA. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ModuleIaBinds {
    @Binds
    @Singleton
    internal abstract fun fabriqueFournisseurIa(impl: FabriqueFournisseurIaReelle): FabriqueFournisseurIa

    @Binds
    @Singleton
    internal abstract fun fournisseurIa(impl: FournisseurIaActif): AiProvider
}

/**
 * Fabrique réelle : mappe les réglages (fournisseur actif, URL de base)
 * sur les adaptateurs publiés, avec la source de clé du coffre. Retourne
 * `null` pour un fournisseur inconnu ou une URL invalide — le routeur du
 * domaine traduit en `AppError.Ai(Configuration)`.
 */
internal class FabriqueFournisseurIaReelle
    @Inject
    constructor(
        private val transport: AiHttpTransport,
        private val dispatchers: DispatcherProvider,
        private val journal: AppLogger,
        private val coffre: CoffreClesIa,
    ) : FabriqueFournisseurIa {
        override suspend fun creer(reglages: AiSettings): AiProvider? =
            when (reglages.fournisseurActif) {
                FournisseursIa.ANTHROPIC -> {
                    val url = reglages.urlBaseAnthropic.ifBlank { URL_ANTHROPIC_DEFAUT }
                    if (ValidationUrlIa.erreur(url) != null) {
                        null
                    } else {
                        AnthropicProvider(
                            transport = transport,
                            dispatchers = dispatchers,
                            apiKeySource = SourceCleFournisseur(coffre, FournisseursIa.ANTHROPIC),
                            logger = journal,
                            baseUrl = url,
                        )
                    }
                }

                FournisseursIa.OPENAI_COMPATIBLE -> {
                    val url = reglages.urlBaseOpenAiCompatible
                    if (ValidationUrlIa.erreur(url) != null) {
                        null
                    } else {
                        OpenAiCompatibleProvider(
                            transport = transport,
                            dispatchers = dispatchers,
                            apiKeySource = SourceCleFournisseur(coffre, FournisseursIa.OPENAI_COMPATIBLE),
                            logger = journal,
                            baseUrl = url,
                        )
                    }
                }

                else -> {
                    null
                }
            }

        private companion object {
            const val URL_ANTHROPIC_DEFAUT = "https://api.anthropic.com"
        }
    }
