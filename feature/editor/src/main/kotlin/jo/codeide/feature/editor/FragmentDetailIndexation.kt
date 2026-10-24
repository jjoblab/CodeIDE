@file:Suppress("MagicNumber", "TooManyFunctions", "SpreadOperator")

package jo.codeide.feature.editor

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import jo.codeide.core.ui.couleurRole
import jo.codeide.core.ui.couleurSurSurfaceDiscret
import jo.codeide.feature.editor.databinding.FragmentDetailIndexationBinding
import jo.codeide.feature.editor.databinding.LigneBilanBinding
import jo.codeide.feature.editor.databinding.LigneCoutBinding
import jo.codeide.feature.editor.databinding.LigneEcarteeBinding
import jo.codeide.feature.editor.databinding.LigneMoteurBinding
import jo.codeide.feature.editor.databinding.LigneTravailIndexationBinding
import jo.codeide.core.ui.R as RUi

/**
 * Panneau de détail de l'indexation (Phase B prompt 2, ADR 0117 §4.4 —
 * lot B6 ; v0.178.0, ADR 0145 : MULTI-MOTEURS). `BottomSheetDialogFragment`
 * affiché au toucher de la pastille de toolbar (lot B5).
 *
 * Le fragment rend [ModeleAffichageIndexation] (produit par
 * [PresentationIndexation.afficher] depuis l'agrégat Java + Kotlin + XML
 * de `EditorViewModel.etatsMoteursIndexation`) — TOUTES les sections de
 * la maquette `docs/mockups/indexation/` : en-tête avec pourcentage,
 * barre de progression (construction seule), résumé des reprises, vue
 * par moteur (accent de langage ADR 0059), liste de travail, entrées
 * écartées, erreurs bloquantes, bilan du dernier passage, histogramme
 * des coûts, notes d'impact par niveau. Le fragment ne contient AUCUNE
 * logique — il ne fait que lier le modèle aux vues (règle §5.11).
 *
 * Les boutons d'action (lot B7, v0.178.0 : enfin branchés) appellent
 * [EditorViewModel.reindexerLsp] : « Réindexer » et « Réessayer les
 * entrées échouées » mappent tous deux sur la revalidation +
 * auto-guérison tant que le dépôt frère n'expose pas de réessai ciblé
 * (docs/DEMANDES_CODEIDE_LSP.md D4).
 *
 * Le fragment survit à la rotation (il observe via `activityViewModels()`
 * — l'ACTIVITÉ collecte l'agrégat et rappelle [rendre]).
 */
internal class FragmentDetailIndexation : BottomSheetDialogFragment() {
    private var liaisonAmorce: FragmentDetailIndexationBinding? = null
    private val liaison get() = checkNotNull(liaisonAmorce) { "liaison détail indexation indisponible" }

    private val viewModel: EditorViewModel by activityViewModels()

    private lateinit var adaptateur: AdaptateurListeTravail

