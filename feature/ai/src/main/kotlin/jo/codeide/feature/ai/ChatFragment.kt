package jo.codeide.feature.ai

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import androidx.activity.result.contract.ActivityResultContracts
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
import jo.codeide.core.ui.SectionParametres
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

    /**
     * Punaisage au bas (v0.196.0) : `true` tant que la lecture reste
     * COLLÉE au bas de la conversation — le flux est suivi pixel par
     * pixel ; un appui sur « Envoyer » ou « Revenir en bas » le
     * réarme, une remontée de lecture l'annule (plus jamais de yank).
     */
    private var punaiserBas = true

    /**
     * Empreinte des puces DEJA posées (v0.196.0, ANR) : la rangée ne
     * se regonfle QUE si les pièces ou le budget ont CHANGÉ — avant,
     * chaque jeton du flux détruisait et regonflait la rangée entière.
     */
    private var empreintePuces: String? = null

    /** Empreinte des suggestions de COMMANDES déjà posées (rapport IA,
     * lot 3 — même garde anti-regonflage que les puces). */
    private var empreinteCommandes: String? = null

    /** Nom de fichier proposé par l'export Markdown (rapport IA, lot 3). */
    private val nomFichierExport: String
        get() = "reponse-ia-${System.currentTimeMillis()}.md"

    /**
     * Export Markdown (rapport IA, lot 3 — d'après la maquette) : le
     * sélecteur SAF `CreateDocument("text/markdown")` rend l'EMPLACEMENT
     * à l'utilisateur — la réponse brute part dans l'URI choisie. Le
     * texte en attente survit à la recréation du lanceur (rotation).
     */
    private var exportEnAttente: String? = null

    private val lanceurExport =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            val texte = exportEnAttente
            exportEnAttente = null
            if (uri != null && texte != null) {
                ecrireExport(uri, texte)
            }
        }

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
                onRegenererMessage = { viewModel.onAction(ActionChatIa.Regenerer) },
                onExporterMessage = { identifiant -> viewModel.onAction(ActionChatIa.ExporterMessage(identifiant)) },
                onActionErreur = { _, action -> appliquerActionErreur(action) },
            )
        adaptateur = adaptateurLocal
        liaisonLocale.listeMessages.adapter = adaptateurLocal
        liaisonLocale.listeMessages.layoutManager = LinearLayoutManager(requireContext())
        // Punaisage (v0.196.0) : chaque défilement réel (l'utilisateur)
        // ou suivi (le flux) réévalue « suis-je au bas ? » — le bouton
        // « Revenir en bas » apparaît dès que la lecture a remonté.
        liaisonLocale.listeMessages.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recycler: RecyclerView, dx: Int, dy: Int) {
                    majPunaisage(recycler)
                }
            },
        )

        liaisonLocale.boutonEnvoyer.setOnClickListener {
            // Envoyer = l'attention revient à la conversation : le bas
            // redevient la référence de lecture (v0.196.0).
            punaiserBas = true
            viewModel.onAction(ActionChatIa.Envoyer)
        }
        liaisonLocale.boutonArreter.setOnClickListener { viewModel.onAction(ActionChatIa.Arreter) }
        liaisonLocale.boutonConsentement.setOnClickListener { viewModel.onAction(ActionChatIa.OuvrirConsentement) }
        // « Revenir en bas » (v0.196.0) : recolle la conversation au bas
        // et REPREND le suivi du flux — le geste standard des clients de chat.
        liaisonLocale.boutonRevenirBas.setOnClickListener {
            punaiserBas = true
            suivreLeBas(liaisonLocale)
        }
        // Lot 5, point 3 : le modèle se choisit depuis l'EN-TÊTE du chat.
        liaisonLocale.boutonModele.setOnClickListener { ouvrirSelecteurModele() }
        // B3 (rapport IA, lot 1) : « ＋ Nouvelle » referme la discussion
        // courante (messages, pièces, jetons, génération annulée) —
        // une discussion VIDE n'est jamais persistée.
        liaisonLocale.boutonNouvelleDiscussion.setOnClickListener {
            viewModel.onAction(ActionChatIa.NouvelleConversation)
        }
        // v0.188.0 (rapport IA S3) : l'état « Ajoutez une clé » mène au
        // DÉTAIL du fournisseur ACTIF (c'est sa clé qui manque).
        liaisonLocale.boutonAjouterCle.setOnClickListener { viewModel.onAction(ActionChatIa.OuvrirFournisseurCle) }
        // Actions rapides de l'accueil vide (rapport IA, lot 3 — d'après
        // la maquette) : un appui PRÉREMPLIT la commande « / » —
        // l'utilisateur garde la main sur l'envoi (aucune dépense de
        // jetons sans lui), le curseur se pose prêt à préciser.
        liaisonLocale.actionRapideExpliquer.setOnClickListener { preparerSaisie(CommandesBarreIa.COMMANDE_EXPLIQUER) }
        liaisonLocale.actionRapideCorriger.setOnClickListener { preparerSaisie(CommandesBarreIa.COMMANDE_CORRIGER) }
        liaisonLocale.actionRapideTests.setOnClickListener { preparerSaisie(CommandesBarreIa.COMMANDE_TESTS) }
        liaisonLocale.actionRapideDocumenter.setOnClickListener { preparerSaisie(CommandesBarreIa.COMMANDE_DOC) }

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

        installerResultatsTiroir()

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
     * Résultats du TIROIR (B2/B3, rapport IA lot 1) : l'ouverture d'une
     * discussion de l'historique et « ＋ Nouvelle » arrivent par FRAGMENT
     * RESULT — le tiroir pose le résultat, le chat le consomme À SON
     * PASSAGE À STARTED (que la vue soit déjà attachée ou en cours
     * d'attachement asynchrone : le résultat ATTEND dans le
     * FragmentManager). L'ancien appel direct sur un fragment
     * potentiellement sans vue était PERDU quand le chat n'était pas
     * encore attaché.
     */
    private fun installerResultatsTiroir() {
        parentFragmentManager.setFragmentResultListener(
            CLE_RESULTAT_OUVERTURE_CONVERSATION,
            viewLifecycleOwner,
        ) { _, extras ->
            val identifiant = extras.getString(EXTRA_CONVERSATION_ID)
            if (!identifiant.isNullOrBlank()) {
                viewModel.onAction(ActionChatIa.OuvrirConversation(identifiant))
            }
        }

        // « ＋ Nouvelle » depuis l'historique — même mécanisme (résultat
        // consommé à STARTED), action sans identifiant.
        parentFragmentManager.setFragmentResultListener(
            CLE_RESULTAT_NOUVELLE_DISCUSSION,
            viewLifecycleOwner,
        ) { _, _ -> viewModel.onAction(ActionChatIa.NouvelleConversation) }

        // Préremplissage depuis la RECHERCHE (rapport IA, lot 3 — d'après
        // la maquette) : « Demander à l'IA » sur un fichier bascule sur le
        // chat et pose « /expliquer <chemin> » dans la saisie — même
        // mécanisme, l'extra porte le TEXTE à poser.
        parentFragmentManager.setFragmentResultListener(
            CLE_RESULTAT_PREPARER_SAISIE,
            viewLifecycleOwner,
        ) { _, extras ->
            val texte = extras.getString(EXTRA_SAISIE_PREREMPLIE)
            if (!texte.isNullOrBlank()) {
                preparerSaisie(texte)
            }
        }
    }

    /**
     * Pose un texte dans la saisie (commande « / » ou préremplissage de
     * la recherche, rapport IA, lot 3) : l'état reste la source de
     * vérité (l'action passe par le ViewModel), l'anti-écho garde la
     * frappe programmatique hors du flux utilisateur, le curseur se
     * pose à la FIN (prêt à préciser) et le champ prend le focus.
     */
    private fun preparerSaisie(texte: String) {
        val liaisonLocale = liaison ?: return
        viewModel.onAction(ActionChatIa.ChangerSaisie(texte))
        synchronisationSaisie = true
        liaisonLocale.saisieIa.setText(texte)
        liaisonLocale.saisieIa.setSelection(texte.length)
        synchronisationSaisie = false
        liaisonLocale.saisieIa.requestFocus()
    }

    override fun onDestroyView() {
        LienIa.ouverture = null
        adaptateur = null
        liaison = null
        dialogueSecretAffiche = false
        super.onDestroyView()
    }

    private fun rendre(etat: EtatChatIa, liaisonLocale: FragmentIaChatBinding, adaptateurLocal: AdapterMessages) {
        // Suivi du bas (v0.196.0) : la soumission se fait AVEC RAPPEL DE
        // COMMIT — le recollage pixel se joue APRÈS l'application du diff
        // de la liste (position et hauteur réelles), pas avant. TANT QUE
        // la lecture est punaisée au bas, la réponse qui grandit reste
        // COLLÉE à l'écran : plus un saut `scrollToPosition` PAR JETON qui
        // faisait trembler la liste — le défilement suit exactement les
        // pixels ajoutés (recette des clients de chat).
        adaptateurLocal.submitList(etat.messages) {
            if (punaiserBas) {
                suivreLeBas(liaisonLocale)
            } else {
                majPunaisage(liaisonLocale.listeMessages)
            }
        }
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

        // Suggestions de COMMANDES « / » (rapport IA, lot 3 — d'après la
        // maquette) : la frappe d'un « / » non tranché suggère, la
        // commande complétée se tait.
        rendreCommandes(etat, liaisonLocale)

        // Dialogue bloquant de secret (ADR 0124 §4) : tant que l'état
        // porte des alertes non tranchées, AUCUN envoi part — l'humain
        // choisit « Envoyer sans » ou « Annuler ».
        if (etat.alerteSecret.isNotEmpty()) {
            afficherDialogueSecret(etat)
        } else {
            dialogueSecretAffiche = false
        }

        // Défilement automatique pendant la génération (v0.195.0) :
        // REMPLACÉ (v0.196.0) par le punaisage pixel ci-dessus — l'ancienne
        // condition (`findLastCompletelyVisibleItemPosition >= lastIndex - 1`)
        // devenait FAUSSE dès que le message en génération dépassait la
        // hauteur de l'écran : le suivi mourait en pleine réponse
        // (« ça fige avant de s'afficher les résultats »).

        // En-tête (Lot 5, point 3) : le sélecteur de modèle n'est offert
        // que dans les états OUVERTS — jamais de contrôle sans effet.
        val ecran =
            PresentationChatIa.decrireEcranChat(
                active = etat.active,
                cleAbsente = etat.cleAbsente,
                nbMessages = etat.messages.size,
            )
        rendreEnTete(etat, ecran, liaisonLocale)
        rendreEcran(ecran, liaisonLocale)
    }

    /**
     * Réévalue le punaisage après un défilement (v0.196.0) : mesure
     * PIXEL du dernier enfant posé — reste vraie même quand le message
     * en génération dépasse l'écran (le talon d'Achille de l'ancienne
     * condition par positions). Le bouton « Revenir en bas » suit : il
     * n'apparaît QUE quand il y a une lecture remontée à reprendre.
     */
    @Suppress("ReturnCount") // la fonction EST la chaîne de gardes (liaison
    // perdue, gestionnaire absent, enfant absent) — même motif que
    // `FournisseurIaActif.acceder`.
    private fun majPunaisage(recycler: RecyclerView) {
        val liaisonLocale = liaison ?: return
        val gestionnaire = recycler.layoutManager as? LinearLayoutManager ?: return
        val dernierEnfant = gestionnaire.getChildAt(gestionnaire.childCount - 1) ?: return
        punaiserBas =
            PunaisageFluxChat.estPunaise(
                basDernierEnfant = dernierEnfant.bottom,
                hauteurZone = recycler.height,
                paddingBas = recycler.paddingBottom,
            )
        liaisonLocale.boutonRevenirBas.isVisible = !punaiserBas && gestionnaire.itemCount > 1
    }

    /**
     * RECOLLE la conversation au bas (v0.196.0) : saut FRANC quand le
     * dernier message n'est pas encore posé (conversation rouverte,
     * nouveau message hors zone) puis recollage PIXEL après le
     * passage de disposition — même un dernier message plus HAUT que
     * l'écran atterrît le bas à l'écran ; sinon simple ajustement du
     * SURPLOMB par `scrollBy` : pas de tremblement, la réponse qui
     * grandit reste collée au bas.
     */
    @Suppress("ReturnCount") // un retour par issue (liste vide, dernier
    // hors zone, bas posé) — la lisibilité vise par les gardes.
    private fun suivreLeBas(liaisonLocale: FragmentIaChatBinding) {
        val recycler = liaisonLocale.listeMessages
        val gestionnaire = recycler.layoutManager as? LinearLayoutManager ?: return
        val dernier = gestionnaire.itemCount - 1
        if (dernier < 0) {
            return
        }
        if (gestionnaire.findLastVisibleItemPosition() < dernier) {
            // Dernier item hors zone : saut direct, PUIS recollage du bas
            // au passage de disposition suivant (scrollToPosition aligne le
            // HAUT de l'item — un dernier message plus haut que l'écran
            // laisserait sa moitié basse hors champs sans le recollage).
            recycler.scrollToPosition(dernier)
            recycler.post {
                recollerLeBas(recycler, gestionnaire)
            }
            return
        }
        recollerLeBas(recycler, gestionnaire)
        liaisonLocale.boutonRevenirBas.isVisible = false
    }

    /** Applique le SURPLOMB pixel (nul, positif ou négatif) du dernier enfant. */
    private fun recollerLeBas(recycler: RecyclerView, gestionnaire: LinearLayoutManager) {
        val dernierEnfant = gestionnaire.getChildAt(gestionnaire.childCount - 1) ?: return
        val surplomb =
            PunaisageFluxChat.surplomb(
                basDernierEnfant = dernierEnfant.bottom,
                hauteurZone = recycler.height,
                paddingBas = recycler.paddingBottom,
            )
        if (surplomb != 0) {
            recycler.scrollBy(0, surplomb)
        }
    }

    /**
     * En-tête du chat (rapport IA, Lot 5, points 3 et 4) : modèle actif
     * + sélecteur, et compteur d'usage cumulé de la conversation —
     * offerts uniquement dans les états OUVERTS ; compteur masqué tant
     * qu'aucun jeton n'a été rapporté (jamais d'estimation locale).
     */
    private fun rendreEnTete(etat: EtatChatIa, ecran: EcranChat, liaisonLocale: FragmentIaChatBinding) {
        liaisonLocale.enTeteChat.isVisible = ecran == EcranChat.VIDE || ecran == EcranChat.CONVERSATION
        liaisonLocale.boutonModele.text =
            etat.modeleActif.ifBlank { getString(R.string.ia_chat_modele_vide) }
        val usageVisible = etat.jetonsEntree > 0 || etat.jetonsSortie > 0
        liaisonLocale.texteUsage.isVisible = usageVisible
        if (usageVisible) {
            liaisonLocale.texteUsage.text =
                getString(R.string.ia_chat_usage, etat.jetonsEntree, etat.jetonsSortie)
        }
    }

    /**
     * États d'écran (v0.188.0, rapport IA §5 Lot 3 — B1a/B1b) : le
     * modèle PUR décrit l'écran ; le calque vit DANS la zone des
     * messages, le compositeur n'est plus JAMAIS recouvert — il est
     * MASQUÉ dans les états fermés (inactif, clé absente) et LIBRE
     * dès l'accueil vide (le premier message doit pouvoir être tapé).
     */
    private fun rendreEcran(ecran: EcranChat, liaisonLocale: FragmentIaChatBinding) {
        when (ecran) {
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
                // Rapport IA, lot 3 — actions rapides de l'accueil vide
                // (Expliquer, Corriger, Tests, Documenter).
                liaisonLocale.rangeeActionsRapides.isVisible = true
                liaisonLocale.composeurIa.isVisible = true
            }

            EcranChat.CONVERSATION -> {
                liaisonLocale.etatTransverse.isVisible = false
                liaisonLocale.rangeeActionsRapides.isVisible = false
                liaisonLocale.composeurIa.isVisible = true
            }
        }
        if (ecran == EcranChat.INACTIF || ecran == EcranChat.CLE_ABSENTE) {
            liaisonLocale.rangeeActionsRapides.isVisible = false
        }
    }

    /**
     * Rend la rangée de puces (mission IA, lot IA-4) : UNE puce par
     * pièce assemblée + puce budget + puce « @ Fichier ». La puce
     * affichée EST ce qui part — le retrait repart vers le ViewModel.
     *
     * GARDE D'EMPREINTE (v0.196.0, ANR) : la rangée n'est DÉTRUITE et
     * regonflée QUE si les pièces, le budget ou les mentions ont
     * CHANGÉ — pendant le flux, les ~20 rendus/seconde passaient
     * chacun par `removeAllViews` + une inflation par puce pour un
     * résultat STRICTEMENT identique.
     */
    private fun rendrePuces(etat: EtatChatIa, liaisonLocale: FragmentIaChatBinding) {
        val empreinte = empreintePucesDe(etat)
        if (empreinte == empreintePuces) {
            return
        }
        empreintePuces = empreinte
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

    /**
     * Rend la rangée de suggestions de COMMANDES « / » (rapport IA,
     * lot 3) : UNE puce par commande suggérée par
     * [CommandesBarreIa.suggestions] — la saisie « /ex » ne montre que
     * `/expliquer`, la commande tranchée (espace tapé) ferme la rangée.
     * Même garde d'empreinte que les puces (la frappe rebondit à chaque
     * caractère — la rangée ne se regonfle que si les suggestions
     * CHANGENT).
     */
    private fun rendreCommandes(etat: EtatChatIa, liaisonLocale: FragmentIaChatBinding) {
        val suggestions = CommandesBarreIa.suggestions(etat.saisie)
        if (suggestions.isEmpty()) {
            liaisonLocale.rangeeCommandes.isVisible = false
            empreinteCommandes = null
            return
        }
        val empreinte = suggestions.joinToString("|")
        if (empreinte == empreinteCommandes) {
            liaisonLocale.rangeeCommandes.isVisible = true
            return
        }
        empreinteCommandes = empreinte
        val conteneur = liaisonLocale.conteneurCommandes
        conteneur.removeAllViews()
        for (commande in suggestions) {
            conteneur.addView(
                puce(
                    layoutInflater,
                    liaison?.conteneurCommandes,
                    libelleCommande(requireContext(), commande),
                    retirable = false,
                ) { _ -> preparerSaisie("$commande ") },
            )
        }
        liaisonLocale.rangeeCommandes.isVisible = true
    }

    /**
     * Action de remédiation d'une carte d'erreur (rapport IA, Lot 5,
     * point 5) : chaque erreur normalisée mène à SON geste — relancer
     * le dernier échange, réparer la configuration (Paramètres → IA)
     * ou changer de modèle (sélecteur de l'en-tête, point 3).
     */
    private fun appliquerActionErreur(action: ActionErreurIa) {
        when (action) {
            ActionErreurIa.REESSAYER -> viewModel.onAction(ActionChatIa.Regenerer)
            ActionErreurIa.OUVRIR_REGLAGES -> navigator.openSettingsSection(SectionParametres.IA)
            ActionErreurIa.CHANGER_MODELE -> ouvrirSelecteurModele()
        }
    }

    /**
     * Sélecteur de modèle de l'en-tête (rapport IA, Lot 5, point 3) :
     * le modèle ACTIF (même absent du recensement) + les modèles
     * recensés du fournisseur actif ; « Actualiser la liste » relance
     * le recensement SANS quitter le chat. Liste vide : un message
     * honnête, jamais un sélecteur muet.
     */
    private fun ouvrirSelecteurModele() {
        val etat = viewModel.etat.value
        val modeles =
            (listOf(etat.modeleActif) + etat.modelesRecenses)
                .filter { it.isNotBlank() }
                .distinct()
        val libelles =
            if (modeles.isEmpty()) {
                arrayOf(getString(R.string.ia_chat_modele_aucun))
            } else {
                modeles.toTypedArray()
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ia_carte_modele_titre)
            .setItems(libelles) { _, position ->
                if (modeles.isNotEmpty()) {
                    viewModel.onAction(ActionChatIa.ChoisirModele(modeles[position]))
                }
            }.setNeutralButton(R.string.ia_bouton_actualiser) { _, _ ->
                viewModel.onAction(ActionChatIa.ActualiserModeles)
            }.setNegativeButton(R.string.ia_secret_annuler, null)
            .show()
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

            is EffetChatIa.ExporterMarkdown -> {
                // Rapport IA, lot 3 : l'EMPLACEMENT reste un choix
                // utilisateur (SAF) — le lanceur rend la main après la
                // sélection, le texte attend en champ privé.
                exportEnAttente = effet.texte
                lanceurExport.launch(nomFichierExport)
            }
        }
    }

    /**
     * Écrit l'export Markdown dans l'URI choisie (SAF) — retour
     * honnête par Snackbar : succès OU échec d'écriture, jamais un
     * silence.
     */
    // Exemption detekt ciblée (règle 16) : SwallowedException — l'incident
    // d'E/S est CONVERTI en retour honnête (Snackbar d'échec), jamais
    // remonté en exception vers l'UI (l'export ne fait pas tomber le chat).
    @Suppress("SwallowedException")
    private fun ecrireExport(uri: Uri, texte: String) {
        val ecrit =
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { sortie ->
                    sortie.write(texte.toByteArray(Charsets.UTF_8))
                }
                true
            } catch (incident: java.io.IOException) {
                // Échec d'écriture modélisé (retour honnête, jamais un crash).
                false
            }
        if (ecrit) {
            Retour.snackbar(requireView(), getString(R.string.ia_export_reussi))
        } else {
            Retour.snackbar(requireView(), getString(R.string.ia_export_echoue))
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

/**
 * Libellé localisé d'une COMMANDE « / » (rapport IA, lot 3) : le nom
 * de la commande + sa description — la puce de suggestion montre ce
 * que la commande FAIT, pas seulement son nom.
 */
internal fun libelleCommande(contexte: Context, commande: String): String = when (commande) {
    CommandesBarreIa.COMMANDE_EXPLIQUER ->
        contexte.getString(R.string.ia_commande_expliquer_libelle)

    CommandesBarreIa.COMMANDE_CORRIGER ->
        contexte.getString(R.string.ia_commande_corriger_libelle)

    CommandesBarreIa.COMMANDE_TESTS ->
        contexte.getString(R.string.ia_commande_tests_libelle)

    CommandesBarreIa.COMMANDE_DOC ->
        contexte.getString(R.string.ia_commande_doc_libelle)

    CommandesBarreIa.COMMANDE_REFACTOR ->
        contexte.getString(R.string.ia_commande_refactor_libelle)

    else -> commande
}

/**
 * EMPREINTE de la rangée de puces (v0.196.0, ANR) — pur, testé en JVM :
 * la rangée ne se regonfle QUE si l'empreinte CHANGE. Contenu : types
 * ET chemins des pièces, budget de jetons — TOUT ce que la rangée
 * affiche (la puce « @ Fichier » est statique, hors empreinte).
 */
internal fun empreintePucesDe(etat: EtatChatIa): String = etat.pieces.joinToString(separator = "|") { piece ->
    "${piece.type.name}:${piece.cheminRelatif.orEmpty()}"
} + "#" + etat.jetonsContexte
