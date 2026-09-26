package jo.codeide.feature.editor

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textview.MaterialTextView
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentExplorateurBinding

/**
 * Fragment **Explorateur de fichiers v2** (étape 31,
 * `docs/EXPLORATEUR_V2.md`) : destination « Fichiers » du tiroir à
 * fragments (ADR 0052). Porte son propre entête (§ 4) — emblème, titre,
 * sous-titre = chemin de la racine affichée, ligne d'actions (Nouveau,
 * Replier tout, Actualiser, Légende), bascule **Projet / Privé**
 * exclusive (§ 5), fil d'Ariane de la sélection (§ 4.4) — puis le corps
 * (arbre paresseux + états d'accès) et la barre presse-papiers (§ 12).
 *
 * Les popover maison (§ 10) vivent dans [PopoverExplorateur] : actions
 * du nœud ancrées au point de contact, Nouveau ancré au bouton,
 * Déplacer vers…, confirmation Supprimer, Légende. Le popover
 * « Déplacer vers… » porte en sus une liste déroulante de destination
 * (v0.32.1) : un SECOND popover maison s'ouvre par-dessus lui, ancré à
 * l'icône du champ Destination. Le snackbar (§ 15) flotte dans la zone
 * du tiroir de l'activité, au-dessus du rail.
 *
 * Toutes les intentions passent par [ActionEditor] — le fragment ne
 * détient aucun état : il rend [EtatEditor] (patron UDF, section 5.3).
 *
 * Exemption detekt ciblée (règle 16) : le fragment porte le tiroir
 * entier de la spécification (entête, bascule, Ariane, arbre, popovers,
 * presse-papiers) — chaque fonction reste une section nommée du §
 * correspondant, l'éclater masquerait la lecture verticale de la
 * spécification ; les gardes précoces des chemins (§ popover) portent
 * des retours explicites, plus lisibles qu'un empilement.
 */
@Suppress("TooManyFunctions", "LargeClass", "LongMethod", "CyclomaticComplexMethod", "ReturnCount")
@AndroidEntryPoint
class ExplorateurFragment : Fragment() {
    private var liaisonAmorce: FragmentExplorateurBinding? = null
    private val liaison get() = liaisonAmorce!!

    /** ViewModel de l'espace de travail (porté par l'activité). */
    private val viewModel: EditorViewModel by activityViewModels()

    /** Popover maison (un seul à la fois, § 10.5). */
    private lateinit var popover: PopoverExplorateur

    /** Second popover maison (v0.32.1) : liste déroulante de destination du
     *  popover « Déplacer vers… » — s'ouvre PAR-DESSUS lui, qui reste
     *  affiché en dessous ; fermé au choix, au clic extérieur ou retour. */
    private lateinit var popoverDestination: PopoverExplorateur

    private companion object {
        /** Rotation de l'icône Actualiser (§ 4 : 0,65 s). */
        const val DUREE_ROTATION_ACTUALISER_MS = 650L

        /** Taille des étiquettes du fil d'Ariane (§ 4.4, mono 11 sp). */
        const val TAILLE_ARIANE_SP = 11f

        /** Séparateur du fil d'Ariane (§ 4.4). */
        const val SEPARATEUR_ARIANE = "›"

        /** Rotation complète de l'icône Actualiser (§ 4). */
        const val ROTATION_ACTUALISER = 360f

        /** Hauteur du séparateur d'actions du popover (§ 10.4 : 1 dp). */
        const val HAUTEUR_SEPARATEUR_ACTIONS_DP = 1

        /** Marges horizontales du séparateur d'actions (§ 10.4 : 8 dp). */
        const val MARGE_H_SEPARATEUR_ACTIONS_DP = 8

        /** Marges verticales du séparateur d'actions (§ 10.4 : 5 dp). */
        const val MARGE_V_SEPARATEUR_ACTIONS_DP = 5

        /** Opacité d'une action désactivée (§ 10.4 : 35 %). */
        const val ALPHA_ACTION_DESACTIVEE = 0.35f

        /** Padding horizontal du séparateur « › » (§ 4.4). */
        const val PADDING_SEPARATEUR_PX = 6

        /** Séparateur des chemins relatifs de l'arbre (§ 10.5). */
        const val SEPARATEUR_CHEMIN = "/"

        /** Indentation d'un niveau de dossier dans la liste de destination
         *  (v0.32.1, lecture arborescente comme l'arbre § 6.2). */
        const val INDENTATION_NIVEAU_DP = 13

        /** Padding horizontal de base d'une ligne d'action (§ 10.4). */
        const val PADDING_ACTION_POPOVER_DP = 10

        /** Largeur de coque du popover (§ 10.1) pour la mesure de la liste. */
        const val LARGEUR_POPOVER_DP = 258

        /** Hauteur maximale de la liste de destination (v0.32.1, ≈ 8 lignes) :
         *  sans borne, un arbre profond dépasserait l'écran malgré le
         *  retournement § 10.2. */
        const val HAUTEUR_MAX_LISTE_DP = 264
    }

