package jo.codeide.feature.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.ai.databinding.FragmentIaRechercheBinding
import jo.codeide.feature.ai.databinding.LigneResultatRechercheIaBinding

/**
 * Onglet Recherche du tiroir IA (rapport IA, lot 3 — d'après la
 * maquette) : remplace l'état « bientôt » — recherche de FICHIERS du
 * projet par nom (même source bornée que le sélecteur `@` du chat),
 * un résultat par ligne avec l'action « Demander à l'IA » qui bascule
 * sur le chat et préremplit « /expliquer <chemin> » (mécanisme
 * FragmentResult du tiroir, miroir de l'ouverture de conversation).
 *
 * Aucune logique ici : le filtre vit dans [RechercheIaViewModel]
 * (testé en JVM pur), l'action vit dans l'hôte [IaTiroirFragment].
 */
@AndroidEntryPoint
public class RechercheIaFragment : Fragment() {
    private val viewModel: RechercheIaViewModel by viewModels()

    private var liaisonAmorce: FragmentIaRechercheBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val liaisonLocale = FragmentIaRechercheBinding.inflate(inflater, container, false)
        liaisonAmorce = liaisonLocale
        return liaisonLocale.root
    }

    override fun onViewCreated(vue: View, savedInstanceState: Bundle?) {
        super.onViewCreated(vue, savedInstanceState)
        val liaison = liaisonAmorce ?: return
        val adaptateur =
            AdaptateurResultats { chemin ->
                (parentFragment as? IaTiroirFragment)?.ouvrirDiscussionIaSur(chemin)
            }
        liaison.listeResultatsRecherche.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeResultatsRecherche.adapter = adaptateur

        // Filtre LOCAL à la frappe (la liste est bornée — le filtre ne
        // rebalaye jamais le projet).
        liaison.champRechercheIa.doAfterTextChanged { texte ->
            viewModel.changerRequete(texte?.toString().orEmpty())
        }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            adaptateur.submitList(etat.resultats)
            liaison.texteRechercheVide.isVisible = etat.resultats.isEmpty()
            liaison.texteRechercheVide.setText(
                when {
                    etat.chargement -> R.string.ia_recherche_chargement
                    etat.projetFerme -> R.string.ia_recherche_projet_ferme
                    etat.requete.isNotBlank() -> R.string.ia_recherche_aucun_resultat
                    else -> R.string.ia_recherche_vide
                },
            )
        }
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }
}

/** Adaptateur des résultats (DiffUtil sur le chemin — identifiant stable). */
private class AdaptateurResultats(private val surDemanderIa: (String) -> Unit) :
    ListAdapter<ResultatRechercheIa, AdaptateurResultats.Porteur>(ComparateurResultats()) {
    private class ComparateurResultats : DiffUtil.ItemCallback<ResultatRechercheIa>() {
        override fun areItemsTheSame(ancienne: ResultatRechercheIa, nouvelle: ResultatRechercheIa): Boolean =
            ancienne.cheminRelatif == nouvelle.cheminRelatif

        override fun areContentsTheSame(ancienne: ResultatRechercheIa, nouvelle: ResultatRechercheIa): Boolean =
            ancienne == nouvelle
    }

    public class Porteur(private val liaison: LigneResultatRechercheIaBinding) : RecyclerView.ViewHolder(liaison.root) {
        public fun lier(resultat: ResultatRechercheIa, surDemanderIa: (String) -> Unit) {
            liaison.nomResultat.text = resultat.nom
            liaison.dossierResultat.text = resultat.dossier
            liaison.dossierResultat.isVisible = resultat.dossier.isNotBlank()
            liaison.root.contentDescription =
                liaison.root.context.getString(R.string.ia_recherche_resultat_cd, resultat.cheminRelatif)
            // Ligne ET bouton portent la MÊME action (zone tactile
            // généreuse — le bouton reste le geste explicite).
            liaison.root.setOnClickListener { surDemanderIa(resultat.cheminRelatif) }
            liaison.boutonDemanderIa.setOnClickListener { surDemanderIa(resultat.cheminRelatif) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Porteur =
        Porteur(LigneResultatRechercheIaBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(porteur: Porteur, position: Int) {
        porteur.lier(getItem(position), surDemanderIa)
    }
}
