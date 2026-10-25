package jo.codeide.feature.ai

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ai.PieceContexte
import jo.codeide.core.ai.TypePieceContexte
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.Retour
import jo.codeide.feature.ai.databinding.FragmentIaChatBinding
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Onglet Chat du tiroir IA (mission IA, lot IA-3, spec E1) : liste des
 * messages (DiffUtil, rendu incrémental du message en génération),
 * états transverses (IA non activée → consentement ; conversation vide),
 * compositeur avec bascule Envoyer ↔ Arrêter (l'annulation ferme la
 * requête — critère d'acceptation IA-3), effets presse-papiers.

 * Le seuil de fonctions est structurellement dépassé : un fragment
 * est un CONTRAT de câblage (cycle de vie + écouteurs — précédent
 * `PanneauConsoleFragment`).
 */
@Suppress("TooManyFunctions")
@AndroidEntryPoint
public class ChatFragment : Fragment() {
    /** Navigation découplée (consentement). */
    @Inject
    lateinit var navigator: AppNavigator

    private val viewModel: ChatViewModel by viewModels()

    private var liaison: FragmentIaChatBinding? = null

    private var adaptateur: AdapterMessages? = null

    /** Garde anti-écho : l'écriture programmatique du brouillon ne
     * doit pas repartir vers le ViewModel comme une frappe. */
    private var synchronisationSaisie = false

    /** Garde du dialogue bloquant de secret : une seule boîte par
     * suspension de l'envoi (l'état vide réarme la garde). */
    private var dialogueSecretAffiche = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val liaisonLocale = FragmentIaChatBinding.inflate(inflater, container, false)
        liaison = liaisonLocale
        return liaisonLocale.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val liaisonLocale = liaison ?: return
        val adaptateurLocal =
            AdapterMessages(
                onCopierMessage = { identifiant -> viewModel.onAction(ActionChatIa.CopierMessage(identifiant)) },
                onCopierBloc = { code -> viewModel.onAction(ActionChatIa.CopierBlocCode(code)) },
                onInsererBloc = { code -> viewModel.onAction(ActionChatIa.InsererBlocCode(code)) },
                onAppliquerBloc = { code -> viewModel.onAction(ActionChatIa.AppliquerBlocCode(code)) },
            )
        adaptateur = adaptateurLocal
        liaisonLocale.listeMessages.adapter = adaptateurLocal
        liaisonLocale.listeMessages.layoutManager = LinearLayoutManager(requireContext())

        liaisonLocale.boutonEnvoyer.setOnClickListener { viewModel.onAction(ActionChatIa.Envoyer) }
        liaisonLocale.boutonArreter.setOnClickListener { viewModel.onAction(ActionChatIa.Arreter) }
        liaisonLocale.boutonConsentement.setOnClickListener { viewModel.onAction(ActionChatIa.OuvrirConsentement) }
        // v0.188.0 (rapport IA S3) : l'état « Ajoutez une clé » mène au
        // DÉTAIL du fournisseur ACTIF (c'est sa clé qui manque).
        liaisonLocale.boutonAjouterCle.setOnClickListener { viewModel.onAction(ActionChatIa.OuvrirFournisseurCle) }
        // Le brouillon vit dans le ViewModel (survit à la rotation) : la
        // frappe y est répercutée, l'écriture programmatique (effacement
        // après envoi, restauration) est gardée par le drapeau anti-écho.
        liaisonLocale.saisieIa.doAfterTextChanged { texte ->
            if (!synchronisationSaisie) {
                viewModel.onAction(ActionChatIa.ChangerSaisie(texte?.toString().orEmpty()))
            }
        }

        // Liens du Markdown : ouverture sur initiative utilisateur (ADR
        // 0121 point 3) via le callback testable [LienIa.ouverture].
        LienIa.ouverture = { url -> ouvrirUrl(url) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // v0.188.0 (rapport IA S3) : la clé a pu être ajoutée ou
                // supprimée pendant l'arrêt (aller au fournisseur et
                // retour) SANS que les réglages n'émettent — re-vérifiée
                // à CHAQUE retour au premier plan.
                viewModel.onAction(ActionChatIa.RafraichirCle)
                launch {
                    viewModel.etat.collect { etat -> rendre(etat, liaisonLocale, adaptateurLocal) }
                }
                launch {
                    viewModel.effets.collect { effet -> rendreEffet(effet) }
                }
            }
        }
    }

    /**
     * Rouvre une conversation de l'historique (lot IA-6, appelé par
     * [IaTiroirFragment]) : l'action charge les messages persistés dans
     * la vue — l'envoi suivant poursuit la même conversation.
     */
    public fun ouvrirConversation(conversationId: String) {
        viewModel.onAction(ActionChatIa.OuvrirConversation(conversationId))
    }

    override fun onDestroyView() {
        LienIa.ouverture = null
        adaptateur = null
        liaison = null
        dialogueSecretAffiche = false
        super.onDestroyView()
    }

    private fun rendre(etat: EtatChatIa, liaisonLocale: FragmentIaChatBinding, adaptateurLocal: AdapterMessages) {
        adaptateurLocal.submitList(etat.messages)
        // Le brouillon vit dans le ViewModel : effacement après envoi et
        // restauration post-rotation passent ICI (drapeau anti-écho — la
        // frappe utilisateur, elle, ne repasse jamais par ce chemin).
        if (liaisonLocale.saisieIa.text.toString() != etat.saisie) {
            synchronisationSaisie = true
            liaisonLocale.saisieIa.setText(etat.saisie)
            liaisonLocale.saisieIa.setSelection(etat.saisie.length)
            synchronisationSaisie = false
        }
        // Bouton principal : Envoyer (repos) ↔ Arrêter (génération en cours).
        liaisonLocale.boutonEnvoyer.isVisible = !etat.generationEnCours
        liaisonLocale.boutonArreter.isVisible = etat.generationEnCours

        rendrePuces(etat, liaisonLocale)

        // Dialogue bloquant de secret (ADR 0124 §4) : tant que l'état
        // porte des alertes non tranchées, AUCUN envoi part — l'humain
        // choisit « Envoyer sans » ou « Annuler ».
        if (etat.alerteSecret.isNotEmpty()) {
            afficherDialogueSecret(etat)
        } else {
            dialogueSecretAffiche = false
        }

        // Défilement automatique vers le bas pendant la génération.
        if (etat.generationEnCours && etat.messages.isNotEmpty()) {
            liaisonLocale.listeMessages.scrollToPosition(etat.messages.lastIndex)
        }

        // États d'écran (v0.188.0, rapport IA §5 Lot 3 — B1a/B1b) : le
        // modèle PUR décrit l'écran ; le calque vit DANS la zone des
        // messages, le compositeur n'est plus JAMAIS recouvert — il est
        // MASQUÉ dans les états fermés (inactif, clé absente) et LIBRE
        // dès l'accueil vide (le premier message doit pouvoir être tapé).
        when (
            PresentationChatIa.decrireEcranChat(
                active = etat.active,
                cleAbsente = etat.cleAbsente,
                nbMessages = etat.messages.size,
            )
        ) {
            EcranChat.INACTIF -> {
                liaisonLocale.etatTransverse.isVisible = true
                liaisonLocale.etatTitre.setText(R.string.ia_ia_inactive_titre)
                liaisonLocale.etatTexte.setText(R.string.ia_ia_inactive_texte)
                liaisonLocale.boutonConsentement.isVisible = true
                liaisonLocale.boutonAjouterCle.isVisible = false
                liaisonLocale.composeurIa.isVisible = false
            }

            EcranChat.CLE_ABSENTE -> {
                // S3 : la clé du fournisseur ACTIF manque — saisie fermée,
                // l'ajout de clé à UN appui.
                liaisonLocale.etatTransverse.isVisible = true
                liaisonLocale.etatTitre.setText(R.string.ia_cle_absente_titre)
                liaisonLocale.etatTexte.setText(R.string.ia_cle_absente_texte)
                liaisonLocale.boutonConsentement.isVisible = false
                liaisonLocale.boutonAjouterCle.isVisible = true
                liaisonLocale.composeurIa.isVisible = false
            }

            EcranChat.VIDE -> {
                liaisonLocale.etatTransverse.isVisible = true
                liaisonLocale.etatTitre.setText(R.string.ia_chat_vide_titre)
                liaisonLocale.etatTexte.setText(R.string.ia_chat_vide_texte)
                liaisonLocale.boutonConsentement.isVisible = false
                liaisonLocale.boutonAjouterCle.isVisible = false
                liaisonLocale.composeurIa.isVisible = true
            }

            EcranChat.CONVERSATION -> {
                liaisonLocale.etatTransverse.isVisible = false
                liaisonLocale.composeurIa.isVisible = true
            }
        }
    }

    /**
     * Rend la rangée de puces (mission IA, lot IA-4) : UNE puce par
     * pièce assemblée + puce budget + puce « @ Fichier ». La puce
     * affichée EST ce qui part — le retrait repart vers le ViewModel.
     */
    private fun rendrePuces(etat: EtatChatIa, liaisonLocale: FragmentIaChatBinding) {
        val conteneur = liaisonLocale.conteneurPuces
        conteneur.removeAllViews()
        for (piece in etat.pieces) {
            conteneur.addView(
                puce(
                    layoutInflater,
                    liaison?.conteneurPuces,
                    libellePuce(requireContext(), piece),
                    retirable = true,
                ) { _ ->
                    if (piece.type == TypePieceContexte.FichierMentionne) {
                        piece.cheminRelatif?.let { chemin -> viewModel.onAction(ActionChatIa.RetirerMention(chemin)) }
                    } else {
                        viewModel.onAction(ActionChatIa.RetirerPiece(piece.type))
                    }
                },
            )
        }
        if (etat.pieces.isNotEmpty()) {
            conteneur.addView(
                puce(
                    layoutInflater,
                    liaison?.conteneurPuces,
                    getString(R.string.ia_jetons_contexte, etat.jetonsContexte),
                    retirable = false,
                ) { _ -> Unit },
            )
        }
        conteneur.addView(
            puce(
                layoutInflater,
                liaison?.conteneurPuces,
                getString(R.string.ia_ajouter_mention),
                retirable = false,
            ) { _ ->
                viewModel.onAction(ActionChatIa.OuvrirSelecteurFichiers)
            },
        )
        liaisonLocale.rangeePuces.isVisible = true
    }

    /** Dialogue bloquant « secret détecté » (ADR 0124 §4) — l'humain tranche. */
    private fun afficherDialogueSecret(etat: EtatChatIa) {
        if (dialogueSecretAffiche) {
            return
        }
        dialogueSecretAffiche = true
        val motifs =
            etat.alerteSecret
                .map { it.motif }
                .distinct()
                .joinToString("\n• ", "• ")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ia_secret_titre)
            .setMessage(getString(R.string.ia_secret_texte, motifs))
            .setCancelable(false)
            .setPositiveButton(R.string.ia_secret_envoyer) { _, _ ->
                viewModel.onAction(ActionChatIa.ConfirmerEnvoiSecret)
            }.setNegativeButton(R.string.ia_secret_annuler) { _, _ ->
                viewModel.onAction(ActionChatIa.AnnulerAlerteSecret)
            }.show()
    }

    /** Sélecteur `@` : liste bornée des fichiers du projet, filtrage local. */
    private fun ouvrirSelecteurFichiers() {
        val chemins = viewModel.etat.value.cheminsDisponibles
        val vue = layoutInflater.inflate(R.layout.vue_selecteur_fichiers_ia, null)
        val liste = vue.findViewById<ListView>(R.id.liste_fichiers)
        val recherche =
            vue.findViewById<com.google.android.material.textfield.TextInputEditText>(
                R.id.recherche_fichiers,
            )
        val adaptateur =
            android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                chemins.toMutableList(),
            )
        liste.adapter = adaptateur
        recherche.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: android.text.Editable?) {
                    val filtre = s?.toString().orEmpty().trim()
                    adaptateur.clear()
                    adaptateur.addAll(
                        if (filtre.isEmpty()) {
                            chemins
                        } else {
                            chemins.filter { it.contains(filtre, ignoreCase = true) }
                        },
                    )
                }
            },
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ia_selecteur_titre)
            .setView(vue)
            .setNegativeButton(R.string.ia_secret_annuler, null)
            .show()
        liste.setOnItemClickListener { _, _, position, _ ->
            val chemin = adaptateur.getItem(position) ?: return@setOnItemClickListener
            viewModel.onAction(ActionChatIa.AjouterMention(chemin))
        }
    }

    private fun rendreEffet(effet: EffetChatIa) {
        when (effet) {
            is EffetChatIa.Copier -> {
                copier(effet.texte)
            }

            EffetChatIa.OuvrirConsentement -> {
                navigator.openAiConsentement()
            }

            is EffetChatIa.OuvrirFournisseur -> {
                // v0.188.0 (rapport IA S3) : « Ajouter une clé » — le
                // repli de navigation du Lot 1 rend cet appel effectif
                // DEPUIS l'éditeur aussi.
                navigator.openAiFournisseur(effet.identifiant)
            }

            EffetChatIa.OuvrirSelecteurFichiers -> {
                ouvrirSelecteurFichiers()
            }

            is EffetChatIa.Message -> {
                val texte =
                    effet.argument?.let { requireContext().getString(effet.ressource, it) }
                        ?: requireContext().getString(effet.ressource)
                Retour.snackbar(requireView(), texte)
            }

            is EffetChatIa.AfficherDiff -> {
                // Carte de diff de l'éditeur (lot IA-5) : la proposition
                // passe par le contrat hôte — hôte absent (chat hors
                // éditeur) : aucun effet visible.
                (activity as? jo.codeide.core.ui.ControleurIaTiroir)
                    ?.afficherPropositionDiff(effet.proposition)
            }
        }
    }

    /** Presse-papiers (même API que le tiroir terminal) — retour
     * Snackbar thématisé (ADR 0127 §3), miroir du terminal et du Logcat. */
    private fun copier(texte: String) {
        val gestionnaire =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        gestionnaire.setPrimaryClip(ClipData.newPlainText(getString(R.string.ia_tiroir_titre), texte))
        Retour.snackbar(requireView(), getString(R.string.ia_copie_effectuee))
    }

    /** Ouvre un lien Markdown dans le navigateur — action utilisateur
     * explicite ; sans navigateur sur l'appareil : sans effet (donnée
     * du modèle, jamais un lien critique du flux de travail). */
    private fun ouvrirUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (ignore: ActivityNotFoundException) {
            // Aucun navigateur : l'ouverture est volontairement sans effet.
        }
    }
}

