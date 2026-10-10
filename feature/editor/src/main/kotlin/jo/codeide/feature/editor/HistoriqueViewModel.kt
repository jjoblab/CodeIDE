package jo.codeide.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.DiffUnifie
import jo.codeide.core.domain.EntreeHistorique
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.HistoriqueLocal
import jo.codeide.core.domain.LigneDiff
import jo.codeide.core.domain.TypeEntreeHistorique
import jo.codeide.core.domain.mimeFichierTexte
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

    /** Fichier SUPPRIMÉ recréé à son emplacement d'origine — la version
     *  supprimée reste dans l'historique (H3). */
    data object FichierRecree : MessageHistorique

    /** Contenu de la révision indisponible (binaire ou trop grand). */
    data object ContenuIndisponible : MessageHistorique

    /** L'écriture a échoué — le fichier n'a pas bougé. */
    data object EchecEcriture : MessageHistorique

    /** Impossible de recréer le fichier : son dossier parent a
     *  disparu (H3 — restaurer une pierre tombale). */
    data object ParentIntrouvable : MessageHistorique
}

/** Mode d'ouverture de la feuille Historique (mission H3). */
enum class ModeHistorique {
    /** Révisions d'un FICHIER (H2) : diff + restauration. */
    FICHIER,

    /** Révisions de tous les fichiers SOUS un dossier (H3). */
    DOSSIER,

    /** « Modifications récentes » : révisions du projet ENTIER (H3). */
    PROJET,
}

/** Une révision rendue : l'entrée brute + son moment et sa période. */
data class RevisionUi(
    val entree: EntreeHistorique,
    val moment: MomentHistorique,
    val periode: PeriodeHistorique,
)

/**
 * État observable de la feuille « Historique » (missions H2/H3, spec
 * HISTORIQUE_LOCAL.md § 5).
 *
 * @property mode mode d'ouverture (fichier / dossier / projet).
 * @property nomFichier nom affiché (sous-titre de la feuille).
 * @property cheminRelatif chemin du fichier OU dossier relatif au projet
 *           (vide : le projet entier).
 * @property uri URI de document du fichier (mode FICHIER — lecture et
 *           écriture courante).
 * @property chargement `true` tant que la première liste n'est pas là.
 * @property revisions révisions affichées (filtre appliqué), les plus
 *           récentes d'abord.
 * @property filtreSupprimes `true` : seules les pierres tombales
 *           (fichiers supprimés, retrouvables) sont listées.
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
    val mode: ModeHistorique = ModeHistorique.FICHIER,
    val nomFichier: String = "",
    val cheminRelatif: String = "",
    val uri: String = "",
    val chargement: Boolean = true,
    val revisions: List<RevisionUi> = emptyList(),
    val filtreSupprimes: Boolean = false,
    val selection: EntreeHistorique? = null,
    val modeActuel: Boolean = true,
    val lignesDiff: List<LigneDiff> = emptyList(),
    val diffIndisponible: Boolean = false,
    val message: MessageHistorique? = null,
    val contenuRestaure: String? = null,
)

/**
 * ViewModel de la feuille « Historique » (missions « Historique local »
 * H2 puis H3) : liste des révisions d'un fichier (moments relatifs,
 * périodes), d'un DOSSIER (tous les fichiers sous le préfixe) ou du
 * PROJET ENTIER (« Modifications récentes ») — filtre « Supprimés
 * seuls » (pierres tombales retrouvables), diff unifié (contre le
 * contenu ACTUEL ou la révision PRÉCÉDENTE — [DiffUnifie], partagé avec
 * la future vue Git), RESTAURATION d'une version existante et
 * RECRÉATION d'un fichier supprimé.
 *
 * La restauration est annulable PAR CONSTRUCTION : l'écriture passe par
 * le port `FileSystem` DÉCORÉ (la version d'avant part à l'historique
 * automatiquement, ADR 0106) — et le snackbar porte en plus une action
 * « Annuler » immédiate : réécriture du contenu d'avant (gardé ici),
 * ou suppression du fichier recréé.
 */