    /** Adaptateur de l'arbre (mutations par nœud, § 11). */
    private lateinit var adaptateur: ExplorateurAdapter

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FragmentExplorateurBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(
        vue: View,
        etat: Bundle?,
    ) {
        popover = PopoverExplorateur(liaison.root)
        popoverDestination = PopoverExplorateur(liaison.root)
        brancherArbre()
        brancherActionsEntete()
        brancherBascule()
        brancherPressePapiers()
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { rendre(it) }
    }

    override fun onDestroyView() {
        popoverDestination.masquer()
        popover.masquer()
        liaisonAmorce = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------
    // Arbre (§ 6, § 11)
    // ------------------------------------------------------------------

    private fun brancherArbre() {
        adaptateur =
            ExplorateurAdapter(
                surClic = ::surClicNoeud,
                surClicLong = ::surClicLongNoeud,
                surValiderEdition = { nom -> viewModel.onAction(ActionEditor.ValiderEdition(nom)) },
                surAnnulerEdition = { viewModel.onAction(ActionEditor.AnnulerEdition) },
            )
        liaison.listeExplorateur.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeExplorateur.adapter = adaptateur
    }

    /** Tap : dossier = sélection + dépli/repli, fichier = sélection +
     *  ouverture (§ 17) — l'onglet actif reçoit le fichier. */
    private fun surClicNoeud(noeud: NoeudExplorateur) {
        viewModel.onAction(ActionEditor.SelectionnerNoeud(noeud.uri))
        if (noeud.estDossier) {
            viewModel.onAction(ActionEditor.BasculerNoeud(noeud.uri))
        } else {
            viewModel.onAction(ActionEditor.OuvrirFichier(noeud.uri))
        }
    }

    /** Appui long : popover d'actions ancré au contact (§ 10, § 17),
     *  vibration du retour haptique long. */
    private fun surClicLongNoeud(
        noeud: NoeudExplorateur,
        x: Int,
        y: Int,
    ): Boolean {
        liaison.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        montrerPopoverActions(noeud, x, y)
        return true
    }

    // ------------------------------------------------------------------
    // Entête (§ 4) : actions, bascule (§ 5), fil d'Ariane (§ 4.4)
    // ------------------------------------------------------------------

    private fun brancherActionsEntete() {
        // Nouveau : popover ancré sous le bouton, cible = dossier
        // sélectionné (sinon racine), deux actions fichier/dossier.
        liaison.boutonNouveauEntete.setOnClickListener { ancre ->
            val cible = dossierSelectionneOuRacine()
            if (cible != null) {
                val position = IntArray(2)
                ancre.getLocationOnScreen(position)
                montrerPopoverNouveau(
                    cible,
                    position[0],
                    position[1] + ancre.height,
                )
            }
        }

        liaison.boutonReplierTout.setOnClickListener {
            viewModel.onAction(ActionEditor.ReplierTout)
        }

        // Actualiser : re-vérifie l'accès, l'icône tourne 0,65 s (§ 4).
        liaison.boutonActualiserEntete.setOnClickListener { bouton ->
            viewModel.onAction(ActionEditor.Rafraichir)
            bouton
                .animate()
                .rotationBy(ROTATION_ACTUALISER)
                .setDuration(DUREE_ROTATION_ACTUALISER_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        liaison.boutonLegende.setOnClickListener { bouton ->
            val position = IntArray(2)
            bouton.getLocationOnScreen(position)
            montrerPopoverLegende(position[0], position[1] + bouton.height)
        }
    }

    /** Dossier ciblé par « Nouveau » : sélection si dossier, racine sinon
     *  (§ 10.5 — « cible = dossier sélectionné, sinon racine »). */
    private fun dossierSelectionneOuRacine(): String? {
        val etat = viewModel.etat.value
        val selection = etat.noeuds.find { it.uri == etat.uriSelection }
        if (selection != null && selection.estDossier) return selection.uri
        return etat.noeuds.firstOrNull { it.estRacine }?.uri
    }

    /** Bascule Projet / Privé (§ 5) : exclusive, réinitialise la
     *  sélection, ferme le popover ouvert. */
    private fun brancherBascule() {
        liaison.segmentProjet.setOnClickListener {
            viewModel.onAction(ActionEditor.BasculerSource(SourceArbre.PROJET))
        }
        liaison.segmentPrive.setOnClickListener {
            viewModel.onAction(ActionEditor.BasculerSource(SourceArbre.PRIVE))
        }
    }

    /** Barre presse-papiers (§ 12) : bouton Vider. */
    private fun brancherPressePapiers() {
        liaison.boutonViderPressePapiers.setOnClickListener {
            viewModel.onAction(ActionEditor.ViderPressePapiers)
        }
    }

    // ------------------------------------------------------------------
    // Rendu de l'état
    // ------------------------------------------------------------------

    private fun rendre(etat: EtatEditor) {
        rendreEntete(etat)
        rendreBascule(etat)
        rendreAriane(etat)
        rendreArbre(etat)
        rendrePressePapiers(etat)
        rendreBandeau(etat)
    }

    private fun rendreEntete(etat: EtatEditor) {
        liaison.sousTitreExplorateur.text =
            etat.cheminRacine.ifBlank {
                etat.projet?.location?.displayPath ?: ""
            }
        adaptateur.cheminRacine = liaison.sousTitreExplorateur.text.toString()
    }

    private fun rendreBascule(etat: EtatEditor) {
        val projet = etat.source == SourceArbre.PROJET
        liaison.segmentProjet.background =
            if (projet) {
                ContextCompat.getDrawable(requireContext(), R.drawable.fond_segment_projet_actif)
            } else {
                null
            }
        liaison.segmentPrive.background =
            if (!projet) {
                ContextCompat.getDrawable(requireContext(), R.drawable.fond_segment_prive_actif)
            } else {
                null
            }
        liaison.libelleSegmentProjet.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (projet) {
                    jo.codeide.core.ui.R.color.codeide_explorateur_accent
                } else {
                    jo.codeide.core.ui.R.color.codeide_explorateur_texte_2
                },
            ),
        )
        liaison.libelleSegmentPrive.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (!projet) {
                    jo.codeide.core.ui.R.color.codeide_explorateur_nom_prive
                } else {
                    jo.codeide.core.ui.R.color.codeide_explorateur_texte_2
                },
            ),
        )
        liaison.iconeSegmentProjet.setColorFilter(
            ContextCompat.getColor(
                requireContext(),
                if (projet) {
                    jo.codeide.core.ui.R.color.codeide_explorateur_accent
                } else {
                    jo.codeide.core.ui.R.color.codeide_explorateur_texte_2
                },
            ),
        )
        liaison.iconeSegmentPrive.setColorFilter(
            ContextCompat.getColor(
                requireContext(),
                if (!projet) {
                    jo.codeide.core.ui.R.color.codeide_explorateur_nom_prive
                } else {
                    jo.codeide.core.ui.R.color.codeide_explorateur_texte_2
                },
            ),
        )
        liaison.boutonActualiserEntete.isEnabled = !etat.verificationAcces
    }

    /** Fil d'Ariane (§ 4.4) : maison + ancêtres de la sélection, dernière
     *  étiquette en accent, clic = sélection du nœud. */
    private fun rendreAriane(etat: EtatEditor) {
        val conteneur = liaison.segmentsAriane
        conteneur.removeAllViews()
        val accent = ContextCompat.getColor(requireContext(), jo.codeide.core.ui.R.color.codeide_explorateur_accent)
        val texte2 = ContextCompat.getColor(requireContext(), jo.codeide.core.ui.R.color.codeide_explorateur_texte_2)
        liaison.maisonAriane.setColorFilter(accent.takeIf { etat.segmentsAriane.isEmpty() } ?: texte2)

        etat.segmentsAriane.forEachIndexed { indice, segment ->
            if (indice > 0) {
                val separateur =
                    MaterialTextView(requireContext()).apply {
                        text = SEPARATEUR_ARIANE
                        textSize = TAILLE_ARIANE_SP
                        setTextColor(texte2)
                        setPadding(PADDING_SEPARATEUR_PX, 0, PADDING_SEPARATEUR_PX, 0)
                    }
                conteneur.addView(separateur)
            }
            val etiquette =
                MaterialTextView(requireContext()).apply {
                    text = segment.nom
                    textSize = TAILLE_ARIANE_SP
                    setFontMonospace()
                    setTextColor(if (indice == etat.segmentsAriane.lastIndex) accent else texte2)
                    val uri = segment.uri
                    setOnClickListener {
                        viewModel.onAction(ActionEditor.SelectionnerNoeud(uri))
                    }
                }
            conteneur.addView(etiquette)
        }
    }

    private fun MaterialTextView.setFontMonospace() {
        typeface = android.graphics.Typeface.MONOSPACE
    }

    /** Arbre (§ 6) : éléments = nœuds visibles + éditeur inline inséré
     *  sous le dossier cible (création) ou à la place du nœud (renommage,
     *  § 11) — puis défilement doux vers la ligne flashée. */
    private fun rendreArbre(etat: EtatEditor) {
        val elements = construireElements(etat)
        adaptateur.submitList(elements)
        liaison.texteExplorateurVide.isVisible = elements.isEmpty()

        // Défilement doux vers la première ligne flashée (§ 6.4).
        val uriFlash = etat.urisFlachees.firstOrNull()
        if (uriFlash != null) {
            val position =
                elements.indexOfFirst {
                    it is ExplorateurAdapter.Element.Noeud && it.noeud.uri == uriFlash
                }
            if (position >= 0) liaison.listeExplorateur.smoothScrollToPosition(position)
        }
    }

    /** Éléments de liste : nœuds + éditeur inline (§ 11). */
    private fun construireElements(etat: EtatEditor): List<ExplorateurAdapter.Element> {
        val noeuds: List<ExplorateurAdapter.Element> = etat.noeuds.map { ExplorateurAdapter.Element.Noeud(it) }
        val edition = etat.edition ?: return noeuds

        if (edition.renommage != null) {
            // Renommage : l'éditeur remplace la ligne du nœud.
            return noeuds.map { element ->
                val noeud = (element as ExplorateurAdapter.Element.Noeud).noeud
                if (noeud.uri == edition.renommage) {
                    ExplorateurAdapter.Element.Editeur(
                        edition = edition,
                        profondeur = noeud.profondeur,
                        dernierEnfant = noeud.dernierEnfant,
                        masqueAncetres = noeud.masqueAncetresDerniers,
                    )
                } else {
                    element
                }
            }
        }

        // Création : l'éditeur s'insère sous le dossier cible (ou en tête
        // si le parent n'est pas visible).
        val positionParent =
            noeuds.indexOfFirst { (it as ExplorateurAdapter.Element.Noeud).noeud.uri == edition.uriParent }
        val profondeur =
            (noeuds.getOrNull(positionParent) as? ExplorateurAdapter.Element.Noeud)
                ?.noeud
                ?.profondeur
                ?.plus(1) ?: 1
        val editeur =
            ExplorateurAdapter.Element.Editeur(
                edition = edition,
                profondeur = profondeur,
                dernierEnfant = true,
                masqueAncetres = 0,
            )
        return if (positionParent >= 0) {
            // Liste re-typée en Element (mélange nœud + éditeur inline).
            val resultat = ArrayList<ExplorateurAdapter.Element>(noeuds.size + 1)
            resultat.addAll(noeuds)
            resultat.add(positionParent + 1, editeur)
            resultat
        } else {
            listOf<ExplorateurAdapter.Element>(editeur) + noeuds
        }
    }

    /** Barre presse-papiers (§ 12). */
    private fun rendrePressePapiers(etat: EtatEditor) {
        val presse = etat.pressePapiers
        liaison.barrePressePapiers.isVisible = presse != null
        if (presse != null) {
            liaison.textePressePapiers.text =
                getString(
                    if (presse.mode == ModePressePapiers.COPIER) {
                        R.string.presse_papiers_copier
                    } else {
                        R.string.presse_papiers_couper
                    },
                    presse.nom,
                )
        }
    }

    /** Bandeau d'accès (permission perdue, introuvable, erreur) et
     *  vérification en vol — repris de l'entête v1. */
    private fun rendreBandeau(etat: EtatEditor) {
        val acces = etat.acces
        val enVerification = etat.verificationAcces || acces == null
        val panne =
            !enVerification &&
                (etat.erreurRacine || acces == ProjectAccessState.PermissionLost || acces == ProjectAccessState.Missing)

        liaison.progressionTiroir.isVisible = enVerification && etat.source == SourceArbre.PROJET
        liaison.bandeauAcces.isVisible = panne
        if (panne) {
            when {
                // acces == null est impossible ici : « panne » requiert
                // l'absence de vérification en vol, donc un acces connu.
                etat.erreurRacine -> {
                    liaison.titreAcces.setText(R.string.editor_acces_erreur_titre)
                    liaison.messageAcces.setText(R.string.editor_acces_erreur_message)
                    liaison.boutonResoudre.setText(R.string.editor_acces_reessayer)
                    liaison.boutonResoudre.setOnClickListener {
                        viewModel.onAction(ActionEditor.Rafraichir)
                    }
                }

                acces == ProjectAccessState.PermissionLost -> {
                    liaison.titreAcces.setText(R.string.editor_acces_permission_titre)
                    liaison.messageAcces.setText(R.string.editor_acces_permission_message)
                    liaison.boutonResoudre.setText(R.string.editor_acces_resoudre)
                    liaison.boutonResoudre.setOnClickListener { activity?.finish() }
                }

                else -> {
                    liaison.titreAcces.setText(R.string.editor_acces_introuvable_titre)
                    liaison.messageAcces.setText(R.string.editor_acces_introuvable_message)
                    liaison.boutonResoudre.setText(R.string.editor_acces_resoudre)
                    liaison.boutonResoudre.setOnClickListener { activity?.finish() }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Popovers (§ 10)
    // ------------------------------------------------------------------

    /** Popover d'actions d'un nœud, ancré au contact (§ 10.4). */
    private fun montrerPopoverActions(
        noeud: NoeudExplorateur,
        x: Int,
        y: Int,
    ) {
        val etat = viewModel.etat.value
        val presse = etat.pressePapiers
        popover.montrer(R.layout.popover_actions_noeud, x, y) { vue ->
            vue.findViewById<android.widget.ImageView>(R.id.icone_tete_popover)?.setImageResource(
                if (noeud.estDossier) {
                    jo.codeide.core.ui.IconesFichiers
                        .pourDossier(noeud.prive)
                } else {
                    jo.codeide.core.ui.IconesFichiers
                        .pourNom(noeud.nom)
                },
            )
            vue.findViewById<MaterialTextView>(R.id.nom_tete_popover)?.text = noeud.nom
            vue.findViewById<MaterialTextView>(R.id.chemin_tete_popover)?.text = cheminAbsolu(noeud)

            val contexte = vue.context
            val conteneur = vue.findViewById<LinearLayout>(R.id.actions_popover)
            val contexteTete = vue.findViewById<MaterialTextView>(R.id.contexte_popover)
            contexteTete.isVisible = noeud.estDossier
            if (noeud.estDossier) {
                contexteTete.text =
                    contexte.getString(
                        R.string.popover_contexte_cible,
                        cheminRelatif(noeud, etat),
                    )
            }

            fun action(
                identifiant: Int,
                libelle: String,
                icone: Int,
                note: String? = null,
                dangereuse: Boolean = false,
                activee: Boolean = true,
                surChoix: () -> Unit,
            ) {
                val ligne =
                    LayoutInflater.from(contexte).inflate(R.layout.ligne_action_popover, conteneur, false)
                val racine = ligne.findViewById<LinearLayout>(R.id.racine_action_popover)
                racine.id = identifiant
                racine.setOnClickListener {
                    popover.masquer()
                    if (activee) surChoix()
                }
                ligne
                    .findViewById<android.widget.ImageView>(R.id.icone_action_popover)
                    .setImageResource(icone)
                ligne
                    .findViewById<android.widget.ImageView>(R.id.icone_action_popover)
                    .setColorFilter(
                        ContextCompat.getColor(
                            contexte,
                            if (dangereuse) {
                                jo.codeide.core.ui.R.color.codeide_explorateur_rouge
                            } else {
                                jo.codeide.core.ui.R.color.codeide_explorateur_texte
                            },
                        ),
                    )
                ligne.findViewById<MaterialTextView>(R.id.libelle_action_popover).text = libelle
                ligne.findViewById<MaterialTextView>(R.id.libelle_action_popover).setTextColor(
                    ContextCompat.getColor(
                        contexte,
                        if (dangereuse) {
                            jo.codeide.core.ui.R.color.codeide_explorateur_rouge
                        } else {
                            jo.codeide.core.ui.R.color.codeide_explorateur_texte
                        },
                    ),
                )
                if (dangereuse) {
                    racine.setBackgroundResource(R.drawable.fond_action_popover_dangereuse)
                }
                racine.alpha = if (activee) 1f else ALPHA_ACTION_DESACTIVEE
                racine.isClickable = activee
                note?.let {
                    ligne.findViewById<MaterialTextView>(R.id.note_action_popover).apply {
                        text = it
                        isVisible = true
                    }
                }
                conteneur.addView(ligne)
            }

            fun separateur() {
                val trait =
                    View(contexte).apply {
                        layoutParams =
                            LinearLayout
                                .LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    (HAUTEUR_SEPARATEUR_ACTIONS_DP * resources.displayMetrics.density).toInt(),
                                ).apply {
                                    setMargins(
                                        (MARGE_H_SEPARATEUR_ACTIONS_DP * resources.displayMetrics.density).toInt(),
                                        (MARGE_V_SEPARATEUR_ACTIONS_DP * resources.displayMetrics.density).toInt(),
                                        (MARGE_H_SEPARATEUR_ACTIONS_DP * resources.displayMetrics.density).toInt(),
                                        (MARGE_V_SEPARATEUR_ACTIONS_DP * resources.displayMetrics.density).toInt(),
                                    )
                                }
                        setBackgroundColor(
                            ContextCompat.getColor(
                                contexte,
                                jo.codeide.core.ui.R.color.codeide_explorateur_popover_bordure,
                            ),
                        )
                    }
                conteneur.addView(trait)
            }

            if (noeud.estDossier) {
                // Dossier : Nouveau fichier/dossier + Coller ; racine sans
                // les actions de nœud (§ 10.4).
                action(
                    View.generateViewId(),
                    getString(R.string.popover_nouveau_fichier),
                    jo.codeide.core.ui.R.drawable.ic_fichier,
                ) {
                    viewModel.onAction(ActionEditor.DebuterCreation(noeud.uri, false))
                }
                action(
                    View.generateViewId(),
                    getString(R.string.popover_nouveau_dossier),
                    jo.codeide.core.ui.R.drawable.ic_dossier,
                ) {
                    viewModel.onAction(ActionEditor.DebuterCreation(noeud.uri, true))
                }
                val collageRefuse =
                    presse == null ||
                        (
                            presse.mode == ModePressePapiers.COUPER &&
                                (noeud.uri == presse.uri || noeud.uri.startsWith("${presse.uri}/"))
                        )
                action(
                    View.generateViewId(),
                    getString(R.string.popover_coller),
                    jo.codeide.core.ui.R.drawable.ic_presse_papiers,
                    note = presse?.nom,
                    activee = !collageRefuse,
                ) {
                    viewModel.onAction(ActionEditor.CollerDans(noeud.uri))
                }
                if (!noeud.estRacine) {
                    separateur()
                    action(
                        View.generateViewId(),
                        getString(R.string.popover_copier),
                        jo.codeide.core.ui.R.drawable.ic_presse_papiers,
                    ) {
                        viewModel.onAction(ActionEditor.CopierNoeud(noeud.uri))
                    }
                    action(
                        View.generateViewId(),
                        getString(R.string.popover_couper),
                        jo.codeide.core.ui.R.drawable.ic_presse_papiers,
                    ) {
                        viewModel.onAction(ActionEditor.CouperNoeud(noeud.uri))
                    }
                    action(
                        View.generateViewId(),
                        getString(R.string.popover_deplacer_vers),
                        jo.codeide.core.ui.R.drawable.ic_deplacer_vers,
                    ) {
                        montrerPopoverDeplacer(noeud, x, y)
                    }
                    action(
                        View.generateViewId(),
                        getString(R.string.editor_menu_renommer),
                        jo.codeide.core.ui.R.drawable.ic_renommer,
                    ) {
                        viewModel.onAction(ActionEditor.DebuterRenommage(noeud.uri))
                    }
                    separateur()
                    action(
                        View.generateViewId(),
                        getString(R.string.editor_menu_supprimer),
                        jo.codeide.core.ui.R.drawable.ic_supprimer,
                        dangereuse = true,
                    ) {
                        montrerPopoverSupprimer(noeud, x, y)
                    }
                }
            } else {
                // Fichier : Ouvrir (projet seulement — l'arbre privé n'a
                // pas d'onglets, § 9), puis presse-papiers, déplacement,
                // renommage, suppression (§ 10.4).
                action(
                    View.generateViewId(),
                    getString(R.string.popover_ouvrir),
                    jo.codeide.core.ui.R.drawable.ic_ouvrir,
                    activee = !noeud.prive,
                ) {
                    viewModel.onAction(ActionEditor.OuvrirFichier(noeud.uri))
                }
                separateur()
                action(
                    View.generateViewId(),
                    getString(R.string.popover_copier),
                    jo.codeide.core.ui.R.drawable.ic_presse_papiers,
                ) {
                    viewModel.onAction(ActionEditor.CopierNoeud(noeud.uri))
                }
                action(
                    View.generateViewId(),
                    getString(R.string.popover_couper),
                    jo.codeide.core.ui.R.drawable.ic_presse_papiers,
                ) {
                    viewModel.onAction(ActionEditor.CouperNoeud(noeud.uri))
                }
                action(
                    View.generateViewId(),
                    getString(R.string.popover_deplacer_vers),
                    jo.codeide.core.ui.R.drawable.ic_deplacer_vers,
                ) {
                    montrerPopoverDeplacer(noeud, x, y)
                }
                action(
                    View.generateViewId(),
                    getString(R.string.editor_menu_renommer),
                    jo.codeide.core.ui.R.drawable.ic_renommer,
                ) {
                    viewModel.onAction(ActionEditor.DebuterRenommage(noeud.uri))
                }
                separateur()
                action(
                    View.generateViewId(),
                    getString(R.string.editor_menu_supprimer),
                    jo.codeide.core.ui.R.drawable.ic_supprimer,
                    dangereuse = true,
                ) {
                    montrerPopoverSupprimer(noeud, x, y)
                }
            }
        }
    }

    /** Popover « Déplacer vers… » (§ 10.5) : validation LOCALE du chemin
     *  (dossier connu, pas déjà là, destination hors de l'élément) pour
     *  afficher les erreurs EN LIGNE sans fermer le popover. */
    private fun montrerPopoverDeplacer(
        noeud: NoeudExplorateur,
        x: Int,
        y: Int,
    ) {
        val etat = viewModel.etat.value
        popover.montrer(R.layout.popover_deplacer, x, y) { vue ->
            val origine = vue.findViewById<MaterialTextView>(R.id.champ_origine_deplacer)
            origine.text = cheminRelatif(noeud, etat).ifBlank { "/" }
            val contexte = vue.context

            // Contexte de tête : « Déplacement — chemin relatif » (§ 10.3).
            // Le layout déplacer n'a pas de tête contextuelle — l'origine
            // affichée joue ce rôle (§ 10.5).

            val champ = vue.findViewById<android.widget.EditText>(R.id.champ_destination_deplacer)
            champ.setText(cheminParentRelatif(noeud, etat))
            val erreur = vue.findViewById<MaterialTextView>(R.id.erreur_deplacer)

            fun valider() {
                val cible =
                    champ.text
                        .toString()
                        .trim()
                        .removePrefix("./")
                        .removeSuffix("/")
                val parentCourant = cheminParentRelatif(noeud, etat)
                val cheminPropre = cheminRelatif(noeud, etat)
                when {
                    cible !in etat.cheminsDossiers -> {
                        erreur.text = contexte.getString(R.string.deplacer_erreur_introuvable)
                        erreur.isVisible = true
                    }

                    cible == parentCourant -> {
                        erreur.text = contexte.getString(R.string.deplacer_erreur_deja)
                        erreur.isVisible = true
                    }

                    cible == cheminPropre || cible.startsWith("$cheminPropre/") -> {
                        erreur.text = contexte.getString(R.string.deplacer_erreur_dans_source)
                        erreur.isVisible = true
                    }

                    else -> {
                        popover.masquer()
                        viewModel.onAction(ActionEditor.DeplacerVers(noeud.uri, cible))
                    }
                }
            }

            vue.findViewById<View>(R.id.bouton_annuler_deplacer).setOnClickListener {
                popover.masquer()
            }
            vue.findViewById<View>(R.id.bouton_valider_deplacer).setOnClickListener { valider() }

            // Liste déroulante de destination (v0.32.1) : second popover
            // par-dessus celui-ci, ancré à l'icône du champ.
            vue.findViewById<View>(R.id.bouton_destination_deroulante).setOnClickListener { ancre ->
                montrerPopoverDestination(noeud, ancre, champ, erreur, etat)
            }
            champ.setOnEditorActionListener { _, actionId, evenement ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    (
                        evenement?.action == android.view.KeyEvent.ACTION_DOWN &&
                            evenement.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                    )
                ) {
                    valider()
                    true
                } else {
                    false
                }
            }
            champ.requestFocus()
            champ.post { champ.setSelection(champ.text.length) }
        }
    }

    /** Popover « Choisir la destination » (v0.32.1) : liste déroulante
     *  ancrée à l'icône du champ Destination. Dossiers énumérés de la
     *  racine active — racine incluse, nœud déplacé et ses descendants
     *  exclus (la validation § 10.5 les refuserait de toute façon) —
     *  indentés par profondeur comme l'arbre. Le choix REMPLIT le champ
     *  (et l'efface de l'erreur en ligne) ; le popover « Déplacer
     *  vers… » reste ouvert en dessous. */
    private fun montrerPopoverDestination(
        noeud: NoeudExplorateur,
        ancre: View,
        champ: android.widget.EditText,
        erreur: MaterialTextView,
        etat: EtatEditor,
    ) {
        val position = IntArray(2)
        ancre.getLocationOnScreen(position)
        val x = position[0] + ancre.width / 2
        val y = position[1] + ancre.height
        val cheminPropre = cheminRelatif(noeud, etat)
        popoverDestination.montrer(R.layout.popover_destination_deplacer, x, y) { vue ->
            val contexte = vue.context
            val dp = contexte.resources.displayMetrics.density
            val conteneur = vue.findViewById<LinearLayout>(R.id.liste_dossiers_destination)

            val dossiers =
                etat.cheminsDossiers.filterNot { chemin ->
                    chemin == cheminPropre || chemin.startsWith("$cheminPropre$SEPARATEUR_CHEMIN")
                }

            fun dossier(chemin: String) {
                val ligne =
                    LayoutInflater.from(contexte).inflate(R.layout.ligne_action_popover, conteneur, false)
                val estRacine = chemin.isEmpty()
                val niveau = chemin.count { it == SEPARATEUR_CHEMIN[0] } + 1
                ligne.setPaddingRelative(
                    ((PADDING_ACTION_POPOVER_DP + niveau * INDENTATION_NIVEAU_DP) * dp).toInt(),
                    ligne.paddingTop,
                    ligne.paddingEnd,
                    ligne.paddingBottom,
                )
                ligne
                    .findViewById<android.widget.ImageView>(R.id.icone_action_popover)
                    .apply {
                        setImageResource(
                            if (estRacine) {
                                jo.codeide.core.ui.R.drawable.ic_maison
                            } else {
                                jo.codeide.core.ui.R.drawable.ic_dossier
                            },
                        )
                        setColorFilter(
                            ContextCompat.getColor(
                                contexte,
                                if (estRacine) {
                                    jo.codeide.core.ui.R.color.codeide_explorateur_texte_2
                                } else {
                                    jo.codeide.core.ui.R.color.codeide_explorateur_dossier
                                },
                            ),
                        )
                    }
                ligne.findViewById<MaterialTextView>(R.id.libelle_action_popover).text =
                    if (estRacine) {
                        getString(R.string.deplacer_destination_racine)
                    } else {
                        chemin.substringAfterLast(SEPARATEUR_CHEMIN)
                    }
                ligne.setOnClickListener {
                    popoverDestination.masquer()
                    champ.setText(chemin)
                    champ.setSelection(chemin.length)
                    erreur.isVisible = false
                }
                conteneur.addView(ligne)
            }
            dossiers.forEach { dossier(it) }

            // Hauteur bornée avant la mesure § 10.2 : les libellés sont
            // singleLine, la hauteur des lignes ne dépend pas de la largeur.
            val defilement = vue.findViewById<android.widget.ScrollView>(R.id.defilement_destination)
            conteneur.measure(
                View.MeasureSpec.makeMeasureSpec((LARGEUR_POPOVER_DP * dp).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED,
            )
            defilement.layoutParams.height =
                conteneur.measuredHeight.coerceAtMost((HAUTEUR_MAX_LISTE_DP * dp).toInt())
        }
    }

    /** Popover « Supprimer » (§ 10.5) : confirmation avant action. */
    private fun montrerPopoverSupprimer(
        noeud: NoeudExplorateur,
        x: Int,
        y: Int,
    ) {
        popover.montrer(R.layout.popover_supprimer, x, y) { vue ->
            vue.findViewById<MaterialTextView>(R.id.message_supprimer).text =
                vue.context.getString(
                    if (noeud.estDossier) R.string.supprimer_dossier_message else R.string.supprimer_fichier_message,
                    noeud.nom,
                )
            vue.findViewById<View>(R.id.bouton_annuler_supprimer).setOnClickListener {
                popover.masquer()
            }
            vue.findViewById<View>(R.id.bouton_confirmer_supprimer).setOnClickListener {
                popover.masquer()
                viewModel.onAction(ActionEditor.SupprimerDocument(noeud.uri))
            }
        }
    }

    /** Popover « Légende » (§ 10.5) : pastilles figées + notation. */
    private fun montrerPopoverLegende(
        x: Int,
        y: Int,
    ) {
        popover.montrer(R.layout.popover_legende, x, y) { vue ->
            vue
                .findViewById<jo.codeide.feature.editor.VuePointEtat>(R.id.legende_point_defaut)
                .programmer(jo.codeide.feature.editor.VuePointEtat.EtatPoint.DEFAUT)
            vue
                .findViewById<jo.codeide.feature.editor.VuePointEtat>(R.id.legende_point_ouvert)
                .programmer(jo.codeide.feature.editor.VuePointEtat.EtatPoint.OUVERT)
            vue
                .findViewById<jo.codeide.feature.editor.VuePointEtat>(R.id.legende_point_actif)
                .programmer(jo.codeide.feature.editor.VuePointEtat.EtatPoint.ACTIF)
            vue
                .findViewById<jo.codeide.feature.editor.VuePointEtat>(R.id.legende_point_selectionne)
                .programmer(jo.codeide.feature.editor.VuePointEtat.EtatPoint.SELECTIONNE)
        }
    }

    /** Popover « Nouveau » de l'entête (§ 10.5) : ancré sous le bouton,
     *  contexte « Créer dans — dossier », deux actions. */
    private fun montrerPopoverNouveau(
        uriDossier: String,
        x: Int,
        y: Int,
    ) {
        val etat = viewModel.etat.value
        val noeud = etat.noeuds.find { it.uri == uriDossier }
        popover.montrer(R.layout.popover_actions_noeud, x, y) { vue ->
            vue.findViewById<android.widget.ImageView>(R.id.icone_tete_popover)?.setImageResource(
                jo.codeide.core.ui.IconesFichiers
                    .pourDossier(noeud?.prive == true),
            )
            vue.findViewById<MaterialTextView>(R.id.nom_tete_popover)?.text =
                noeud?.nom ?: getString(R.string.editor_explorateur_titre)
            vue.findViewById<MaterialTextView>(R.id.chemin_tete_popover)?.text = uriDossier
            val contexte = vue.context
            vue.findViewById<MaterialTextView>(R.id.contexte_popover)?.apply {
                isVisible = true
                text =
                    contexte.getString(
                        R.string.popover_contexte_creation,
                        noeud?.let { cheminRelatif(it, etat) } ?: "",
                    )
            }
            val conteneur = vue.findViewById<LinearLayout>(R.id.actions_popover)

            fun action(
                libelle: String,
                icone: Int,
                estDossier: Boolean,
            ) {
                val ligne =
                    LayoutInflater.from(contexte).inflate(R.layout.ligne_action_popover, conteneur, false)
                ligne.findViewById<android.widget.ImageView>(R.id.icone_action_popover).setImageResource(icone)
                ligne.findViewById<MaterialTextView>(R.id.libelle_action_popover).text = libelle
                ligne.setOnClickListener {
                    popover.masquer()
                    viewModel.onAction(ActionEditor.DebuterCreation(uriDossier, estDossier))
                }
                conteneur.addView(ligne)
            }

            action(
                getString(R.string.popover_nouveau_fichier),
                jo.codeide.core.ui.R.drawable.ic_fichier,
                false,
            )
            action(
                getString(R.string.popover_nouveau_dossier),
                jo.codeide.core.ui.R.drawable.ic_dossier,
                true,
            )
        }
    }

    // ------------------------------------------------------------------
    // Chemins (affichage tête du popover, contexte)
    // ------------------------------------------------------------------

    /** Chemin absolu affiché dans la tête du popover : racine + chemin
     *  relatif du nœud. */
    private fun cheminAbsolu(noeud: NoeudExplorateur): String {
        val racine = liaison.sousTitreExplorateur.text.toString()
        val relatif = cheminRelatif(noeud, viewModel.etat.value)
        return if (relatif.isBlank()) racine else "$racine/$relatif"
    }

    /** Chemin relatif du nœud dans l'arbre courant ("" pour la racine) :
     *  recalculé depuis la liste aplatie — le parent d'une ligne est le
     *  dossier **le plus proche avant elle** de profondeur moindre. */
    private fun cheminRelatif(
        noeud: NoeudExplorateur,
        etat: EtatEditor,
    ): String {
        if (noeud.estRacine) return ""
        val index = etat.noeuds.indexOf(noeud)
        if (index < 0) return noeud.nom
        val segments = mutableListOf(noeud.nom)
        var profondeur = noeud.profondeur
        var position = index
        while (profondeur > 1) {
            position--
            val parent =
                etat.noeuds
                    .take(position + 1)
                    .findLast { it.estDossier && it.profondeur == profondeur - 1 }
                    ?: break
            segments.add(0, parent.nom)
            profondeur = parent.profondeur
            position = etat.noeuds.indexOf(parent)
        }
        return segments.joinToString("/")
    }

    /** Chemin relatif du PARENT du nœud (valeur initiale du champ
     *  Destination du popover Déplacer, § 10.5). */
    private fun cheminParentRelatif(
        noeud: NoeudExplorateur,
        etat: EtatEditor,
    ): String {
        if (noeud.estRacine) return ""
        val index = etat.noeuds.indexOf(noeud)
        if (index < 0) return ""
        val parent =
            etat.noeuds
                .take(index)
                .findLast { it.estDossier && it.profondeur == noeud.profondeur - 1 }
                ?: return ""
        return cheminRelatif(parent, etat)
    }
}
