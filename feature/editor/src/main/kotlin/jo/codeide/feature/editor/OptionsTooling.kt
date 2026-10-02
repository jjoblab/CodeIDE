package jo.codeide.feature.editor

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Détenteur des réglages tooling consommés par l'espace de travail (v3) —
 * même patron que `PorteurStyleCurseur` du terminal (ADR 0059) : une seule
 * collecte des paramètres applicatifs en tâche de fond, la dernière valeur
 * connue servie à la demande, sans coupler l'espace à un flux de réglages.
 *
 * L'exécution y lit : les arguments Gradle du prochain build (`--offline` +
 * arguments libres — l'orchestrateur garde la main sur `--console=plain`,
 * ajouté en DERNIER côté serveur). L'ancien réglage « afficher les tâches »
 * a disparu avec la console à rangées (v0.46.0, ADR 0078) : le flux brut
 * de Gradle porte ses propres lignes « > Task ».
 *
 * Classe pure testable : la collecte vit dans une portée interne bornée au
 * dispatcher injecté (règle 5), l'état exposé est un instantané immuable.
 *
 * @param observerReglages source des paramètres applicatifs.
 * @param dispatchers répartition des fils (collecte hors principal).
 */
@Singleton
class OptionsTooling
    @Inject
    constructor(
        observerReglages: ObserveSettingsUseCase,
        dispatchers: DispatcherProvider,
    ) {
        /**
         * Derniers réglages connus — @Volatile + mutable sans private set :
         * écrits par la collecte de production, lisibles à tout instant par
         * l'exécution (arguments du prochain build) — un aperçu cohérent
         * sans verrou.
         */
        @Volatile
        internal var courants: AppSettings = AppSettings()

        /** Arguments Gradle du prochain build (mode hors ligne + libres). */
        internal fun argumentsBuild(): List<String> {
            val libres =
                courants.toolingArguments
                    .split(SEPARATEURS)
                    .filter { it.isNotBlank() }
            return if (courants.toolingHorsLigne) listOf(ARGUMENT_HORS_LIGNE) + libres else libres
        }

        private val portee = CoroutineScope(SupervisorJob() + dispatchers.default)

        init {
            portee.launch {
                observerReglages().collect { courants = it }
            }
        }

        private companion object {
            /** Séparateurs des arguments libres (espaces et tabulations). */
            val SEPARATEURS = Regex("\\s+")

            /** Argument Gradle du mode hors ligne. */
            const val ARGUMENT_HORS_LIGNE = "--offline"
        }
    }
