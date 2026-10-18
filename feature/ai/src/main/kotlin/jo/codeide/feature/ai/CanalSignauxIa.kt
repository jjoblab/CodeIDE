package jo.codeide.feature.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signal envoyé par l'éditeur vers le chat (mission IA, lot IA-5, ADR
 * 0126) : consigne de la barre en ligne (envoi immédiat — bouton
 * « Envoyer » de la barre = action utilisateur explicite, ADR 0121
 * point 3), pré-remplissage Problèmes/Console/Logcat (jamais auto-envoyé)
 * ou relance de la dernière génération (« Réessayer » de la carte de
 * diff).
 */
public sealed interface SignalIaExterne {
    /**
     * Consigne à porter dans le compositeur du chat.
     *
     * @property texte consigne (jamais vide).
     * @property envoiImmediat `true` = l'envoi part dès la réception
     * (action utilisateur explicite côté barre) ; `false` = le champ
     * est pré-rempli, l'utilisateur relit puis envoie.
     */
    public data class Consigne(
        public val texte: String,
        public val envoiImmediat: Boolean,
    ) : SignalIaExterne

    /** Relance la dernière génération (garde : aucune si rien à relancer). */
    public data object Regenerer : SignalIaExterne
}

/**
 * Canal singleton éditeur → chat (mission IA, lot IA-5) : le pont
 * [PontIaEditeurImpl] publie, le [ChatViewModel] consomme. Un
 * [StateFlow] à valeur unique suffit — l'éditeur et le chat vivent dans
 * la même activité, un seul signal est en attente à tout instant ; la
 * consommation le remet à `null` (aucun rejeu silencieux à la rotation :
 * le signal est déjà matérialisé dans l'état du chat).
 */
@Singleton
public class CanalSignauxIa
    @Inject
    constructor() {
        private val interne = MutableStateFlow<SignalIaExterne?>(null)

        /** Dernier signal publié (consommé une fois, puis `null`). */
        public val signal: StateFlow<SignalIaExterne?> = interne.asStateFlow()

        /** Publie un signal (remplace un éventuel signal non consommé). */
        public fun publier(signal: SignalIaExterne) {
            interne.value = signal
        }

        /** Consomme le signal courant (aucun rejeu). */
        public fun consommer() {
            interne.value = null
        }
    }