/** Libellé localisé d'une puce — la clé brute vient du domaine. */
private fun libellePuce(contexte: Context, piece: PieceContexte): String = when (piece.type) {
    TypePieceContexte.FichierActif -> {
        contexte.getString(
            R.string.ia_puce_fichier_actif,
            piece.cheminRelatif.orEmpty(),
        )
    }

    TypePieceContexte.Selection -> {
        contexte.getString(R.string.ia_puce_selection)
    }

    TypePieceContexte.Diagnostics -> {
        contexte.getString(R.string.ia_puce_diagnostics)
    }

    TypePieceContexte.FichierMentionne -> {
        piece.cheminRelatif.orEmpty()
    }

    TypePieceContexte.ListeChemins -> {
        contexte.getString(R.string.ia_puce_chemins)
    }
}

/** Gonfle une puce ; [auClic] reçoit le libellé — la croix seule est
 * masquée quand la puce n'est pas retirable (budget, « @ Fichier »). */
private fun puce(
    gonfleur: LayoutInflater,
    parent: ViewGroup?,
    libelle: String,
    retirable: Boolean,
    auClic: (String) -> Unit,
): View {
    val vue = gonfleur.inflate(R.layout.ligne_puce_contexte, parent, false)
    vue.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.libelle_puce).text = libelle
    if (!retirable) {
        vue.findViewById<View>(R.id.croix_puce).isVisible = false
    }
    vue.setOnClickListener { auClic(libelle) }
    return vue
}
