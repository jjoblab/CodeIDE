package jo.codeide.feature.newproject

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.feature.newproject.databinding.VueLigneArborescenceBinding

/**
 * Adaptateur de l'arborescence prévue (section 12.3) : lignes aplaties du
 * plan, dossiers repliables par un toucher (contenu décrit pour TalkBack),
 * crayon de renommage par ligne (ADR 0077) — partagé par le récapitulatif
 * et l'aperçu en direct (L5d), le crayon n'apparaissant que si un
 * écouteur de renommage est fourni.
 */
internal class ArborescenceAdapter(private val onRenommer: ((Noeud) -> Unit)? = null) :
    ListAdapter<ArborescenceAdapter.LigneArbre, ArborescenceAdapter.Support>(ComparaisonLignes()) {
    /** Dossiers repliés (chemin final affiché). */
    private val replies = mutableSetOf<String>()

    /** Dernier arbre soumis — l'aplatissement redérive de l'état replié. */
    private var dernierArbre: Arborescence? = null

    /** Une ligne affichable : profondeur, nœud, chemin final, identité originale. */
    data class LigneArbre(val profondeur: Int, val noeud: Noeud, val chemin: String, val cheminOriginal: String)

    /** Soumet un arbre : aplatit selon l'état replié courant. */
    fun soumettre(arbre: Arborescence) {
        dernierArbre = arbre
        rafraichir()
    }

    private fun rafraichir() {
        val arbre = dernierArbre ?: return
        val lignes = mutableListOf<LigneArbre>()
        aplatir(arbre.racine, 0, "", lignes, estRacine = true)
        submitList(lignes)
    }

    /** Parcours préfixe : la racine est masquée, un replié cache ses enfants. */
    private fun aplatir(
        dossier: Noeud.Dossier,
        profondeur: Int,
        prefixe: String,
        sortie: MutableList<LigneArbre>,
        estRacine: Boolean,
    ) {
        dossier.enfantsTries.forEach { noeud ->
            val chemin = if (prefixe.isEmpty()) noeud.nom else "$prefixe/${noeud.nom}"
            if (!estRacine) sortie += LigneArbre(profondeur, noeud, chemin, noeud.cheminOriginal)
            if (noeud is Noeud.Dossier && chemin !in replies) {
                aplatir(noeud, if (estRacine) 0 else profondeur + 1, chemin, sortie, estRacine = false)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Support {
        val liaison =
            VueLigneArborescenceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Support(liaison)
    }

    override fun onBindViewHolder(support: Support, position: Int) {
        val ligne = getItem(position)
        val densite = support.liaison.root.resources.displayMetrics.density
        support.liaison.espaceIndentation.layoutParams.width =
            (INDENTATION_DP * densite).toInt() * ligne.profondeur.coerceAtMost(PROFONDEUR_MAX)
        support.liaison.espaceIndentation.requestLayout()

        val dossier = ligne.noeud as? Noeud.Dossier
        support.liaison.iconeDossier.isVisible = dossier != null
        support.liaison.texteNoeud.text = ligne.noeud.nom

        // Renommage : interdit sur les métadonnées du projet (ADR 0077),
        // offert seulement quand un écouteur est fourni (aperçu en lecture).
        val renommable = onRenommer != null && !ligne.cheminOriginal.estProtege()
        support.liaison.boutonRenommer.isVisible = renommable
        if (renommable) {
            support.liaison.boutonRenommer.contentDescription =
                support.liaison.root.context
                    .getString(R.string.wizard_arbre_renommer, ligne.noeud.nom)
            support.liaison.boutonRenommer.setOnClickListener { onRenommer?.invoke(ligne.noeud) }
        }

        if (dossier == null) {
            support.liaison.root.isClickable = false
            support.liaison.root.contentDescription = null
        } else {
            support.liaison.root.isClickable = true
            support.liaison.root.setOnClickListener {
                if (ligne.chemin in replies) replies.remove(ligne.chemin) else replies.add(ligne.chemin)
                rafraichir()
            }
            val replie = ligne.chemin in replies
            support.liaison.root.contentDescription =
                support.liaison.root.context.getString(
                    if (replie) R.string.wizard_arbre_deplier else R.string.wizard_arbre_replier,
                    ligne.noeud.nom,
                )
        }
    }

    /** Les métadonnées `.codeide/` ne se renomment jamais. */
    private fun String.estProtege(): Boolean = this == DOSSIER_METADATA || startsWith("$DOSSIER_METADATA/")

    /** Support de vue d'une ligne. */
    class Support(val liaison: VueLigneArborescenceBinding) : RecyclerView.ViewHolder(liaison.root)

    /** Diff par identité (chemin original) puis par contenu (nœud et chemin). */
    private class ComparaisonLignes : DiffUtil.ItemCallback<LigneArbre>() {
        override fun areItemsTheSame(ancienne: LigneArbre, nouvelle: LigneArbre): Boolean =
            ancienne.cheminOriginal == nouvelle.cheminOriginal

        override fun areContentsTheSame(ancienne: LigneArbre, nouvelle: LigneArbre): Boolean = ancienne == nouvelle
    }

    private companion object {
        /** Indentation par niveau de profondeur (dp → px au rendu). */
        const val INDENTATION_DP = 16

        /** Profondeur maximale affichée avant saturation (lisibilité). */
        const val PROFONDEUR_MAX = 6

        /** Dossier des métadonnées du projet — jamais renommable (ADR 0077). */
        const val DOSSIER_METADATA = ".codeide"
    }
}
