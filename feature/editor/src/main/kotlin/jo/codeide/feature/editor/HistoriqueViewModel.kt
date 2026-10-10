package jo.codeide.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.DiffUnifie
import jo.codeide.core.domain.EntreeHistorique
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.HistoriqueLocal
import jo.codeide.core.domain.LigneDiff
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Message de résultat d'une restauration — le fragment le rend en
 * snackbar (honnête, action « Annuler » quand c'est possible).
 */
sealed interface MessageHistorique {
    /** Version restaurée — l'ancienne version est conservée dans
     *  l'historique (annulable par construction ET immédiatement). */
    data object Restauree : MessageHistorique

    /** Contenu de la révision indisponible (binaire ou trop grand). */
    data object ContenuIndisponible : MessageHistorique

    /** L'écriture a échoué — le fichier n'a pas bougé. */
    data object EchecEcriture : MessageHistorique
}

/** Une révision rendue : l'entrée brute + son moment et sa période. */
data class RevisionUi(
    val entree: EntreeHistorique,
    val moment: MomentHistorique,
    val periode: PeriodeHistorique,
)

/**
 * État observable de la feuille « Historique » (mission H2, spec
 * HISTORIQUE_LOCAL.md § 5).
 *
 * @property nomFichier nom affiché (sous-titre de la feuille).
 * @property cheminRelatif chemin du fichier relatif au projet.
 * @property uri URI de document du fichier (lecture/écriture courante).
 * @property chargement `true` tant que la première liste n'est pas là.
 * @property revisions révisions les plus récentes d'abord.
 * @property selection révision dont le diff est affiché (`null` : liste).
 * @property modeActuel `true` : diff contre le CONTENU ACTUEL du fichier ;
 *           `false` : contre la révision PRÉCÉDENTE (l'état d'avant).
 * @property lignesDiff lignes du diff unifié affiché.
 * @property diffIndisponible la révision sélectionnée n'a pas de contenu
 *           consultable (binaire/trop grand — honnête, jamais inventé).
 * @property message résultat d'une action (restauration) à rendre.
 * @property contenuRestaure texte écrit par la dernière restauration (ou
 *           annulation) — l'onglet ouvert de l'éditeur s'en rafraîchit,
 *           puis [HistoriqueViewModel.consommerContenuRestaure] l'efface.
 */
data class EtatHistorique(
    val nomFichier: String = "",
    val cheminRelatif: String = "",
    val uri: String = "",
    val chargement: Boolean = true,
    val revisions: List<RevisionUi> = emptyList(),
    val selection: EntreeHistorique? = null,
    val modeActuel: Boolean = true,
    val lignesDiff: List<LigneDiff> = emptyList(),
    val diffIndisponible: Boolean = false,
    val message: MessageHistorique? = null,
    val contenuRestaure: String? = null,
)

/**
 * ViewModel de la feuille « Historique » d'un fichier (mission
 * « Historique local » H2) : liste des révisions (moments relatifs,
 * périodes), diff unifié (contre le contenu ACTUEL ou la révision
 * PRÉCÉDENTE — [DiffUnifie], partagé avec la future vue Git), et
 * RESTAURATION.
 *
 * La restauration est annulable PAR CONSTRUCTION : l'écriture passe par
 * le port `FileSystem` DÉCORÉ (la version d'avant part à l'historique
 * automatiquement, ADR 0106) — et le snackbar porte en plus une action
 * « Annuler » immédiate (réécriture du contenu d'avant, gardé ici).
 */
@HiltViewModel
class HistoriqueViewModel
    @Inject
    constructor(
        private val historique: HistoriqueLocal,
        private val fichiers: FileSystem,
    ) : ViewModel() {
        private val etatInterne = MutableStateFlow(EtatHistorique())

        val etat: StateFlow<EtatHistorique> = etatInterne.asStateFlow()

        /** Contenu AVANT la dernière restauration (action « Annuler »). */
        private var contenuAvantRestauration: String? = null

        /** Charge les révisions du fichier (appelé à l'ouverture de la feuille). */
        fun charger(
            uri: String,
            cheminRelatif: String,
            nomFichier: String,
        ) {
            if (etatInterne.value.chargement && etatInterne.value.revisions.isEmpty()) {
                etatInterne.value = EtatHistorique(uri = uri, cheminRelatif = cheminRelatif, nomFichier = nomFichier)
                recharger()
            }
        }

        /** Sélectionne une révision : son diff paraît (mode courant). */
        fun selectionner(entree: EntreeHistorique) {
            etatInterne.update { it.copy(selection = entree, diffIndisponible = false, lignesDiff = emptyList()) }
            calculerDiff()
        }

        /** Revient à la liste (ferme la page de diff). */
        fun fermerDiff() {
            etatInterne.update { it.copy(selection = null, lignesDiff = emptyList(), diffIndisponible = false) }
        }

        /** Bascule le diff : contenu ACTUEL ↔ révision PRÉCÉDENTE. */
        fun definirMode(actuel: Boolean) {
            if (etatInterne.value.modeActuel == actuel) return
            etatInterne.update { it.copy(modeActuel = actuel) }
            calculerDiff()
        }

        /**
         * Restaure la révision sélectionnée : écrit le contenu stocké via
         * le port DÉCORÉ (l'état AVANT part automatiquement à
         * l'historique). [EtatHistorique.contenuRestaure] porte le texte
         * écrit pour que l'onglet ouvert de l'éditeur se rafraîchisse —
         * sans quoi l'auto-sauvegarde écraserait la version restaurée
         * (leçon H2).
         */
        fun restaurer(entree: EntreeHistorique) {
            viewModelScope.launch {
                val contenu = historique.lireContenu(entree.id)
                if (contenu == null) {
                    etatInterne.update { it.copy(message = MessageHistorique.ContenuIndisponible) }
                    return@launch
                }
                val uri = etatInterne.value.uri
                val avant = fichiers.readText(uri).let { if (it is AppResult.Success) it.value else null }
                when (fichiers.writeText(uri, contenu)) {
                    is AppResult.Success -> {
                        contenuAvantRestauration = avant
                        etatInterne.update {
                            it.copy(message = MessageHistorique.Restauree, contenuRestaure = contenu)
                        }
                        recharger()
                    }

                    is AppResult.Failure -> {
                        etatInterne.update { it.copy(message = MessageHistorique.EchecEcriture) }
                    }
                }
            }
        }

        /** Action « Annuler » du snackbar : réécrit le contenu d'avant
         *  (l'onglet ouvert se rafraîchit par le même chemin). */
        fun annulerRestauration() {
            val avant = contenuAvantRestauration ?: return
            viewModelScope.launch {
                when (fichiers.writeText(etatInterne.value.uri, avant)) {
                    is AppResult.Success -> {
                        contenuAvantRestauration = null
                        etatInterne.update { it.copy(contenuRestaure = avant, message = null) }
                        recharger()
                    }

                    is AppResult.Failure -> {
                        etatInterne.update { it.copy(message = MessageHistorique.EchecEcriture) }
                    }
                }
            }
        }

        /** Le snackbar a été rendu. */
        fun consommerMessage() {
            etatInterne.update { it.copy(message = null) }
        }

        /** L'onglet de l'éditeur a été rafraîchi du contenu restauré. */
        fun consommerContenuRestaure() {
            etatInterne.update { it.copy(contenuRestaure = null) }
        }

        // ------------------------------------------------------------------
        // Internes.
        // ------------------------------------------------------------------

        /** Recharge les révisions (après restauration ou annulation). */
        private fun recharger() {
            viewModelScope.launch {
                val etat = etatInterne.value
                val maintenant = System.currentTimeMillis()
                val revisions =
                    historique.listerRevisions(etat.cheminRelatif).map { entree ->
                        RevisionUi(
                            entree = entree,
                            moment = CalculsDatesHistorique.moment(entree.horodatageMs, maintenant),
                            periode = CalculsDatesHistorique.periode(entree.horodatageMs, maintenant),
                        )
                    }
                etatInterne.update { it.copy(chargement = false, revisions = revisions) }
            }
        }

        /** Calcule le diff de la sélection (mode courant). */
        private fun calculerDiff() {
            val etat = etatInterne.value
            val selection = etat.selection ?: return
            viewModelScope.launch {
                val ancien = historique.lireContenu(selection.id)
                if (ancien == null) {
                    etatInterne.update { it.copy(diffIndisponible = true, lignesDiff = emptyList()) }
                    return@launch
                }
                val nouveau =
                    if (etat.modeActuel) {
                        when (val lecture = fichiers.readText(etat.uri)) {
                            is AppResult.Success -> {
                                lecture.value
                            }

                            is AppResult.Failure -> {
                                // Fichier disparu : la révision reste
                                // consultable contre RIEN (tout est « ajout »).
                                ""
                            }
                        }
                    } else {
                        // Révision PRÉCÉDENTE : l'entrée PLUS ANCIENNE suivante.
                        val index = etat.revisions.indexOfFirst { it.entree.id == selection.id }
                        etat.revisions.getOrNull(index + 1)?.entree?.let { ancienne ->
                            historique.lireContenu(ancienne.id)
                        } ?: ""
                    }
                val lignes = DiffUnifie.calculer(ancien.split('\n'), nouveau.split('\n'))
                etatInterne.update { it.copy(lignesDiff = lignes, diffIndisponible = false) }
            }
        }
    }
