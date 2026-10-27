package jo.codeide.feature.ai

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import jo.codeide.core.ui.Retour
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.ai.databinding.FragmentIaHistoriqueBinding
import jo.codeide.feature.ai.databinding.LigneConversationBinding

/**
 * Écran Historique du tiroir IA (mission IA, lot IA-6, ADR 0124 §5,
 * spec E6) : conversations persistées localement — recherche par titre,
 * renommage, épinglage (rapport IA, lot 3 — d'après la maquette :
 * épinglées en tête, un appui), suppression IMMÉDIATE avec « Annuler »
 * par Snackbar (rapport IA, lot 3 — plus de dialogue de confirmation,
 * l'instantané complet est restaurable), tout effacer, purge de
 * rétention faite à la création (l'onglet se crée à l'ouverture du
 * tiroir). Appui = rouvre la conversation dans le Chat (l'hôte
 * [IaTiroirFragment] bascule l'onglet et transmet au ChatViewModel).
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val liaisonLocale = FragmentIaHistoriqueBinding.inflate(inflater, container, false)
        liaisonAmorce = liaisonLocale
        return liaisonLocale.root
    }

    override fun onViewCreated(vue: View, savedInstanceState: Bundle?) {
        super.onViewCreated(vue, savedInstanceState)
        val adaptateur =
            AdaptateurConversations(
                surOuvrir = ::ouvrir,
                surMenu = ::ouvrirMenu,
                surEpingler = { conversation, epingle ->
                    viewModel.onAction(ActionHistoriqueIa.Epingler(conversation.identifiant, epingle))
                },
                surSupprimer = { conversation ->
                    viewModel.onAction(ActionHistoriqueIa.Supprimer(conversation.identifiant))
                },
            )
        adaptateurAmorce = adaptateur
        liaison.listeConversations.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeConversations.adapter = adaptateur

        // Recherche par titre (le ViewModel débounce — même convention
        // que l'accueil).
        liaison.champRechercheHistorique.doAfterTextChanged { texte ->
            viewModel.onAction(ActionHistoriqueIa.ChangerRecherche(texte?.toString().orEmpty()))
        }
        liaison.boutonToutEffacer.setOnClickListener { confirmerToutEffacer() }
        // B3 (rapport IA, lot 1) : « ＋ Nouvelle » bascule sur le chat
        // avec une discussion NEUVE — le tiroir sélectionne l'onglet
        // et pose le FragmentResult que le chat consommera à STARTED.
        liaison.boutonNouvelleDiscussion.setOnClickListener {
            (parentFragment as? IaTiroirFragment)?.ouvrirNouvelleDiscussionDansChat()
        }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            adaptateur.submitList(etat.conversations)
            liaison.texteHistoriqueVide.isVisible = etat.conversations.isEmpty()
            liaison.listeConversations.isVisible = etat.conversations.isNotEmpty()
        }
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet ->
            when (effet) {
                is EffetHistoriqueIa.Message -> {
                    Retour.snackbar(liaison.root, getString(effet.ressource))
                }

                // Rapport IA, lot 3 — suppression avec « Annuler »
                // (Snackbar) : l'instantané COMPLET voyagé par l'effet,
                // l'annulation le restaure tel quel (conversation +
                // messages, épinglage compris).
                is EffetHistoriqueIa.Supprimee -> {
                    Retour.snackbar(
                        liaison.root,
                        getString(R.string.ia_historique_supprimee),
                        getString(R.string.ia_historique_annuler_suppression),
                    ) {
                        viewModel.onAction(
                            ActionHistoriqueIa.AnnulerSuppression(effet.conversation, effet.messages),
                        )
                    }
                }
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

    /** Menu de ligne : Renommer (spec E6 — renommage conservé ; la
     * suppression vit désormais DIRECTEMENT sur la ligne, rapport IA
     * lot 3, avec « Annuler » par Snackbar). */
    private fun ouvrirMenu(conversation: ConversationIa) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(conversation.titre)
            .setItems(
                arrayOf(
                    getString(R.string.ia_historique_menu_renommer),
                ),
            ) { _, choix ->
                if (choix == 0) {
                    demanderRenommage(conversation)
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
    private val surEpingler: (ConversationIa, Boolean) -> Unit,
    private val surSupprimer: (ConversationIa) -> Unit,
) : ListAdapter<ConversationIa, TitulaireConversation>(ComparateurConversations) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TitulaireConversation =
        TitulaireConversation(LigneConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(titulaire: TitulaireConversation, position: Int) {
        titulaire.lier(getItem(position), surOuvrir, surMenu, surEpingler, surSupprimer)
    }
}

/**
 * Titulaire d'une ligne : titre, méta (date · modèle · messages),
 * épinglage en un appui (rapport IA, lot 3 — l'icône teintée PRIMAIRE
 * quand épinglée, le DAO la maintient en tête de liste), suppression
 * directe (l'annulation vit dans le Snackbar), menu (renommage).
 */
private class TitulaireConversation(private val liaison: LigneConversationBinding) :
    RecyclerView.ViewHolder(liaison.root) {
    fun lier(
        conversation: ConversationIa,
        surOuvrir: (String) -> Unit,
        surMenu: (ConversationIa) -> Unit,
        surEpingler: (ConversationIa, Boolean) -> Unit,
        surSupprimer: (ConversationIa) -> Unit,
    ) {
        val contexte = liaison.root.context
        liaison.titreConversation.text = conversation.titre
        val relatif =
            DateUtils.getRelativeTimeSpanString(
                conversation.modifieA,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
        // Lot 5, point 4 : l'usage RAPPORTÉ par le fournisseur complète
        // la méta quand la conversation en a un (jamais d'estimation).
        // Une chaîne à substituants par cas — jamais de concaténation
        // affichée (lint SetTextI18n).
        val totalJetons = conversation.jetonsEntree + conversation.jetonsSortie
        liaison.metaConversation.text =
            if (totalJetons > 0) {
                contexte.getString(R.string.ia_historique_meta_usage, relatif, conversation.modele, totalJetons)
            } else {
                contexte.getString(R.string.ia_historique_meta, relatif, conversation.modele)
            }
        liaison.root.contentDescription =
            contexte.getString(R.string.ia_historique_ligne_cd, conversation.titre)
        liaison.root.setOnClickListener { surOuvrir(conversation.identifiant) }
        liaison.boutonMenuConversation.setOnClickListener { surMenu(conversation) }

        // Épinglage (rapport IA, lot 3) : l'état se LIT sur l'icône —
        // teinte primaire + description dédiée quand épinglée ; l'appui
        // INVERSE l'état courant.
        liaison.boutonEpingleConversation.contentDescription =
            contexte.getString(
                if (conversation.epingle) {
                    R.string.ia_historique_detacher_cd
                } else {
                    R.string.ia_historique_epingler_cd
                },
            )
        liaison.boutonEpingleConversation.iconTint =
            android.content.res.ColorStateList.valueOf(
                couleurTheme(contexte, if (conversation.epingle) ATTR_PRIMAIRE else ATTR_VARIANTE),
            )
        liaison.boutonEpingleConversation.setOnClickListener {
            surEpingler(conversation, !conversation.epingle)
        }

        // Suppression directe (rapport IA, lot 3) : la ligne part
        // immédiatement, l'instantané complet vit dans l'effet — le
        // Snackbar propose « Annuler ».
        liaison.boutonSupprimerConversation.setOnClickListener { surSupprimer(conversation) }
    }

    /** Attribut de thème résolu (le clair/sombre suit le thème — pas de couleur en dur). */
    private fun couleurTheme(contexte: android.content.Context, attribut: Int): Int {
        val valeur = android.util.TypedValue()
        contexte.theme.resolveAttribute(attribut, valeur, true)
        return valeur.data
    }

    private companion object {
        /** Attributs de teinte de l'épingle (épinglée / détachée). */
        private val ATTR_PRIMAIRE = androidx.appcompat.R.attr.colorPrimary

        private val ATTR_VARIANTE = com.google.android.material.R.attr.colorOnSurfaceVariant
    }
}

/** DiffUtil sur l'identifiant stable (tri par dernier message). */
private object ComparateurConversations : DiffUtil.ItemCallback<ConversationIa>() {
    override fun areItemsTheSame(ancienne: ConversationIa, nouvelle: ConversationIa): Boolean =
        ancienne.identifiant == nouvelle.identifiant

    override fun areContentsTheSame(ancienne: ConversationIa, nouvelle: ConversationIa): Boolean = ancienne == nouvelle
}
