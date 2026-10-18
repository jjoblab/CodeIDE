package jo.codeide.feature.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.ai.AiProvider
import jo.codeide.core.ai.FabriqueFournisseurIa
import jo.codeide.core.domain.CoffreClesIa
import jo.codeide.core.domain.FournisseursIa
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.domain.ValidationUrlIa
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Résultat d'un test de connexion — l'erreur typée, jamais le réseau brut. */
public sealed interface ResultatTestIa {
    /** La clé est acceptée. */
    public data object Succes : ResultatTestIa

    /** Échec typé (traduit par [PresentationErreursIa]). */
    public data class Echec(
        public val erreur: AppError,
    ) : ResultatTestIa
}

/**
 * Écran Détail fournisseur (état) — le champ de saisie et la clé en clair
 * n'y figurent **jamais** (ADR 0124 §1, critère IA-2 : « la clé saisie
 * n'apparaît dans aucun état d'UI ») : seul l'aperçu `••••1234` remonte.
 */
public data class EtatFournisseurIaDetail(
    public val identifiant: String = FournisseursIa.ANTHROPIC,
    public val chargement: Boolean = true,
    public val urlBase: String = "",
    public val urlValide: Boolean = true,
    public val etatCle: EtatCleFournisseur = EtatCleFournisseur.ABSENTE,
    public val apercuCle: String? = null,
    public val estActif: Boolean = false,
    public val testEnCours: Boolean = false,
    public val resultatTest: ResultatTestIa? = null,
)

/** Actions du détail fournisseur (UDF). */
public sealed interface ActionFournisseurIa {
    /** URL de base éditée — persistée uniquement si valide. */
    public data class ChangerUrl(
        public val valeur: String,
    ) : ActionFournisseurIa

    /**
     * Enregistre la clé saisie (chiffrée dans le coffre) — le tableau est
     * effacé après usage ; le ViewModel ne le copie JAMAIS dans l'état.
     */
    public data class EnregistrerCle(
        public val cle: CharArray,
    ) : ActionFournisseurIa

    /** Supprime la clé du coffre. */
    public data object SupprimerCle : ActionFournisseurIa

    /** Test de connexion (ping du fournisseur ÉDITÉ, pas de l'actif). */
    public data object TesterConnexion : ActionFournisseurIa

    /** Fait de ce fournisseur le fournisseur actif. */
    public data object UtiliserCeFournisseur : ActionFournisseurIa
}

/** Effets du détail fournisseur. */
public sealed interface EffetFournisseurIa {
    /** Message éphémère (ressource + argument optionnel). */
    public data class Message(
        @param:androidx.annotation.StringRes public val ressource: Int,
        public val argument: String? = null,
    ) : EffetFournisseurIa

    /** La clé vient d'être enregistrée : le fragment vide le champ. */
    public data object CleEnregistree : EffetFournisseurIa
}

/**
 * ViewModel du détail fournisseur (mission IA, lot IA-2). Le test de
 * connexion passe par la [FabriqueFournisseurIa] du domaine avec les
 * réglages du fournisseur ÉDITÉ — l'app ne compose que la fabrique.
 */
