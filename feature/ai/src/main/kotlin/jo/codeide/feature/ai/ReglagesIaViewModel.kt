package jo.codeide.feature.ai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.ai.ListerModelesIaUseCase
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.CoffreClesIa
import jo.codeide.core.domain.ConversationStore
import jo.codeide.core.domain.FournisseursIa
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.domain.RetentionIa
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** État de la clé d'un fournisseur, tel que visible dans les réglages. */
public enum class EtatCleFournisseur {
    /** Aucune clé enregistrée. */
    ABSENTE,

    /** Clé chiffrée présente et lisible. */
    ENREGISTREE,

    /** Blob illisible (Keystore invalidé) — la clé doit être ressaisie. */
    A_RESAISIR,
}

/** Ligne « fournisseur » de la carte Fournisseur — la clé n'y figure JAMAIS. */
public data class EtatFournisseurIaUi(
    public val identifiant: String,
    public val etatCle: EtatCleFournisseur,
    /** Aperçu public « ••••1234 », ou `null` — seule trace autorisée (ADR 0124 §1). */
    public val apercu: String?,
)

/** Écran Paramètres → IA (état) — aucun secret n'y figure jamais. */
public data class EtatReglagesIa(
    public val chargement: Boolean = true,
    public val active: Boolean = false,
    public val consentementDonne: Boolean = false,
    public val fournisseurs: List<EtatFournisseurIaUi> = emptyList(),
    public val fournisseurActif: String = FournisseursIa.ANTHROPIC,
    public val modeleActif: String = "",
    public val modelesRecenses: List<String> = emptyList(),
    public val maxJetonsReponse: Int = AiSettings().maxJetonsReponse,
    public val delaiSecondes: Int = AiSettings().delaiSecondes,
    public val envoyerFichierActif: Boolean = true,
    public val envoyerListeChemins: Boolean = true,
    public val envoyerDiagnostics: Boolean = true,
    public val retentionConversations: RetentionIa = RetentionIa.JOURS_30,
    public val actualisationEnCours: Boolean = false,
)

/** Actions de l'écran Réglages IA (UDF). */
public sealed interface ActionReglagesIa {
    /**
     * Interrupteur maître : `true` ouvre le consentement (aucun état
     * persisté ici — c'est l'écran de consentement qui active) ; `false`
     * désactive ET révoque le consentement.
     */
    public data class ChangerActivation(
        public val active: Boolean,
    ) : ActionReglagesIa

    /** Ouvre l'écran de consentement (ce qui est envoyé). */
    public data object OuvrirConsentement : ActionReglagesIa

    /** Ouvre le détail d'un fournisseur. */
    public data class OuvrirFournisseur(
        public val identifiant: String,
    ) : ActionReglagesIa

    /**
     * Rétention de l'historique des conversations (lot IA-6, ADR 0124
     * §5) : 7 j / 30 j / illimitée — appliquée dès la prochaine purge.
     */
    public data class ChoisirRetention(
        public val retention: RetentionIa,
    ) : ActionReglagesIa

    /**
     * Tout effacer dans l'historique (lot IA-6) — confirmation
     * préalable côté fragment (jamais d'effet silencieux).
     */
    public data object ToutEffacerHistorique : ActionReglagesIa

    /** Sélectionne le fournisseur actif (routeur IA). */
    public data class ChoisirFournisseur(
        public val identifiant: String,
    ) : ActionReglagesIa

    /** Modèle du fournisseur actif (jamais codé en dur — ADR 0123 §6). */
    public data class ChangerModele(
        public val valeur: String,
    ) : ActionReglagesIa

    /** Bouton « Actualiser la liste » (listModels du fournisseur actif). */
    public data object ActualiserModeles : ActionReglagesIa

    /** Interrupteur de confidentialité (ADR 0124 §4). */
    public data class ChangerConfidentialite(
        public val aspect: AspectConfidentialite,
        public val valeur: Boolean,
    ) : ActionReglagesIa

    /** Plafond de jetons de réponse. */
    public data class ChangerMaxJetons(
        public val valeur: Int,
    ) : ActionReglagesIa

    /** Délai maximal d'une requête (secondes). */
    public data class ChangerDelai(
        public val valeur: Int,
    ) : ActionReglagesIa
}

/** Aspects de confidentialité pilotables (ADR 0124 §4). */
public enum class AspectConfidentialite {
    FICHIER_ACTIF,
    LISTE_CHEMINS,
    DIAGNOSTICS,
}

