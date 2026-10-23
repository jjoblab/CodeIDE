package jo.codeide.feature.ai.agent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.feature.ai.R

/**
 * Liste des étapes de l'agent (spec E2 : pastille d'état + titre) —
 * rendu sec, aucune logique (TalkBack : la ligne annonce le libellé).
 */
public class AdapterEtapesAgent : ListAdapter<EtapeAgentIa, AdapterEtapesAgent.Porteur>(ComparateurEtapes()) {
    private class ComparateurEtapes : DiffUtil.ItemCallback<EtapeAgentIa>() {
        override fun areItemsTheSame(ancienne: EtapeAgentIa, nouvelle: EtapeAgentIa): Boolean =
            ancienne.libelle == nouvelle.libelle

        override fun areContentsTheSame(ancienne: EtapeAgentIa, nouvelle: EtapeAgentIa): Boolean = ancienne == nouvelle
    }

    public class Porteur(vue: View) : RecyclerView.ViewHolder(vue) {
        val libelle: TextView = vue.findViewById(R.id.texte_etape)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Porteur =
        Porteur(LayoutInflater.from(parent.context).inflate(R.layout.ligne_etape_agent, parent, false))

    override fun onBindViewHolder(porteur: Porteur, position: Int) {
        porteur.libelle.text = getItem(position).libelle
    }
}
