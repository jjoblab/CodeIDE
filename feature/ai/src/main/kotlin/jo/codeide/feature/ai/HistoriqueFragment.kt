package jo.codeide.feature.ai

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.ConversationIa
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.ai.databinding.FragmentIaHistoriqueBinding
import jo.codeide.feature.ai.databinding.LigneConversationBinding

/**
 * Écran Historique du tiroir IA (mission IA, lot IA-6, ADR 0124 §5,
 * spec E6) : conversations persistées localement — recherche par titre,
 * renommage, suppression en cascade, tout effacer, purge de rétention
 * faite à la création (l'onglet se crée à l'ouverture du tiroir).
 * Appui = rouvre la conversation dans le Chat (l'hôte [IaTiroirFragment]
 * bascule l'onglet et transmet au ChatViewModel).
 */
@AndroidEntryPoint
public class HistoriqueFragment : Fragment() {
    private val viewModel: HistoriqueViewModel by viewModels()

    private var liaisonAmorce: FragmentIaHistoriqueBinding? = null

    private val liaison
        get() =
            checkNotNull(liaisonAmorce) {
                "liaison du panneau Historique indisponible — vue détruite ?"
            }

    private var adaptateurAmorce: AdaptateurConversations? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val liaisonLocale = FragmentIaHistoriqueBinding.inflate(inflater, container, false)
        liaisonAmorce = liaisonLocale
        return liaisonLocale.root
    }

    override fun onViewCreated(
        vue: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(vue, savedInstanceState)
        val adaptateur = AdaptateurConversations(::ouvrir, ::ouvrirMenu)
        adaptateurAmorce = adaptateur
        liaison.listeConversations.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeConversations.adapter = adaptateur

        // Recherche par titre (le ViewModel débounce — même convention
        // que l'accueil).
        liaison.champRechercheHistorique.doAfterTextChanged { texte ->
            viewModel.onAction(ActionHistoriqueIa.ChangerRecherche(texte?.toString().orEmpty()))
        }
        liaison.boutonToutEffacer.setOnClickListener { confirmerToutEffacer() }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            adaptateur.submitList(etat.conversations)
            liaison.texteHistoriqueVide.isVisible = etat.conversations.isEmpty()
            liaison.listeConversations.isVisible = etat.conversations.isNotEmpty()
        }
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet ->
            if (effet is EffetHistoriqueIa.Message) {
                Toast.makeText(requireContext(), getString(effet.ressource), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        adaptateurAmorce = null
        liaisonAmorce = null
        super.onDestroyView()
    }

    /** Rouvre la conversation dans le chat (spec E6 : appui = rouvrir). */
    private fun ouvrir(conversationId: String) {
        (parentFragment as? IaTiroirFragment)?.ouvrirConversationDansChat(conversationId)
    }

    /** Menu de ligne : Renommer / Supprimer (spec E6). */
    private fun ouvrirMenu(conversation: ConversationIa) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(conversation.titre)
            .setItems(
                arrayOf(
                    getString(R.string.ia_historique_menu_renommer),
                    getString(R.string.ia_historique_menu_supprimer),
                ),
            ) { _, choix ->
                when (choix) {
                    0 -> demanderRenommage(conversation)
                    1 -> confirmerSuppression(conversation)
                }
            }.show()
    }

    /** Renommage : champ borné pré-rempli (même patron que l'accueil). */
    private fun demanderRenommage(conversation: ConversationIa) {
        val conteneur = LinearLayout(requireContext())
        val horizontal = resources.getDimensionPixelSize(jo.codeide.core.ui.R.dimen.spacing_lg)
        val vertical = resources.getDimensionPixelSize(jo.codeide.core.ui.R.dimen.spacing_md)
        conteneur.setPadding(horizontal, vertical, horizontal, 0)
        val champ = TextInputEditText(conteneur.context)
        champ.hint = getString(R.string.ia_historique_renommer_hint)
        champ.setText(conversation.titre)
        champ.filters = arrayOf(android.text.InputFilter.LengthFilter(TITRE_MAX))
        conteneur.addView(champ)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ia_historique_renommer_titre)
            .setView(conteneur)
            .setPositiveButton(R.string.ia_historique_confirmer) { _, _ ->
                viewModel.onAction(
                    ActionHistoriqueIa.Renommer(conversation.identifiant, champ.text?.toString().orEmpty()),
                )
            }.setNegativeButton(R.string.ia_historique_annuler, null)
            .show()
    }

    /** Suppression : confirmation explicite (jamais d'effet silencieux). */
    private fun confirmerSuppression(conversation: ConversationIa) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ia_historique_suppression_titre)
            .setMessage(getString(R.string.ia_historique_suppression_message, conversation.titre))
            .setPositiveButton(R.string.ia_historique_confirmer) { _, _ ->
                viewModel.onAction(ActionHistoriqueIa.Supprimer(conversation.identifiant))
            }.setNegativeButton(R.string.ia_historique_annuler, null)
            .show()
    }

    /** Tout effacer : confirmation explicite (critère IA-6). */
    private fun confirmerToutEffacer() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ia_historique_tout_effacer_titre)
            .setMessage(R.string.ia_historique_tout_effacer_message)
            .setPositiveButton(R.string.ia_historique_confirmer) { _, _ ->
                viewModel.onAction(ActionHistoriqueIa.ToutEffacer)
            }.setNegativeButton(R.string.ia_historique_annuler, null)
            .show()
    }

    private companion object {
        /** Longueur maximale d'un titre renommé (bornage honnête). */
        private const val TITRE_MAX = 80
    }
}

/** Adaptateur de la liste (DiffUtil sur l'identifiant). */
private class AdaptateurConversations(
    private val surOuvrir: (String) -> Unit,
    private val surMenu: (ConversationIa) -> Unit,
) : ListAdapter<ConversationIa, TitulaireConversation>(ComparateurConversations) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): TitulaireConversation =
        TitulaireConversation(LigneConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        titulaire: TitulaireConversation,
        position: Int,
    ) {
        titulaire.lier(getItem(position), surOuvrir, surMenu)
    }
}

/** Titulaire d'une ligne : titre, méta (date · modèle · messages), menu. */
private class TitulaireConversation(
    private val liaison: LigneConversationBinding,
) : RecyclerView.ViewHolder(liaison.root) {
    fun lier(
        conversation: ConversationIa,
        surOuvrir: (String) -> Unit,
        surMenu: (ConversationIa) -> Unit,
    ) {
        val contexte = liaison.root.context
        liaison.titreConversation.text = conversation.titre
        val relatif =
            DateUtils.getRelativeTimeSpanString(
                conversation.modifieA,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
        liaison.metaConversation.text =
            contexte.getString(R.string.ia_historique_meta, relatif, conversation.modele)
        liaison.root.contentDescription =
            contexte.getString(R.string.ia_historique_ligne_cd, conversation.titre)
        liaison.root.setOnClickListener { surOuvrir(conversation.identifiant) }
        liaison.boutonMenuConversation.setOnClickListener { surMenu(conversation) }
    }
}

/** DiffUtil sur l'identifiant stable (tri par dernier message). */
private object ComparateurConversations : DiffUtil.ItemCallback<ConversationIa>() {
    override fun areItemsTheSame(
        ancienne: ConversationIa,
        nouvelle: ConversationIa,
    ): Boolean = ancienne.identifiant == nouvelle.identifiant

    override fun areContentsTheSame(
        ancienne: ConversationIa,
        nouvelle: ConversationIa,
    ): Boolean = ancienne == nouvelle
}
