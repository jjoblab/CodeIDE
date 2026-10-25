package jo.codeide.feature.ai.agent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.feature.ai.R

/**
 * Liste des étapes de l'agent (spec E2 ; v0.195.0 — redesign) :
 * pastille NUMÉROTÉE (ordre du parcours) + libellé + coche de
 * complétion — rendu sec, aucune logique (TalkBack : la ligne annonce
 * rang et libellé).
 */
public class AdapterEtapesAgent : ListAdapter<EtapeAgentIa, AdapterEtapesAgent.Porteur>(ComparateurEtapes()) {
    private class ComparateurEtapes : DiffUtil.ItemCallback<EtapeAgentIa>() {
        override fun areItemsTheSame(ancienne: EtapeAgentIa, nouvelle: EtapeAgentIa): Boolean =
            ancienne.libelle == nouvelle.libelle

        override fun areContentsTheSame(ancienne: EtapeAgentIa, nouvelle: EtapeAgentIa): Boolean = ancienne == nouvelle
    }

    public class Porteur(vue: View) : RecyclerView.ViewHolder(vue) {
        val numero: TextView = vue.findViewById(R.id.numero_etape)
        val libelle: TextView = vue.findViewById(R.id.texte_etape)
        val coche: ImageView = vue.findViewById(R.id.coche_etape)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Porteur =
        Porteur(LayoutInflater.from(parent.context).inflate(R.layout.ligne_etape_agent, parent, false))

    override fun onBindViewHolder(porteur: Porteur, position: Int) {
        val etape = getItem(position)
        porteur.numero.text = (position + 1).toString()
        porteur.libelle.text = etape.libelle
        // Les étapes s'ajoutent ACHEVÉES au fil du runner ; la coche
        // n'est masquée que si une étape « en cours » venait à exister.
        porteur.coche.isVisible = !etape.enCours
        porteur.libelle.alpha = if (etape.enCours) ALPHA_EN_COURS else ALPHA_ACHEVEE
    }

    private companion object {
        const val ALPHA_ACHEVEE = 1f
        const val ALPHA_EN_COURS = 0.6f
    }
}