    override fun onCreateView(inflateur: LayoutInflater, conteneur: ViewGroup?, etat: Bundle?): View {
        liaisonAmorce = FragmentDetailIndexationBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(vue: View, etat: Bundle?) {
        super.onViewCreated(vue, etat)
        adaptateur = AdaptateurListeTravail()
        liaison.listeTravailDetail.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeTravailDetail.adapter = adaptateur
    }

    override fun onDestroyView() {
        super.onDestroyView()
        liaisonAmorce = null
    }

    /**
     * Rend le modèle d'affichage dans les vues. Appelé par l'activité
     * (qui observe l'agrégat multi-moteurs) à chaque changement d'état.
     */
    internal fun rendre(modele: ModeleAffichageIndexation) {
        if (liaisonAmorce == null) return
        rendreEntete(modele.panneau.entete)
        rendreProgression(modele.panneau.progression)
        rendreResumeReprises(modele.panneau.resumeReprises)
        rendreListeTravail(modele.panneau.listeTravail)
        rendreMoteurs(modele.panneau.moteurs)
        rendreEntreesEcartees(modele.panneau.entreesEcartees)
        rendreErreursBloquantes(modele.panneau.erreursBloquantes)
        rendreBilan(modele.panneau.bilan)
        rendreCouts(modele.panneau.couts)
        rendreNoteImpact(modele.panneau.noteImpact)
        rendreActions(modele.panneau.actions)
    }

    private fun rendreEntete(entete: EnteteDetailIndexation) {
        liaison.iconeEtatDetail.programmer(traduireEtatPastille(entete.etat))
        liaison.titreDetail.text = getString(entete.titre)
        liaison.sousTitreDetail.text = resoudreTexte(entete.sousTitre)
        val pourcentage = entete.pourcentageDroite
        if (pourcentage != null) {
            liaison.pourcentageDroite.text = getString(R.string.editor_indexation_pourcentage, pourcentage)
            liaison.pourcentageDroite.isVisible = true
        } else {
            liaison.pourcentageDroite.isVisible = false
        }
    }

    private fun traduireEtatPastille(etat: EtatPastilleIndexation): VuePointIndexation.Etat = when (etat) {
        EtatPastilleIndexation.INITIAL -> VuePointIndexation.Etat.INITIAL
        EtatPastilleIndexation.CONSTRUCTION -> VuePointIndexation.Etat.CONSTRUCTION
        EtatPastilleIndexation.PRET -> VuePointIndexation.Etat.PRET
        EtatPastilleIndexation.PARTIEL -> VuePointIndexation.Etat.PARTIEL
        EtatPastilleIndexation.ERREUR -> VuePointIndexation.Etat.ERREUR
    }

    private fun rendreProgression(progression: ProgressionDetailIndexation) {
        val barre = liaison.progressionDetail
        barre.isVisible = progression.visible
        if (progression.indeterminee) {
            barre.isIndeterminate = true
        } else {
            barre.isIndeterminate = false
            barre.setProgressCompat((progression.fraction * 100).toInt().coerceIn(0, 100), true)
        }
        val compteur = progression.compteur
        liaison.compteurDetail.isVisible = compteur != null
        compteur?.let { liaison.compteurDetail.text = resoudreTexte(it) }
    }

    private fun rendreResumeReprises(resume: TexteIndexation?) {
        liaison.resumeReprises.isVisible = resume != null
        resume?.let { liaison.resumeReprises.text = resoudreTexte(it) }
    }

    private fun rendreListeTravail(entrees: List<EntreeDetailIndexation>?) {
        val label = liaison.labelListeTravail
        val liste = liaison.listeTravailDetail
        if (entrees.isNullOrEmpty()) {
            label.isVisible = false
            liste.isVisible = false
        } else {
            label.isVisible = true
            liste.isVisible = true
            adaptateur.submitList(entrees)
        }
    }

    private fun rendreMoteurs(moteurs: List<MoteurDetailIndexation>?) {
        val label = liaison.labelMoteurs
        val conteneur = liaison.conteneurMoteurs
        if (moteurs.isNullOrEmpty()) {
            label.isVisible = false
            conteneur.isVisible = false
        } else {
            label.isVisible = true
            conteneur.isVisible = true
            conteneur.removeAllViews()
            for (moteur in moteurs) {
                val ligne = LigneMoteurBinding.inflate(layoutInflater, conteneur, true)
                ligne.nomMoteur.text = getString(moteur.nom)
                ligne.etatMoteur.text = moteur.detail?.let(::resoudreTexte) ?: ""
                ligne.etatMoteur.setTextColor(couleurEtatMoteur(moteur.etat))
                ligne.pointMoteur.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), moteur.couleurAccent),
                )
            }
        }
    }

    /** Couleur du texte d'état d'une rangée moteur (tokens de statut). */
    private fun couleurEtatMoteur(etat: EtatMoteurDetail): Int = when (etat) {
        EtatMoteurDetail.INITIAL -> requireContext().couleurSurSurfaceDiscret()
        EtatMoteurDetail.CONSTRUCTION -> requireContext().couleurRole(androidx.appcompat.R.attr.colorPrimary)
        EtatMoteurDetail.PRET -> ContextCompat.getColor(requireContext(), RUi.color.codeide_succes)
        EtatMoteurDetail.PARTIEL -> ContextCompat.getColor(requireContext(), RUi.color.codeide_journal_warn)
        EtatMoteurDetail.ERREUR -> ContextCompat.getColor(requireContext(), RUi.color.codeide_error)
    }

    private fun rendreEntreesEcartees(entrees: List<EntreeEcarteeDetail>) {
        rendreSectionMessages(
            label = liaison.labelEntreesEcartees,
            conteneur = liaison.conteneurEntreesEcartees,
            entrees = entrees,
        )
    }

    private fun rendreErreursBloquantes(entrees: List<EntreeEcarteeDetail>) {
        rendreSectionMessages(
            label = liaison.labelErreursBloquantes,
            conteneur = liaison.conteneurErreursBloquantes,
            entrees = entrees,
        )
    }

    private fun rendreSectionMessages(
        label: android.widget.TextView,
        conteneur: ViewGroup,
        entrees: List<EntreeEcarteeDetail>,
    ) {
        if (entrees.isEmpty()) {
            label.isVisible = false
            conteneur.isVisible = false
        } else {
            label.isVisible = true
            conteneur.isVisible = true
            conteneur.removeAllViews()
            for (entree in entrees) {
                LigneEcarteeBinding.inflate(layoutInflater, conteneur, true)
                    .texteEntreeEcartee.text = entree.texte
            }
        }
    }

    private fun rendreBilan(bilan: BilanDetailIndexation?) {
        val label = liaison.labelBilan
        val conteneur = liaison.conteneurBilan
        if (bilan == null) {
            label.isVisible = false
            conteneur.isVisible = false
        } else {
            label.isVisible = true
            conteneur.isVisible = true
            conteneur.removeAllViews()
            ajouterLigneBilan(conteneur, R.string.editor_indexation_bilan_duree, formatDuree(bilan.dureeMs))
            ajouterLigneBilan(
                conteneur,
                R.string.editor_indexation_bilan_bibliotheques,
                getString(
                    R.string.editor_indexation_bilan_arteffacts_repris,
                    bilan.artefactsTotal,
                    bilan.artefactsRepris,
                    bilan.artefactsReconstruits,
                ),
            )
            ajouterLigneBilan(
                conteneur,
                R.string.editor_indexation_bilan_fichiers,
                getString(
                    R.string.editor_indexation_bilan_fichiers_reanalyses,
                    bilan.fichiersReanalyses,
                    bilan.fichiersParcourus,
                ),
            )
        }
    }

    private fun ajouterLigneBilan(conteneur: ViewGroup, labelRes: Int, valeur: String) {
        val ligne = LigneBilanBinding.inflate(layoutInflater, conteneur, true)
        ligne.labelBilan.text = getString(labelRes)
        ligne.valeurBilan.text = valeur
    }

    /**
     * Histogramme « ce qui a coûté le plus de temps » (maquette §4) :
     * une rangée par indexeur, barre de largeur relative au plus coûteux.
     */
    private fun rendreCouts(couts: List<CoutDetailIndexation>) {
        val label = liaison.labelCouts
        val conteneur = liaison.conteneurCouts
        if (couts.isEmpty()) {
            label.isVisible = false
            conteneur.isVisible = false
        } else {
            label.isVisible = true
            conteneur.isVisible = true
            conteneur.removeAllViews()
            for (cout in couts) {
                val ligne = LigneCoutBinding.inflate(layoutInflater, conteneur, true)
                ligne.libelleCout.text = cout.libelle
                ligne.dureeCout.text = formatDuree(cout.millisecondes)
                ligne.barreCout.isIndeterminate = false
                ligne.barreCout.setProgress((cout.fraction * 100).toInt().coerceIn(1, 100))
            }
        }
    }

    private fun rendreNoteImpact(note: NoteImpactIndexation?) {
        val card = liaison.noteImpact
        val texte = liaison.texteNoteImpact
        if (note == null) {
            card.isVisible = false
        } else {
            card.isVisible = true
            texte.text = resoudreTexte(note.texte)
            val (fond, texteCouleur) = couleursNote(note.niveau)
            card.setCardBackgroundColor(fond)
            texte.setTextColor(texteCouleur)
        }
    }

    /** Fond + couleur de texte de la note par niveau (tokens de statut). */
    private fun couleursNote(niveau: NiveauNoteImpact): Pair<Int, Int> = when (niveau) {
        NiveauNoteImpact.INFO ->
            Pair(
                requireContext().couleurRole(com.google.android.material.R.attr.colorSurfaceVariant),
                requireContext().couleurRole(com.google.android.material.R.attr.colorOnSurfaceVariant),
            )

        NiveauNoteImpact.AVERTISSEMENT ->
            Pair(
                ContextCompat.getColor(requireContext(), RUi.color.codeide_avertissement_container),
                ContextCompat.getColor(requireContext(), RUi.color.codeide_journal_warn),
            )

        NiveauNoteImpact.ERREUR ->
            Pair(
                ContextCompat.getColor(requireContext(), RUi.color.codeide_error),
                ContextCompat.getColor(requireContext(), RUi.color.codeide_on_error),
            )
    }

    /**
     * Boutons d'action — v0.178.0 (ADR 0145) : branchés au ViewModel
     * (fin du TODO B7). Le PREMIER bouton de la liste est plein (action
     * recommandée — maquette : « Réessayer les 3 entrées » en partiel),
     * les suivants sont cernés ; les STYLES vivent dans les gabarits XML
     * (bouton_action_indexation_primaire/secondaire — jamais de style
     * par code). [ActionDetailIndexation.ANNULER] n'est PLUS émis par
     * le présentateur (aucun point d'annulation — docs/DEMANDES-
     * _CODEIDE_LSP.md D5) ; la branche reste défensive.
     */
    private fun rendreActions(actions: List<ActionDetailIndexation>) {
        val conteneur = liaison.conteneurActions
        conteneur.removeAllViews()
        var premierEmis = false
        for (action in actions) {
            if (action == ActionDetailIndexation.ANNULER) continue
            val gabarit =
                if (!premierEmis) {
                    R.layout.bouton_action_indexation_primaire
                } else {
                    R.layout.bouton_action_indexation_secondaire
                }
            premierEmis = true
            val bouton =
                layoutInflater.inflate(gabarit, conteneur, true) as MaterialButton
            bouton.text = libelleAction(action)
            bouton.setOnClickListener { viewModel.reindexerLsp() }
        }
    }

    private fun libelleAction(action: ActionDetailIndexation): String = when (action) {
        ActionDetailIndexation.REINDEXER -> getString(R.string.editor_indexation_action_reindexer)
        ActionDetailIndexation.REESSAYER_ECHECS -> getString(R.string.editor_indexation_action_reeessayer_echecs)
        ActionDetailIndexation.ANNULER -> getString(R.string.editor_indexation_action_annuler)
    }

    private fun resoudreTexte(texte: TexteIndexation): String = when (texte) {
        is TexteIndexation.Ressource -> {
            if (texte.args.isEmpty()) {
                getString(texte.id)
            } else {
                getString(texte.id, *texte.args.toTypedArray())
            }
        }

        is TexteIndexation.Brut -> {
            texte.texte
        }
    }

    private fun formatDuree(ms: Long): String {
        if (ms < 1_000) return "$ms ms"
        val secondes = ms / 1_000.0
        return String.format(java.util.Locale.ROOT, "%.1f s", secondes)
    }

    /** Companion object : tag du fragment pour FragmentManager. */
    public companion object {
        public const val TAG: String = "FragmentDetailIndexation"
    }
}

