package jo.codeide.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.ai.FournisseurContexteProjet
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Un résultat de la recherche IA (rapport IA, lot 3 — d'après la
 * maquette) : un FICHIER du projet (chemin relatif) — les symboles LSP
 * restent HORS périmètre (même sobriété que la spec RECHERCHE.md §1).
 *
 * @property cheminRelatif chemin relatif POSIX du fichier.
 * @property nom nom du dernier segment (affiché en gras).
 * @property dossier chemin du parent (affiché en discret).
 */
public data class ResultatRechercheIa(
    public val cheminRelatif: String,
    public val nom: String,
    public val dossier: String,
)

/** État de l'écran Recherche du tiroir IA (rapport IA, lot 3). */
public data class EtatRechercheIa(
    /** Requête courante (filtre local sur les chemins du projet). */
    public val requete: String = "",
    /** Résultats filtrés (bornés — la liste de chemins l'est déjà). */
    public val resultats: List<ResultatRechercheIa> = emptyList(),
    /** `true` tant que la liste des chemins du projet se charge. */
    public val chargement: Boolean = true,
    /** `true` quand AUCUN projet n'est ouvert (liste vide honnête). */
    public val projetFerme: Boolean = false,
)

/**
 * ViewModel de l'écran Recherche du tiroir IA (rapport IA, lot 3 —
 * d'après la maquette) : recherche de FICHIERS du projet par nom —
 * même source que le sélecteur `@` du chat ([FournisseurContexteProjet.listerChemins],
 * liste BORNÉE et élaguée côté implémentation), filtre LOCAL sensible
 * à la casse. L'action « Demander à l'IA » vit dans le fragment (elle
 * bascule sur le chat et préremplit la saisie — mécanisme du tiroir).
 *
 * Les SYMBOLES (LSP) restent hors périmètre (spec RECHERCHE.md §1) :
 * un écart honnête documenté dans le rapport final du lot 3.
 */
@HiltViewModel
public class RechercheIaViewModel
@Inject
constructor(private val fournisseurContexte: FournisseurContexteProjet) :
    ViewModel() {
    private val etatInterne = MutableStateFlow(EtatRechercheIa())

    /** État observable de l'écran. */
    public val etat: StateFlow<EtatRechercheIa> = etatInterne.asStateFlow()

    /** Chemins du projet, chargés UNE fois à l'arrivée sur l'écran. */
    private var chemins: List<String> = emptyList()

    init {
        viewModelScope.launch {
            when (val liste = fournisseurContexte.listerChemins()) {
                is AppResult.Success -> {
                    chemins = liste.value
                    etatInterne.update {
                        it.copy(chargement = false, projetFerme = false, resultats = filtrer(it.requete))
                    }
                }

                is AppResult.Failure -> {
                    // Projet fermé ou racine illisible : liste VIDE honnête
                    // (l'écran l'affiche — jamais une recherche muette).
                    chemins = emptyList()
                    etatInterne.update { it.copy(chargement = false, projetFerme = true, resultats = emptyList()) }
                }
            }
        }
    }

    /** Filtre les chemins par la requête (sensible à la casse — le nom de fichier l'est). */
    public fun changerRequete(requete: String) {
        etatInterne.update { it.copy(requete = requete, resultats = filtrer(requete)) }
    }

    /** Filtre local : la requête vide montre TOUT (liste bornée), sinon le chemin contient la requête. */
    private fun filtrer(requete: String): List<ResultatRechercheIa> {
        val candidats = if (requete.isBlank()) chemins else chemins.filter { it.contains(requete) }
        return candidats.take(NB_RESULTATS_MAX).map { chemin -> resultatDe(chemin) }
    }

    /** Découpe un chemin relatif en nom + dossier (affichage de la ligne). */
    private fun resultatDe(chemin: String): ResultatRechercheIa {
        val segments = chemin.split('/')
        val nom = segments.lastOrNull().orEmpty()
        val dossier = segments.dropLast(1).joinToString("/")
        return ResultatRechercheIa(cheminRelatif = chemin, nom = nom, dossier = dossier)
    }

    private companion object {
        /** Plafond d'affichage des résultats (la liste de chemins est déjà bornée). */
        private const val NB_RESULTATS_MAX = 200
    }
}
