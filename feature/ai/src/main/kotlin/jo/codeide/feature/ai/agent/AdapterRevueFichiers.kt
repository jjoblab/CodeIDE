package jo.codeide.feature.ai.agent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.core.ai.FichierRevueIa
import jo.codeide.feature.ai.databinding.LigneFichierRevueBinding

/**
 * Liste des fichiers de la revue (spec E2 : case à cocher, badge
 * Conflit). Les conflits ne sont PAS cochables — jamais écrasés.
 */
public class AdapterRevueFichiers(private val surBascule: (chemin: String, retenu: Boolean) -> Unit) :
    ListAdapter<FichierRevueIa, AdapterRevueFichiers.Porteur>(ComparateurFichiers()) {
    /** Sélection courante (chemins cochés — hors conflits). */
    public val retenus: MutableSet<String> = mutableSetOf()

    private class ComparateurFichiers : DiffUtil.ItemCallback<FichierRevueIa>() {
        override fun areItemsTheSame(ancienne: FichierRevueIa, nouvelle: FichierRevueIa): Boolean =
            ancienne.cheminRelatif == nouvelle.cheminRelatif

        override fun areContentsTheSame(ancienne: FichierRevueIa, nouvelle: FichierRevueIa): Boolean =
            ancienne == nouvelle
    }

    public class Porteur(val liaison: LigneFichierRevueBinding) : RecyclerView.ViewHolder(liaison.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Porteur =
        Porteur(LigneFichierRevueBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(porteur: Porteur, position: Int) {
        val fichier = getItem(position)
        val liaison = porteur.liaison
        liaison.cheminFichier.text = fichier.cheminRelatif
        liaison.typeFichier.text = fichier.type.name
        liaison.badgeConflit.isVisible = fichier.conflit
        liaison.caseRetenue.isEnabled = !fichier.conflit
        liaison.caseRetenue.setOnCheckedChangeListener(null)
        liaison.caseRetenue.isChecked = fichier.cheminRelatif in retenus
        liaison.caseRetenue.setOnCheckedChangeListener { _, coche ->
            if (coche) {
                retenus.add(fichier.cheminRelatif)
            } else {
                retenus.remove(fichier.cheminRelatif)
            }
            surBascule(fichier.cheminRelatif, coche)
        }
        liaison.root.contentDescription =
            fichier.cheminRelatif + if (fichier.conflit) " — conflit, non cochable" else ""
    }
}