/** Effets à sens unique (navigation, messages). */
public sealed interface EffetReglagesIa {
    /** Ouvre le consentement (premier usage ou lien « Ce qui est envoyé »). */
    public data object OuvrirConsentement : EffetReglagesIa

    /** Ouvre le détail d'un fournisseur. */
    public data class OuvrirFournisseur(
        public val identifiant: String,
    ) : EffetReglagesIa

    /**
     * Message éphémère : ressource + argument optionnel — jamais un
     * secret (aucun texte de clé, de journal ou de fournisseur brut).
     */
    public data class Message(
        @param:StringRes public val ressource: Int,
        public val argument: String? = null,
    ) : EffetReglagesIa
}

/**
 * ViewModel des Réglages IA (mission IA, lot IA-2) : orchestre le port
 * [ReglagesIaRepository] et le [CoffreClesIa] — aucune logique métier
 * (règle 5), aucun secret dans l'état (ADR 0124 §1, testé).
 */
@HiltViewModel
public class ReglagesIaViewModel
    @Inject
    constructor(
        private val reglages: ReglagesIaRepository,
        private val coffre: CoffreClesIa,
        private val listerModeles: ListerModelesIaUseCase,
        private val historique: ConversationStore,
    ) : ViewModel() {
        private val etatInterne = MutableStateFlow(EtatReglagesIa())

        private val effetsInternes = Channel<EffetReglagesIa>(Channel.BUFFERED)

        /** État observable de l'écran. */
        public val etat: StateFlow<EtatReglagesIa> = etatInterne.asStateFlow()

        /** Effets à collecter par le fragment (navigation, messages). */
        public val effets: Flow<EffetReglagesIa> = effetsInternes.receiveAsFlow()

        init {
            viewModelScope.launch {
                reglages.observeAiSettings().collect { courant ->
                    etatInterne.value = copieDe(courant)
                }
            }
        }

        /** Point d'entrée unique des actions (UDF). */
        public fun onAction(action: ActionReglagesIa) {
            when (action) {
                is ActionReglagesIa.ChangerActivation -> {
                    changerActivation(action.active)
                }

                ActionReglagesIa.OuvrirConsentement -> {
                    emettre(EffetReglagesIa.OuvrirConsentement)
                }

                is ActionReglagesIa.OuvrirFournisseur -> {
                    emettre(EffetReglagesIa.OuvrirFournisseur(action.identifiant))
                }

                is ActionReglagesIa.ChoisirFournisseur -> {
                    if (action.identifiant in FOURNISSEURS_CONNUS) {
                        ecrire { it.copy(fournisseurActif = action.identifiant) }
                    }
                }

                is ActionReglagesIa.ChoisirRetention -> {
                    ecrire { it.copy(retentionConversations = action.retention) }
                }

                ActionReglagesIa.ToutEffacerHistorique -> {
                    viewModelScope.launch {
                        historique.toutSupprimer()
                        emettre(EffetReglagesIa.Message(R.string.ia_reglages_historique_purge))
                    }
                }

                is ActionReglagesIa.ChangerModele -> {
                    changerModele(action.valeur)
                }

                ActionReglagesIa.ActualiserModeles -> {
                    actualiserModeles()
                }

                is ActionReglagesIa.ChangerConfidentialite -> {
                    changerConfidentialite(action.aspect, action.valeur)
                }

                is ActionReglagesIa.ChangerMaxJetons -> {
                    ecrire { it.copy(maxJetonsReponse = action.valeur.coerceIn(JETONS_MIN, JETONS_MAX)) }
                }

                is ActionReglagesIa.ChangerDelai -> {
                    ecrire { it.copy(delaiSecondes = action.valeur.coerceIn(DELAI_MIN, DELAI_MAX)) }
                }
            }
        }

        /**
         * Activation : `true` ne persiste RIEN — l'écran de consentement
         * confirme (garde « sans consentement, aucune requête », critère
         * IA-2) ; `false` désactive et révoque (ADR : révocation dans E4).
         */
        private fun changerActivation(active: Boolean) {
            if (active) {
                emettre(EffetReglagesIa.OuvrirConsentement)
            } else {
                ecrire { it.copy(assistantActive = false, consentementDonne = false, consentementDateEpochMs = 0L) }
            }
        }

        private fun changerModele(valeur: String) =
            ecrire { courant ->
                if (courant.fournisseurActif == FournisseursIa.OPENAI_COMPATIBLE) {
                    courant.copy(modeleOpenAiCompatible = valeur.trim())
                } else {
                    courant.copy(modeleAnthropic = valeur.trim())
                }
            }

        private fun changerConfidentialite(
            aspect: AspectConfidentialite,
            valeur: Boolean,
        ) = ecrire { courant ->
            when (aspect) {
                AspectConfidentialite.FICHIER_ACTIF -> courant.copy(envoyerFichierActif = valeur)
                AspectConfidentialite.LISTE_CHEMINS -> courant.copy(envoyerListeChemins = valeur)
                AspectConfidentialite.DIAGNOSTICS -> courant.copy(envoyerDiagnostics = valeur)
            }
        }

        /** listModels du fournisseur actif — la garde de consentement est du domaine. */
        private fun actualiserModeles() {
            val courant = etatInterne.value
            if (!courant.active || !courant.consentementDonne) {
                emettre(EffetReglagesIa.Message(R.string.ia_erreur_consentement))
                return
            }
            etatInterne.value = courant.copy(actualisationEnCours = true)
            viewModelScope.launch {
                when (val resultat = listerModeles()) {
                    is AppResult.Success -> {
                        val modeles = resultat.value.map { it.id }.filter { it.isNotBlank() }
                        reglages.update { preferences ->
                            if (preferences.fournisseurActif == FournisseursIa.OPENAI_COMPATIBLE) {
                                preferences.copy(modelesRecensesOpenAiCompatible = modeles)
                            } else {
                                preferences.copy(modelesRecensesAnthropic = modeles)
                            }
                        }
                        emettre(EffetReglagesIa.Message(R.string.ia_modeles_recenses, modeles.size.toString()))
                    }

                    is AppResult.Failure -> {
                        emettre(PresentationErreursIa.message(resultat.error).enEffet())
                    }
                }
                etatInterne.value = etatInterne.value.copy(actualisationEnCours = false)
            }
        }

        /** État projeté : réglages + états de clé des deux fournisseurs. */
        private suspend fun copieDe(courant: AiSettings): EtatReglagesIa =
            EtatReglagesIa(
                chargement = false,
                active = courant.assistantActive,
                consentementDonne = courant.consentementDonne,
                fournisseurs = FOURNISSEURS_CONNUS.map { identifiant -> etatFournisseur(identifiant) },
                fournisseurActif = courant.fournisseurActif,
                modeleActif = courant.modeleActif,
                modelesRecenses = courant.modelesRecensesActifs,
                maxJetonsReponse = courant.maxJetonsReponse,
                delaiSecondes = courant.delaiSecondes,
                envoyerFichierActif = courant.envoyerFichierActif,
                envoyerListeChemins = courant.envoyerListeChemins,
                envoyerDiagnostics = courant.envoyerDiagnostics,
                retentionConversations = courant.retentionConversations,
                actualisationEnCours = etatInterne.value.actualisationEnCours,
            )

        private suspend fun etatFournisseur(identifiant: String): EtatFournisseurIaUi {
            if (!coffre.contient(identifiant)) {
                return EtatFournisseurIaUi(identifiant, EtatCleFournisseur.ABSENTE, null)
            }
            val lisible = coffre.lire(identifiant) is AppResult.Success
            return EtatFournisseurIaUi(
                identifiant,
                if (lisible) EtatCleFournisseur.ENREGISTREE else EtatCleFournisseur.A_RESAISIR,
                coffre.apercu(identifiant),
            )
        }

        private fun ecrire(transformation: (AiSettings) -> AiSettings) {
            viewModelScope.launch { reglages.update(transformation) }
        }

        private fun emettre(effet: EffetReglagesIa) {
            viewModelScope.launch { effetsInternes.send(effet) }
        }

        private companion object {
            val FOURNISSEURS_CONNUS = listOf(FournisseursIa.ANTHROPIC, FournisseursIa.OPENAI_COMPATIBLE)
            const val JETONS_MIN = 128
            const val JETONS_MAX = 65_536
            const val DELAI_MIN = 5
            const val DELAI_MAX = 3_600
        }
    }

/** Message d'erreur du domaine → effet de CET écran (UDF). */
private fun MessageIa.enEffet(): EffetReglagesIa = EffetReglagesIa.Message(ressource, argument)