@HiltViewModel
@Suppress("TooManyFunctions") // Une fonction par intention (H2) + modes et annulations (H3) : la surface du port.
class HistoriqueViewModel
    @Inject
    constructor(
        private val historique: HistoriqueLocal,
        private val fichiers: FileSystem,
        private val resolveur: ResolveurCheminHistorique,
    ) : ViewModel() {
        private val etatInterne = MutableStateFlow(EtatHistorique())

        val etat: StateFlow<EtatHistorique> = etatInterne.asStateFlow()

        /** Annulation possible de la dernière restauration (snackbar). */
        private sealed interface AnnulationRestauration {
            /** Réécrire le contenu d'avant (fichier existant). */
            data class Reecriture(
                val uri: String,
                val contenuAvant: String?,
            ) : AnnulationRestauration

            /** Supprimer le fichier RECRÉÉ (restauration d'une tombale). */
            data class Suppression(
                val uri: String,
            ) : AnnulationRestauration
        }

        /** Annulation en attente (action « Annuler » du snackbar). */
        private var annulation: AnnulationRestauration? = null

        /** Révisions non filtrées du mode courant (source du filtre). */
        private var revisionsToutes: List<RevisionUi> = emptyList()

        /** Charge les révisions (appelé à l'ouverture de la feuille). */
        fun charger(
            uri: String,
            cheminRelatif: String,
            nomFichier: String,
            mode: ModeHistorique = ModeHistorique.FICHIER,
        ) {
            if (etatInterne.value.chargement && etatInterne.value.revisions.isEmpty()) {
                etatInterne.value =
                    EtatHistorique(
                        mode = mode,
                        uri = uri,
                        cheminRelatif = cheminRelatif,
                        nomFichier = nomFichier,
                    )
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

        /** Bascule le filtre de la liste : tout ↔ pierres tombales seules. */
        fun definirFiltreSupprimes(supprimesSeuls: Boolean) {
            if (etatInterne.value.filtreSupprimes == supprimesSeuls) return
            etatInterne.update { it.copy(filtreSupprimes = supprimesSeuls, revisions = filtrer(supprimesSeuls)) }
        }

        /**
         * Restaure la révision sélectionnée : écrit le contenu stocké via
         * le port DÉCORÉ (l'état AVANT part automatiquement à
         * l'historique). Un fichier SUPPRIMÉ est RECRÉÉ dans son dossier
         * d'origine (résolu segment par segment — jamais d'URI inventée).
         * [EtatHistorique.contenuRestaure] porte le texte écrit pour que
         * l'onglet ouvert de l'éditeur se rafraîchisse — sans quoi
         * l'auto-sauvegarde écraserait la version restaurée (leçon H2).
         */
        fun restaurer(entree: EntreeHistorique) {
            viewModelScope.launch {
                val contenu = historique.lireContenu(entree.id)
                if (contenu == null) {
                    etatInterne.update { it.copy(message = MessageHistorique.ContenuIndisponible) }
                    return@launch
                }
                when (val cible = cibleEcriture(entree)) {
                    is CibleEcriture.Existante -> {
                        restaurerExistante(cible.uri, contenu)
                    }

                    is CibleEcriture.ARecreer -> {
                        recreerFichier(cible, contenu)
                    }

                    CibleEcriture.Introuvable -> {
                        etatInterne.update { it.copy(message = MessageHistorique.ParentIntrouvable) }
                    }
                }
            }
        }

        /** Action « Annuler » du snackbar : réécrit le contenu d'avant,
         *  ou resupprime le fichier recréé (l'onglet ouvert se rafraîchit
         *  par le même chemin). */
        fun annulerRestauration() {
            val enAttente = annulation ?: return
            viewModelScope.launch {
                when (enAttente) {
                    is AnnulationRestauration.Reecriture -> {
                        val contenu = enAttente.contenuAvant ?: return@launch
                        if (fichiers.writeText(enAttente.uri, contenu) is AppResult.Success) {
                            annulation = null
                            etatInterne.update { it.copy(contenuRestaure = contenu, message = null) }
                            recharger()
                        } else {
                            etatInterne.update { it.copy(message = MessageHistorique.EchecEcriture) }
                        }
                    }

                    is AnnulationRestauration.Suppression -> {
                        if (fichiers.delete(enAttente.uri) is AppResult.Success) {
                            annulation = null
                            etatInterne.update { it.copy(message = null) }
                            recharger()
                        } else {
                            etatInterne.update { it.copy(message = MessageHistorique.EchecEcriture) }
                        }
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

        /** Cible d'une restauration : URI existante, dossier parent
         *  d'une recréation, ou impasse honnête. */
        private sealed interface CibleEcriture {
            /** Le fichier existe : écrire dedans (H2). */
            data class Existante(
                val uri: String,
            ) : CibleEcriture

            /** Le fichier n'existe plus : le recréer dans ce parent (H3). */
            data class ARecreer(
                val uriParent: String,
                val nom: String,
            ) : CibleEcriture

            /** Ni l'un ni l'autre : le dossier parent a disparu. */
            data object Introuvable : CibleEcriture
        }

        /** Résout la cible d'écriture de l'entrée (mode courant). */
        private suspend fun cibleEcriture(entree: EntreeHistorique): CibleEcriture {
            val uriExistante = uriExistanteDe(entree)
            if (uriExistante != null) return CibleEcriture.Existante(uriExistante)

            // Pierre tombale : recréer dans le parent RÉSOLU (jamais
            // d'URI construite — leçon v0.64.0).
            val uriParent = resolveur.resoudre(entree.cheminRelatif.substringBeforeLast('/', ""))
            return when (uriParent) {
                null -> CibleEcriture.Introuvable
                else -> CibleEcriture.ARecreer(uriParent, entree.cheminRelatif.substringAfterLast('/'))
            }
        }

        /** URI du fichier s'il existe ENCORE : l'URI de la feuille
         *  (mode fichier) ou la résolution par énumération, sinon `null`. */
        private suspend fun uriExistanteDe(entree: EntreeHistorique): String? =
            when (etatInterne.value.mode) {
                ModeHistorique.FICHIER -> {
                    etatInterne.value.uri.takeIf { fichiers.exists(it) }
                        ?: resolveur.resoudre(entree.cheminRelatif)
                }

                ModeHistorique.DOSSIER, ModeHistorique.PROJET -> {
                    resolveur.resoudre(entree.cheminRelatif)
                }
            }

        /** Restaure par écrasement d'un fichier existant (H2). */
        private suspend fun restaurerExistante(
            uri: String,
            contenu: String,
        ) {
            val avant = fichiers.readText(uri).let { if (it is AppResult.Success) it.value else null }
            if (fichiers.writeText(uri, contenu) is AppResult.Success) {
                annulation = AnnulationRestauration.Reecriture(uri, avant)
                etatInterne.update {
                    it.copy(message = MessageHistorique.Restauree, contenuRestaure = contenu)
                }
                recharger()
            } else {
                etatInterne.update { it.copy(message = MessageHistorique.EchecEcriture) }
            }
        }

        /** Recrée un fichier supprimé dans son dossier d'origine (H3) :
         *  la création passe par le port DÉCORÉ (entrée CREATION), puis
         *  l'écriture (annulable par construction). */
        private suspend fun recreerFichier(
            cible: CibleEcriture.ARecreer,
            contenu: String,
        ) {
            when (
                val creation =
                    fichiers.createFile(cible.uriParent, cible.nom, mimeFichierTexte(cible.nom))
            ) {
                is AppResult.Success -> {
                    val uri = creation.value
                    if (fichiers.writeText(uri, contenu) is AppResult.Success) {
                        annulation = AnnulationRestauration.Suppression(uri)
                        etatInterne.update {
                            it.copy(message = MessageHistorique.FichierRecree, contenuRestaure = contenu)
                        }
                        recharger()
                    } else {
                        etatInterne.update { it.copy(message = MessageHistorique.EchecEcriture) }
                    }
                }

                is AppResult.Failure -> {
                    etatInterne.update { it.copy(message = MessageHistorique.EchecEcriture) }
                }
            }
        }

        /** Recharge les révisions (après restauration ou annulation). */
        private fun recharger() {
            viewModelScope.launch {
                val etat = etatInterne.value
                val maintenant = System.currentTimeMillis()
                val brutes =
                    when (etat.mode) {
                        ModeHistorique.FICHIER -> {
                            historique.listerRevisions(etat.cheminRelatif)
                        }

                        ModeHistorique.DOSSIER, ModeHistorique.PROJET -> {
                            historique.listerRevisionsSous(
                                etat.cheminRelatif,
                                HistoriqueLocal.LIMITE_REVISIONS_SOUS_DOSSIER,
                            )
                        }
                    }
                revisionsToutes =
                    brutes.map { entree ->
                        RevisionUi(
                            entree = entree,
                            moment = CalculsDatesHistorique.moment(entree.horodatageMs, maintenant),
                            periode = CalculsDatesHistorique.periode(entree.horodatageMs, maintenant),
                        )
                    }
                etatInterne.update {
                    it.copy(chargement = false, revisions = filtrer(it.filtreSupprimes))
                }
            }
        }

        /** Filtre courant des révisions chargées. */
        private fun filtrer(supprimesSeuls: Boolean): List<RevisionUi> =
            if (supprimesSeuls) {
                revisionsToutes.filter { it.entree.type == TypeEntreeHistorique.SUPPRESSION }
            } else {
                revisionsToutes
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
                        val uri =
                            when (etat.mode) {
                                ModeHistorique.FICHIER -> {
                                    etat.uri
                                }

                                ModeHistorique.DOSSIER, ModeHistorique.PROJET -> {
                                    resolveur.resoudre(selection.cheminRelatif)
                                }
                            }
                        when (val lecture = uri?.let { fichiers.readText(it) }) {
                            is AppResult.Success -> lecture.value

                            // Fichier disparu (ou hors projet) : la
                            // révision reste consultable contre RIEN.
                            else -> ""
                        }
                    } else {
                        // Révision PRÉCÉDENTE : l'entrée PLUS ANCIENNE du
                        // MÊME chemin (les révisions d'un dossier
                        // mélangent les fichiers — H3).
                        val duMemeFichier = historique.listerRevisions(selection.cheminRelatif)
                        val index = duMemeFichier.indexOfFirst { it.id == selection.id }
                        duMemeFichier.getOrNull(index + 1)?.let { ancienne -> historique.lireContenu(ancienne.id) }
                            ?: ""
                    }
                val lignes = DiffUnifie.calculer(ancien.split('\n'), nouveau.split('\n'))
                etatInterne.update { it.copy(lignesDiff = lignes, diffIndisponible = false) }
            }
        }
    }
