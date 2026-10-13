@file:Suppress("MagicNumber", "TooManyFunctions", "SpreadOperator")

package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import jo.codeide.feature.editor.databinding.FragmentDetailIndexationBinding
import jo.codeide.feature.editor.databinding.LigneBilanBinding
import jo.codeide.feature.editor.databinding.LigneMoteurBinding
import jo.codeide.feature.editor.databinding.LigneTravailIndexationBinding

/**
 * Panneau de détail de l'indexation (Phase B prompt 2, ADR 0117 §4.4 —
 * lot B6). `BottomSheetDialogFragment` affiché au toucher de la pastille
 * de toolbar (lot B5).
 *
 * Le fragment observe `viewModel.sessionLsp.etatIndexation` via
 * `PresentationIndexation.afficher()` (lot B4) et rend le modèle
 * d'affichage. Le fragment ne contient AUCUNE logique — il ne fait que
 * lier le modèle aux vues (règle §5.11 du prompt 2).
 *
 * Le fragment survit à la rotation (il observe le ViewModel de l'activité
 * via `activityViewModels()`) et se ferme proprement quand la session LSP
 * se ferme (ADR 0114 — l'état `INITIAL` déclenche `dismiss()`).
 *
 * Patron : [FeuilleTachesFragment] — `BottomSheetDialogFragment` Material 3.
 */
internal class FragmentDetailIndexation : BottomSheetDialogFragment() {
    private var liaisonAmorce: FragmentDetailIndexationBinding? = null
    private val liaison get() = checkNotNull(liaisonAmorce) { "liaison détail indexation indisponible" }

    private lateinit var adaptateur: AdaptateurListeTravail

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FragmentDetailIndexationBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(
        vue: View,
        etat: Bundle?,
    ) {
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
     * (qui observe `etatIndexation`) à chaque changement d'état.
     */
    internal fun rendre(modele: ModeleAffichageIndexation) {
        if (liaisonAmorce == null) return
        rendreEntete(modele.panneau.entete)
        rendreProgression(modele.panneau.progression)
        rendreListeTravail(modele.panneau.listeTravail)
        rendreMoteurs(modele.panneau.moteurs)
        rendreBilan(modele.panneau.bilan)
        rendreNoteImpact(modele.panneau.noteImpact)
        rendreActions(modele.panneau.actions)
    }

    private fun rendreEntete(entete: EnteteDetailIndexation) {
        liaison.iconeEtatDetail.programmer(traduireEtatPastille(entete.etat))
        liaison.titreDetail.text = getString(entete.titre)
        liaison.sousTitreDetail.text = resoudreTexte(entete.sousTitre)
    }

    private fun traduireEtatPastille(etat: EtatPastilleIndexation): VuePointIndexation.Etat =
        when (etat) {
            EtatPastilleIndexation.INITIAL -> VuePointIndexation.Etat.INITIAL
            EtatPastilleIndexation.CONSTRUCTION -> VuePointIndexation.Etat.CONSTRUCTION
            EtatPastilleIndexation.PRET -> VuePointIndexation.Etat.PRET
            EtatPastilleIndexation.PARTIEL -> VuePointIndexation.Etat.PARTIEL
            EtatPastilleIndexation.ERREUR -> VuePointIndexation.Etat.ERREUR
        }

    private fun rendreProgression(progression: ProgressionDetailIndexation) {
        val barre = liaison.progressionDetail
        if (progression.indeterminee) {
            barre.isIndeterminate = true
            barre.isVisible = true
        } else {
            barre.isIndeterminate = false
            barre.setProgressCompat((progression.fraction * 100).toInt().coerceIn(0, 100), true)
            barre.isVisible = true
        }
        val compteur = liaison.compteurDetail
        if (progression.compteur != null) {
            compteur.text = resoudreTexte(progression.compteur)
            compteur.isVisible = true
        } else {
            compteur.isVisible = false
        }
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
                // TODO B6+ : icône couleur d'accent du moteur.
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

    private fun ajouterLigneBilan(
        conteneur: ViewGroup,
        labelRes: Int,
        valeur: String,
    ) {
        val ligne = LigneBilanBinding.inflate(layoutInflater, conteneur, true)
        ligne.labelBilan.text = getString(labelRes)
        ligne.valeurBilan.text = valeur
    }

    private fun rendreNoteImpact(note: NoteImpactIndexation?) {
        val card = liaison.noteImpact
        val texte = liaison.texteNoteImpact
        if (note == null) {
            card.isVisible = false
        } else {
            card.isVisible = true
            texte.text = resoudreTexte(note.texte)
            // TODO B6+ : couleur de fond selon le niveau (info/avertissement/erreur).
        }
    }

    private fun rendreActions(actions: List<ActionDetailIndexation>) {
        val conteneur = liaison.conteneurActions
        conteneur.removeAllViews()
        for (action in actions) {
            val bouton =
                MaterialButton(requireContext()).apply {
                    text = libelleAction(action)
                    cornerRadius = 22
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (44 * resources.displayMetrics.density).toInt(),
                        )
                    setOnClickListener { /* TODO B7 : brancher les actions au ViewModel */ }
                }
            conteneur.addView(bouton)
        }
    }

    private fun libelleAction(action: ActionDetailIndexation): String =
        when (action) {
            ActionDetailIndexation.REINDEXER -> getString(R.string.editor_indexation_action_reindexer)
            ActionDetailIndexation.REESSAYER_ECHECS -> getString(R.string.editor_indexation_action_reeessayer_echecs)
            ActionDetailIndexation.ANNULER -> getString(R.string.editor_indexation_action_annuler)
        }

    private fun resoudreTexte(texte: TexteIndexation): String =
        when (texte) {
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
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): LigneTravailViewHolder =
        LigneTravailViewHolder(
            LigneTravailIndexationBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
        )

    override fun onBindViewHolder(
        holder: LigneTravailViewHolder,
        position: Int,
    ) {
        holder.lier(getItem(position))
    }
}

private class LigneTravailViewHolder(
    private val liaison: LigneTravailIndexationBinding,
) : RecyclerView.ViewHolder(liaison.root) {
    fun lier(entree: EntreeDetailIndexation) {
        liaison.nomEntree.text = entree.nom
        liaison.etatEntree.text = libelleEtat(entree.etat)
        liaison.iconeEntree.setImageResource(iconeEtat(entree.etat))
    }

    private fun libelleEtat(etat: EtatEntreeDetail): String =
        when (etat) {
            EtatEntreeDetail.EN_ATTENTE -> liaison.root.context.getString(R.string.editor_indexation_entree_attente)
            EtatEntreeDetail.ACTIF -> liaison.root.context.getString(R.string.editor_indexation_entree_actif)
            EtatEntreeDetail.TERMINE -> liaison.root.context.getString(R.string.editor_indexation_entree_termine)
            EtatEntreeDetail.REPRIS -> liaison.root.context.getString(R.string.editor_indexation_entree_repris)
            EtatEntreeDetail.ECARTE -> liaison.root.context.getString(R.string.editor_indexation_entree_ecarte)
        }

    private fun iconeEtat(etat: EtatEntreeDetail): Int =
        when (etat) {
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
        override fun areItemsTheSame(
            old: EntreeDetailIndexation,
            new: EntreeDetailIndexation,
        ): Boolean = old.nom == new.nom

        override fun areContentsTheSame(
            old: EntreeDetailIndexation,
            new: EntreeDetailIndexation,
        ): Boolean = old == new
    }
