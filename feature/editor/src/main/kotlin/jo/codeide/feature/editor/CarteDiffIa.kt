package jo.codeide.feature.editor

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import jo.codeide.core.domain.DiffUnifie
import jo.codeide.core.domain.LigneDiff
import jo.codeide.core.domain.TypeLigneDiff
import jo.codeide.core.ui.PropositionDiffIa
import jo.codeide.feature.editor.databinding.LigneDiffBinding
import jo.codeide.feature.editor.databinding.VueCarteDiffIaBinding

/**
 * Carte de proposition (diff) de l'éditeur (mission IA, lot IA-5, ADR
 * 0126, spec E3) : le bloc « Appliquer » du chat y arrive, le diff
 * unifié ([DiffUnifie] + `ligne_diff.xml`, rendu identique à
 * [FeuilleHistoriqueFragment]) sert de revue — **Accepter** applique en
 * un seul lot via le port d'éditeur (un seul Annuler), refusé si la
 * révision du document a changé depuis la proposition (critère IA-5 :
 * « le texte a changé — relancer ») ; **Rejeter** referme ;
 * **Réessayer** relance la consigne d'origine via le pont.
 *
 * Vue autonome (câblage hôte mince — ADR 0126) : l'hôte ne branche que
 * trois rappels et fournit la garde de révision au clic (pas au rendu).
 */
class CarteDiffIa
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) :
    MaterialCardView(context, attrs) {
    private lateinit var binding: VueCarteDiffIaBinding
    private val adaptateur = AdaptateurLignesDiff()

    /** Proposition affichée (`null` = carte fermée). */
    var proposition: PropositionDiffIa? = null
        private set

    var surAccepter: ((PropositionDiffIa) -> Unit)? = null
    var surRejeter: (() -> Unit)? = null
    var surRessayer: (() -> Unit)? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = VueCarteDiffIaBinding.bind(this)
        binding.listeDiff.layoutManager = LinearLayoutManager(context)
        binding.listeDiff.adapter = adaptateur
        binding.boutonAccepterDiff.setOnClickListener {
            proposition?.let { proposition -> surAccepter?.invoke(proposition) }
        }
        binding.boutonRejeterDiff.setOnClickListener {
            surRejeter?.invoke()
        }
        binding.boutonRessayerDiff.setOnClickListener {
            surRessayer?.invoke()
        }
    }

    /**
     * Affiche la proposition : diff unifié entre le texte actuel de la
     * plage et le texte proposé, compteur « +n −m », hauteur plafonnée
     * à `ia_carte_diff_hauteur_max` (spec E3 — défile au-delà).
     */
    fun maj(nouvelle: PropositionDiffIa) {
        proposition = nouvelle
        val lignes = DiffUnifie.calculer(nouvelle.texteActuel.lines(), nouvelle.textePropose.lines())
        adaptateur.submitList(lignes)
        val ajouts = lignes.count { it.type == TypeLigneDiff.AJOUT }
        val retraits = lignes.count { it.type == TypeLigneDiff.RETRAIT }
        binding.compteurDiff.text =
            context.getString(R.string.ia_carte_compteur, ajouts, retraits)
        isVisible = true
        plafonnerHauteur()
    }

    /** Ferme la carte (Rejeter, application réussie, ou consigne relancée). */
    fun masquer() {
        proposition = null
        isVisible = false
    }

    /** Plafond de hauteur (spec E3) : appliqué après mise en page. */
    private fun plafonnerHauteur() {
        post {
            val plafond = resources.getDimensionPixelSize(jo.codeide.core.ui.R.dimen.ia_carte_diff_hauteur_max)
            if (height > plafond) {
                layoutParams = layoutParams.apply { height = plafond }
            }
        }
    }
}

/** Rangées du diff (réutilise `ligne_diff.xml` — même rendu que l'historique). */
private class AdaptateurLignesDiff : ListAdapter<LigneDiff, TitulaireLigneDiff>(ComparateurLignes) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TitulaireLigneDiff =
        TitulaireLigneDiff(LigneDiffBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(titulaire: TitulaireLigneDiff, position: Int) {
        titulaire.lier(getItem(position))
    }
}

/** Titulaire d'une ligne de diff (préfixe + fonds de l'historique). */
private class TitulaireLigneDiff(private val liaison: LigneDiffBinding) : RecyclerView.ViewHolder(liaison.root) {
    fun lier(ligne: LigneDiff) {
        val contexte = liaison.root.context
        when (ligne.type) {
            TypeLigneDiff.INCHANGE -> {
                liaison.texteLigneDiff.text =
                    contexte.getString(R.string.historique_diff_ligne_inchange, ligne.texte)
                liaison.texteLigneDiff.setTextColor(contexte.getColor(R.color.explorateur_texte_2))
                liaison.texteLigneDiff.setBackgroundColor(0)
            }

            TypeLigneDiff.AJOUT -> {
                liaison.texteLigneDiff.text =
                    contexte.getString(R.string.historique_diff_ligne_ajout, ligne.texte)
                liaison.texteLigneDiff.setTextColor(contexte.getColor(R.color.logcat_niveau_info))
                liaison.texteLigneDiff.setBackgroundResource(R.color.historique_diff_ajout_fond)
            }

            TypeLigneDiff.RETRAIT -> {
                liaison.texteLigneDiff.text =
                    contexte.getString(R.string.historique_diff_ligne_retrait, ligne.texte)
                liaison.texteLigneDiff.setTextColor(contexte.getColor(R.color.logcat_niveau_erreur))
                liaison.texteLigneDiff.setBackgroundResource(R.color.historique_diff_retrait_fond)
            }
        }
    }
}

/** DiffUtil sur l'égalité des lignes (rendu stable pendant la revue). */
private object ComparateurLignes : DiffUtil.ItemCallback<LigneDiff>() {
    override fun areItemsTheSame(ancienne: LigneDiff, nouvelle: LigneDiff): Boolean = ancienne == nouvelle

    override fun areContentsTheSame(ancienne: LigneDiff, nouvelle: LigneDiff): Boolean = ancienne == nouvelle
}