/** Adaptateur de la liste de travail (RecyclerView avec DiffUtil). */
private class AdaptateurListeTravail : ListAdapter<EntreeDetailIndexation, LigneTravailViewHolder>(DIFF_ENTREE) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LigneTravailViewHolder = LigneTravailViewHolder(
        LigneTravailIndexationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        ),
    )

    override fun onBindViewHolder(holder: LigneTravailViewHolder, position: Int) {
        holder.lier(getItem(position))
    }
}

private class LigneTravailViewHolder(private val liaison: LigneTravailIndexationBinding) :
    RecyclerView.ViewHolder(liaison.root) {
    fun lier(entree: EntreeDetailIndexation) {
        liaison.nomEntree.text = entree.nom
        liaison.etatEntree.text = libelleEtat(entree.etat)
        liaison.iconeEntree.setImageResource(iconeEtat(entree.etat))
    }

    private fun libelleEtat(etat: EtatEntreeDetail): String = when (etat) {
        EtatEntreeDetail.EN_ATTENTE -> liaison.root.context.getString(R.string.editor_indexation_entree_attente)
        EtatEntreeDetail.ACTIF -> liaison.root.context.getString(R.string.editor_indexation_entree_actif)
        EtatEntreeDetail.TERMINE -> liaison.root.context.getString(R.string.editor_indexation_entree_termine)
        EtatEntreeDetail.REPRIS -> liaison.root.context.getString(R.string.editor_indexation_entree_repris)
        EtatEntreeDetail.ECARTE -> liaison.root.context.getString(R.string.editor_indexation_entree_ecarte)
    }

    private fun iconeEtat(etat: EtatEntreeDetail): Int = when (etat) {
        EtatEntreeDetail.EN_ATTENTE -> android.R.drawable.checkbox_off_background
        EtatEntreeDetail.ACTIF -> android.R.drawable.ic_menu_rotate
        EtatEntreeDetail.TERMINE -> android.R.drawable.checkbox_on_background
        EtatEntreeDetail.REPRIS -> android.R.drawable.checkbox_on_background
        EtatEntreeDetail.ECARTE -> android.R.drawable.ic_menu_close_clear_cancel
    }
}

/** DiffUtil pour la liste de travail. */
private val DIFF_ENTREE =
    object : DiffUtil.ItemCallback<EntreeDetailIndexation>() {
        override fun areItemsTheSame(old: EntreeDetailIndexation, new: EntreeDetailIndexation): Boolean =
            old.nom == new.nom

        override fun areContentsTheSame(old: EntreeDetailIndexation, new: EntreeDetailIndexation): Boolean = old == new
    }
