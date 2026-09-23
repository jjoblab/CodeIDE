package jo.codeide.feature.editor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.core.ui.IconesFichiers
import jo.codeide.feature.editor.databinding.LigneNoeudArborescenceBinding

/**
 * Adaptateur de l'arborescence paresseuse du tiroir (étape 14) : liste
 * aplatie des nœuds visibles, indentation par profondeur, chevron des
 * dossiers (tourné une fois déplié), icône par extension
 * ([IconesFichiers], prompt compagnon 5.3).
 *
 * Seuls les dossiers sont actionnables à cette étape : un appui déplie ou
 * replie. Un fichier ne s'ouvre pas encore (onglets à l'étape 15) — sa
 * ligne n'offre donc volontairement aucun retour d'appui.
 *
 * Accessibilité : chaque ligne porte un `contentDescription` complet
 * (nom, type, profondeur, état de pli ou d'échec), les cibles font
 * 48 dp minimum (prompt compagnon 5.3).
 */
internal class ExplorateurAdapter(
    private val surBasculer: (NoeudExplorateur) -> Unit,
) : ListAdapter<NoeudExplorateur, ExplorateurAdapter.VueNoeud>(Differences) {
    /** Retrait d'indentation par niveau de profondeur, en pixels. */
    private var retraitPx: Int = 0

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): VueNoeud {
        val liaison =
            LigneNoeudArborescenceBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        if (retraitPx == 0) {
            retraitPx = liaison.root.resources.getDimensionPixelSize(R.dimen.editor_explorateur_retrait)
        }
        return VueNoeud(liaison)
    }

    override fun onBindViewHolder(
        holder: VueNoeud,
        position: Int,
    ) {
        val noeud = getItem(position)
        val liaison = holder.liaison

        liaison.retraitNoeud.layoutParams.width = noeud.profondeur * retraitPx
        liaison.chevronNoeud.isVisible = noeud.estDossier
        liaison.chevronNoeud.rotation = if (noeud.deplie) ROTATION_DEPLIE else ROTATION_REPLIE
        liaison.chargementNoeud.isVisible = noeud.chargementEnfants
        liaison.erreurNoeud.isVisible = noeud.erreurChargement
        liaison.iconeNoeud.setImageResource(
            if (noeud.estDossier) IconesFichiers.pourDossier() else IconesFichiers.pourNom(noeud.nom),
        )
        liaison.nomNoeud.text = noeud.nom
        liaison.racineLigne.isClickable = noeud.estDossier
        liaison.racineLigne.contentDescription = decrire(liaison, noeud)
        liaison.racineLigne.setOnClickListener {
            if (noeud.estDossier) surBasculer(noeud)
        }
    }

    /** Description accessible complète de la ligne (nom + type + état). */
    private fun decrire(
        liaison: LigneNoeudArborescenceBinding,
        noeud: NoeudExplorateur,
    ): String {
        val contexte = liaison.root.context
        return when {
            noeud.erreurChargement -> {
                contexte.getString(R.string.editor_noeud_erreur_cd, noeud.nom)
            }

            noeud.estDossier -> {
                contexte.getString(
                    R.string.editor_noeud_dossier_cd,
                    noeud.nom,
                    noeud.profondeur,
                    contexte.getString(
                        if (noeud.deplie) R.string.editor_noeud_deplie else R.string.editor_noeud_replie,
                    ),
                )
            }

            else -> {
                contexte.getString(R.string.editor_noeud_fichier_cd, noeud.nom, noeud.profondeur)
            }
        }
    }

    /** Vue d'une ligne : la liaison seule, aucune logique. */
    internal class VueNoeud(
        val liaison: LigneNoeudArborescenceBinding,
    ) : RecyclerView.ViewHolder(liaison.root)

    private companion object {
        /** Rotation du chevron : 90° une fois le dossier déplié. */
        const val ROTATION_DEPLIE = 90f
        const val ROTATION_REPLIE = 0f
    }

    private object Differences : DiffUtil.ItemCallback<NoeudExplorateur>() {
        override fun areItemsTheSame(
            ancien: NoeudExplorateur,
            nouveau: NoeudExplorateur,
        ): Boolean = ancien.uri == nouveau.uri

        override fun areContentsTheSame(
            ancien: NoeudExplorateur,
            nouveau: NoeudExplorateur,
        ): Boolean = ancien == nouveau
    }
}
