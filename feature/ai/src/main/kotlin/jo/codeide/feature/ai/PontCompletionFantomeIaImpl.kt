package jo.codeide.feature.ai

import jo.codeide.core.ai.AiRequest
import jo.codeide.core.ai.CompletionFantomeIa
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.model.AppResult
import jo.codeide.core.ui.PontCompletionFantomeIa
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation du pont de complétion fantôme (IA-8b, ADR 0129) :
 * opt-in respecté, UNE requête en vol (la nouvelle annule la précédente),
 * plafond mensuel de caractères acceptés (désactivation au plafond),
 * journalisation sobre (aucun contenu — ADR 0124 §3).
 */
@Singleton
public class PontCompletionFantomeIaImpl
    @Inject
    constructor(
        private val fournisseur: jo.codeide.core.ai.AiProvider,
        private val reglages: ReglagesIaRepository,
        private val horloge: jo.codeide.core.domain.TimeProvider,
    ) : PontCompletionFantomeIa {
        private val _disponible = MutableStateFlow(false)
        override val disponible: StateFlow<Boolean> = _disponible.asStateFlow()

        private var enVol: Job? = null

        /** Réévalue la disponibilité (opt-in + plafond) — appelé au démarrage. */
        public suspend fun rafraichirDisponibilite() {
            val courant = lireReglages()
            _disponible.value =
                courant != null &&
                !CompletionFantomeIa.plafondAtteint(
                    courant.completionFantomeActive,
                    courant.plafondCompletionCaracteres,
                    courant.completionAccepteesCaracteres,
                    courant.completionMoisCompteur,
                    moisCourant(),
                )
        }

        /**
         * Exemption detekt ciblée (règle 16) : ReturnCount — gardes (opt-in,
         * plafond, modèle) + succès/refus dédiés. SwallowedException — une
         * complétion en échec est convertie en `null` (confort, ADR 0129),
         * jamais remontée à la saisie.
         */
        @Suppress("ReturnCount", "SwallowedException")
        override suspend fun suggerer(
            cheminRelatif: String,
            prefixe: String,
            suffixe: String,
        ): String? {
            enVol?.cancel()
            enVol = null
            val courant = lireReglages() ?: return null
            if (!courant.completionFantomeActive || courant.modeleActif.isBlank()) return null
            if (CompletionFantomeIa.plafondAtteint(
                    courant.completionFantomeActive,
                    courant.plafondCompletionCaracteres,
                    courant.completionAccepteesCaracteres,
                    courant.completionMoisCompteur,
                    moisCourant(),
                )
            ) {
                _disponible.value = false
                return null
            }
            val requete =
                AiRequest(CompletionFantomeIa.requete(prefixe, suffixe), courant.modeleActif, maxOutputTokens = 256)
            return try {
                val texte = withTimeout(DELAI_MS) { agreger(fournisseur.stream(requete)) }
                enVol = null
                CompletionFantomeIa.extraire(texte).ifEmpty { null }
            } catch (annulation: kotlinx.coroutines.CancellationException) {
                throw annulation // règle 6 : toujours relancée
            } catch (incident: Exception) {
                // échec silencieux assumé : une complétion en échec ne dérange
                // jamais la saisie (le fantôme est un confort, ADR 0129).
                null
            }
        }

        override fun annuler() {
            enVol?.cancel()
            enVol = null
        }

        override suspend fun noterAcceptation(caracteres: Int) {
            if (caracteres <= 0) return
            reglages.update { courant ->
                val mois = moisCourant()
                val deja =
                    if (courant.completionMoisCompteur == mois) courant.completionAccepteesCaracteres else 0
                courant.copy(
                    completionAccepteesCaracteres = deja + caracteres,
                    completionMoisCompteur = mois,
                )
            }
            rafraichirDisponibilite()
        }

        private suspend fun agreger(flux: kotlinx.coroutines.flow.Flow<jo.codeide.core.ai.AiEvent>): String {
            val texte = StringBuilder()
            flux.collect { evenement ->
                when (evenement) {
                    is jo.codeide.core.ai.AiEvent.TextDelta -> texte.append(evenement.text)
                    is jo.codeide.core.ai.AiEvent.Done -> return@collect
                    is jo.codeide.core.ai.AiEvent.Failure -> return@collect
                    else -> Unit
                }
            }
            return texte.toString()
        }

        private suspend fun lireReglages(): jo.codeide.core.domain.AiSettings? =
            when (val lecture = reglages.courant()) {
                is AppResult.Success -> lecture.value
                is AppResult.Failure -> null
            }

        private fun moisCourant(): Int {
            val instant =
                java.time.Instant
                    .ofEpochMilli(horloge.nowMillis())
                    .atZone(java.time.ZoneId.systemDefault())
            return instant.year * ANNEE_MULT + instant.monthValue // format aaaaMM (compteur)
        }

        private companion object {
            /** Garde-fou latence : une complétion qui dépasse est abandonnée. */
            const val DELAI_MS = 6_000L

            /** Multiplicateur aaaa × 100 + mm (compteur mensuel). */
            const val ANNEE_MULT = 100
        }
    }