@HiltViewModel
public class FournisseurIaViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val reglages: ReglagesIaRepository,
        private val coffre: CoffreClesIa,
        private val fabrique: FabriqueFournisseurIa,
    ) : ViewModel() {
        private val identifiant: String =
            savedStateHandle.get<String>(CLE_FOURNISSEUR)?.takeIf { it in FOURNISSEURS_CONNUS }
                ?: FournisseursIa.ANTHROPIC

        private val etatInterne = MutableStateFlow(EtatFournisseurIaDetail(identifiant = identifiant))

        private val effetsInternes = Channel<EffetFournisseurIa>(Channel.BUFFERED)

        /** État observable de l'écran. */
        public val etat: StateFlow<EtatFournisseurIaDetail> = etatInterne.asStateFlow()

        /** Effets à collecter par le fragment. */
        public val effets: Flow<EffetFournisseurIa> = effetsInternes.receiveAsFlow()

        init {
            viewModelScope.launch {
                reglages.observeAiSettings().collect { courant ->
                    val urlAffichee =
                        if (etatInterne.value.urlBase.isNotBlank()) {
                            etatInterne.value.urlBase
                        } else {
                            urlDe(courant)
                        }
                    etatInterne.value =
                        etatInterne.value.copy(
                            chargement = false,
                            urlBase = urlAffichee,
                            urlValide = ValidationUrlIa.erreur(urlAffichee) == null,
                            estActif = courant.fournisseurActif == identifiant,
                        )
                }
            }
            viewModelScope.launch { rafraichirEtatCle() }
        }

        /** Point d'entrée unique des actions (UDF). */
        public fun onAction(action: ActionFournisseurIa) {
            when (action) {
                is ActionFournisseurIa.ChangerUrl -> {
                    changerUrl(action.valeur)
                }

                is ActionFournisseurIa.EnregistrerCle -> {
                    enregistrerCle(action.cle)
                }

                ActionFournisseurIa.SupprimerCle -> {
                    viewModelScope.launch {
                        coffre.supprimer(identifiant)
                        rafraichirEtatCle()
                        emettre(EffetFournisseurIa.Message(R.string.ia_cle_supprimee))
                    }
                }

                ActionFournisseurIa.TesterConnexion -> {
                    testerConnexion()
                }

                ActionFournisseurIa.UtiliserCeFournisseur -> {
                    utiliserCeFournisseur()
                }
            }
        }

        private fun changerUrl(valeur: String) {
            val valide = ValidationUrlIa.erreur(valeur) == null
            etatInterne.value = etatInterne.value.copy(urlBase = valeur, urlValide = valide)
            if (valide) {
                viewModelScope.launch { reglages.update { ecrireUrl(it, valeur.trim()) } }
            }
        }

        private fun enregistrerCle(cle: CharArray) {
            if (cle.isEmpty()) {
                emettre(EffetFournisseurIa.Message(R.string.ia_erreur_cle_vide))
                return
            }
            viewModelScope.launch {
                when (val resultat = coffre.enregistrer(identifiant, cle)) {
                    is AppResult.Success -> {
                        rafraichirEtatCle()
                        emettre(EffetFournisseurIa.CleEnregistree)
                        emettre(EffetFournisseurIa.Message(R.string.ia_cle_enregistree))
                    }

                    is AppResult.Failure -> {
                        val message = PresentationErreursIa.message(resultat.error)
                        emettre(EffetFournisseurIa.Message(message.ressource, message.argument))
                    }
                }
            }
        }

        /**
         * Test de connexion : construit le fournisseur ÉDITÉ (fabrique du
         * domaine) et appelle `ping()` — la garde de consentement n'est pas
         * applicable ici (aucun contenu ne part, seul un ping d'authentification).
         */
        private fun testerConnexion() {
            val courant = etatInterne.value
            if (!courant.urlValide) {
                emettre(EffetFournisseurIa.Message(R.string.ia_url_erreur))
                return
            }
            etatInterne.value = courant.copy(testEnCours = true, resultatTest = null)
            viewModelScope.launch {
                val resultat =
                    when (val lecture = reglages.courant()) {
                        is AppResult.Success -> pingFournisseur(lecture.value)
                        is AppResult.Failure -> ResultatTestIa.Echec(lecture.error)
                    }
                etatInterne.value = etatInterne.value.copy(testEnCours = false, resultatTest = resultat)
            }
        }

        private suspend fun pingFournisseur(courantReglages: jo.codeide.core.domain.AiSettings): ResultatTestIa {
            val urlEditee = etatInterne.value.urlBase.trim()
            val reglagesEdite =
                when (identifiant) {
                    FournisseursIa.OPENAI_COMPATIBLE -> {
                        courantReglages.copy(
                            fournisseurActif = identifiant,
                            urlBaseOpenAiCompatible = urlEditee,
                        )
                    }

                    else -> {
                        courantReglages.copy(
                            fournisseurActif = identifiant,
                            urlBaseAnthropic = urlEditee,
                        )
                    }
                }
            val fournisseur =
                fabrique.creer(reglagesEdite)
                    ?: return ResultatTestIa.Echec(
                        AppError.Ai(AppError.AiReason.Configuration, "fournisseur non constructible"),
                    )
            return when (val ping = fournisseur.ping()) {
                is AppResult.Success -> ResultatTestIa.Succes
                is AppResult.Failure -> ResultatTestIa.Echec(ping.error)
            }
        }

        private fun utiliserCeFournisseur() {
            val courant = etatInterne.value
            if (!courant.urlValide) {
                emettre(EffetFournisseurIa.Message(R.string.ia_url_erreur))
                return
            }
            viewModelScope.launch {
                reglages.update { ecrireUrl(it.copy(fournisseurActif = identifiant), courant.urlBase.trim()) }
            }
        }

        private suspend fun rafraichirEtatCle() {
            val etat =
                if (!coffre.contient(identifiant)) {
                    EtatCleFournisseur.ABSENTE to null
                } else {
                    when (coffre.lire(identifiant)) {
                        is AppResult.Success -> EtatCleFournisseur.ENREGISTREE to coffre.apercu(identifiant)
                        is AppResult.Failure -> EtatCleFournisseur.A_RESAISIR to coffre.apercu(identifiant)
                    }
                }
            etatInterne.value = etatInterne.value.copy(etatCle = etat.first, apercuCle = etat.second)
        }

        /** URL des réglages pour ce fournisseur (l'URL éditée prime si saisie). */
        private fun urlDe(courant: jo.codeide.core.domain.AiSettings): String =
            when (identifiant) {
                FournisseursIa.OPENAI_COMPATIBLE -> courant.urlBaseOpenAiCompatible
                else -> courant.urlBaseAnthropic
            }

        private fun ecrireUrl(
            courant: jo.codeide.core.domain.AiSettings,
            valeur: String,
        ): jo.codeide.core.domain.AiSettings =
            when (identifiant) {
                FournisseursIa.OPENAI_COMPATIBLE -> courant.copy(urlBaseOpenAiCompatible = valeur)
                else -> courant.copy(urlBaseAnthropic = valeur)
            }

        private fun emettre(effet: EffetFournisseurIa) {
            viewModelScope.launch { effetsInternes.send(effet) }
        }

        /** Message d'erreur du domaine → effet de CET écran (UDF). */

        private companion object {
            val FOURNISSEURS_CONNUS = listOf(FournisseursIa.ANTHROPIC, FournisseursIa.OPENAI_COMPATIBLE)
            const val CLE_FOURNISSEUR = "identifiant_fournisseur"
        }
    }
