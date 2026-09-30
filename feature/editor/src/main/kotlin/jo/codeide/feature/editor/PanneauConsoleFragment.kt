package jo.codeide.feature.editor

import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.ui.ThemeHarmonizer
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentPanneauConsoleBinding
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Onglet Sortie du panneau inférieur (G5 §6, v0.32.4 ADR 0055 ; v3 :
 * tâches au fil du build, étapes de sync, bouton de configuration ; v4,
 * §3.3 : CHIPS Sync/Build — l'arbre d'étapes en vue Sync, les tâches et
 * leur synthèse en vue Build ; v5, aperçu : les deux vues sont EXCLUSIVES
 * et l'une est TOUJOURS active (la chronologie brute — sorties stdout/stderr
 * en liste plate — n'est plus un écran) — bouton Tâches armé par le cache
 * de la sync, BANDEAU d'échec avec « Voir les problèmes » et « Réessayer »,
 * annulation visible en vol, auto-défilement (le suivi s'arrête quand la
 * liste cesse de grandir — un build fini ne défile plus) ; **v0.40.1
 * (prompt de suivi §3) : UN SEUL chip d'action reflète l'action Gradle
 * courante (Sync / Build / Tâches / Classpaths…), NON cliquable — plus
 * de bascule utilisateur. La console montre toujours l'action courante
 * (ou la dernière exécutée). Aucun chip s'il n'y a eu aucune action.**
 *
 * **v0.42.0 (phase 1 du roadmap — performance console) : console HYBRIDE
 * comme Android Studio.** Le corps se scinde en deux zones empilées : la
 * zone STRUCTURÉE (RecyclerView : arbre d'étapes, tâches, synthèse —
 * DiffUtil sur peu de rangées) et la zone TEXTE (TextView monospace
 * scrollable : lignes stdout/stderr brutes). Les lignes brutes arrivent
 * par [EditorViewModel.lignesBrutesConsole] — un flux dédié, JAMAIS dans
 * l'état — et s'appliquent par `append()` direct O(1) par ligne, LOTIES
 * par trame (un seul `append` et une seule passe de layout par trame).
 * L'abonnement rejoue l'historique borné du service puis suit le direct ;
 * il vit sur le `viewLifecycleOwner` (mourir avec la vue = se ré-abonner
 * = reconstruire depuis le rejeu ; survivre à un onStop = ne PAS rejouer
 * deux fois). Un build de 725 ms s'affiche en moins d'une seconde, plus
 * en 2 minutes.
 *
 * Le contenu migre du layout empilé de l'activité (v0.32.3) vers ce
 * fragment ; l'activité ne collecte plus l'état tooling — chaque fragment
 * collecte ce qu'il rend.
 *
 * L'action courante (v0.40.1) vit dans le [EditorViewModel] — un build
 * démarre bascule vers BUILD automatiquement, une sync démarre bascule
 * vers SYNC. Plus de `BasculerFiltreConsole` — le chip est en lecture
 * seule. Le fragment LIT l'action depuis l'état et publie le chip.
 *
 * Exemption detekt ciblée (même précédent que `TerminalTiroirFragment` et
 * `InstallFragment`) : TooManyFunctions — un fragment de panneau est un
 * CONTRAT de câblage (cycle de vie + chip + bandeau + configuration
 * intégrée), chaque fonction a son écouteur ou son rappel.
 */
@Suppress("TooManyFunctions")
class PanneauConsoleFragment : Fragment() {
    private var liaisonAmorce: FragmentPanneauConsoleBinding? = null

    /** Liaison de la vue courante (correctif n°9 : plus de `!!` — un accès
     *  après destruction de la vue échoue avec un diagnostic lisible). */
    private val liaison
        get() =
            checkNotNull(liaisonAmorce) {
                "liaison du panneau Console indisponible — vue détruite ?"
            }

    /** ViewModel de l'espace de travail (porté par l'activité). */
    private val viewModel: EditorViewModel by activityViewModels()

    /** Console du tooling (arbre / lignes / synthèse selon l'action). */
    private lateinit var adaptateur: ConsoleToolingAdapter

    /**
     * Action courante (v0.40.1, prompt de suivi §3) : la vérité vit dans
     * [EditorViewModel] via `EtatEditor.filtreConsole` — le fragment ne
     * fait que LIRE l'état pour publier le chip et la vue correspondante.
     * Plus de bascule utilisateur — le chip est NON cliquable. La
     * sauvegarde locale [CLE_ACTION_COURANTE] reste pour la continuité
     * de cycle entre l'instance du fragment et le ViewModel (re-création
     * du ViewModel avant le onCreateView).
     */
    private var action: FiltreCanalConsole = FiltreCanalConsole.SYNC

    /** Taille de la dernière fenêtre rendue (auto-défilement). */
    private var tailleDerniereFenetre = 0

    // ---- Zone texte (v0.42.0, phase 1 — lignes brutes par append) ------

    /** Lignes stylées en attente du prochain vidage (LOTIES par trame). */
    private val lignesEnAttente = mutableListOf<CharSequence>()

    /** Un `Vider` est en attente — le vidage posera un tampon vierge. */
    private var viderEnAttente = false

    /** Un vidage est déjà planifié (déduplication des posts). */
    private var vidagePlanifie = false

    /** Un défilement vers le bas est déjà planifié (déduplication). */
    private var defilementPlanifie = false

    /** Tolérance « l'utilisateur est en bas » (une ligne ≈ 16 dp, en px). */
    private var toleranceBasPx = 0

    /** Nombre de rangées structurées du dernier rendu (état vide honnête). */
    private var nbRangeesDernierRendu = 0

    /** Retour système pendant l'affichage de la configuration (§3.3) : la
     *  referme avant de remonter au retour de l'espace. */
    private lateinit var retourConfiguration: OnBackPressedCallback

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FragmentPanneauConsoleBinding.inflate(inflateur, conteneur, false)
        // v0.40.1 : on AMORCE avec la sauvegarde locale du fragment (survit
        // à la rotation du fragment sans ViewModel), l'observation de
        // `viewModel.etat` ré-appliquera la vérité du ViewModel juste
        // après.
        action =
            etat
                ?.getString(CLE_ACTION_COURANTE)
                ?.let { nom -> FiltreCanalConsole.entries.firstOrNull { it.name == nom } }
                ?: viewModel.etat.value.filtreConsole
        return liaison.root
    }

    override fun onViewCreated(
        vue: View,
        etat: Bundle?,
    ) {
        adaptateur = ConsoleToolingAdapter()
        liaison.listeSortie.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeSortie.adapter = adaptateur
        // v0.40.1 (correctif n°5 du prompt de suivi) : pas d'animation de
        // changement sur les rangées de la console — un tick de durée qui
        // modifie le texte d'une étape ou d'une tâche ne doit pas clignoter.
        // `DefaultItemAnimator.supportsChangeAnimations = false` désactive
        // l'animation sans casser le DiffUtil (les payloads font la mise à
        // jour en place via `onBindViewHolder(holder, position, payloads)`).
        (liaison.listeSortie.itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)?.apply {
            supportsChangeAnimations = false
            // Les animations d'ajout (rangée qui apparaît) restent utiles
            // pour le défilement en bas — on ne désactive QUE les
            // changements (qui clignotaient à chaque tick de durée).
            changeDuration = 0
        }
        liaison.boutonAnnulerBuild.setOnClickListener {
            viewModel.onAction(ActionEditor.AnnulerBuild)
        }
        brancherActionsEchec()
        // Sélecteur de tâches (§3.3) : armé par le cache de la sync (aucun
        // aller-retour), le bouton reste honnête tant qu'il est éteint
        // (info-bulle « disponible après la synchronisation »).
        liaison.boutonTachesSortie.setOnClickListener {
            viewModel.onAction(ActionEditor.OuvrirSelecteurTaches)
        }
        // Configuration du tooling (v4, §3.3) : la page s'affiche DANS le
        // conteneur de la console (fragment enfant, flèche retour) —
        // réglages persistés à l'instant + état vivant de l'orchestrateur,
        // sans quitter l'espace de travail.
        liaison.boutonConfigTooling.setOnClickListener { ouvrirConfiguration() }

        // Retour système : referme la configuration AVANT de remonter à
        // l'espace (priorité LIFO sur le retour de l'activité).
        retourConfiguration =
            object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    fermerConfiguration()
                }
            }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            retourConfiguration,
        )

        // v0.40.1 : l'action courante vit dans le ViewModel — on l'observe
        // pour que le chip se mette à jour quand un build ou une sync
        // démarre (la bascule est poussée par `executerTachesGradle` /
        // `synchroniserProjetGradle`). Plus de bascule utilisateur — le
        // chip est NON cliquable.
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            if (action != etat.filtreConsole) {
                action = etat.filtreConsole
                tailleDerniereFenetre = 0
                rendre(viewModel.etatGradle.value)
            }
        }
        // v0.41.1 : champ de saisie stdin — visible pendant un build
        // EN_COURS. L'utilisateur tape, appuie sur Entrée, le texte est
        // envoyé au serveur via BuildInput (readln, Scanner(System.in)).
        liaison.champEntreeConsole.setEndIconOnClickListener {
            envoyerEntreeConsole()
        }
        liaison.editEntreeConsole.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                envoyerEntreeConsole()
                true
            } else {
                false
            }
        }

        // v0.42.0 (phase 1) : zone TEXTE — le flux dédié des lignes brutes.
        // Le tampon est posé VIERGE puis l'abonnement rejoue l'historique
        // borné du service (reconstruction exacte) avant de suivre le
        // direct — chaque événement s'accumule, le vidage LOTI par trame
        // applique. La collecte vit sur le `viewLifecycleOwner` : elle
        // survit à un onStop (un onglet du panneau ne doit PAS rejouer
        // l'historique à son retour — doublement) et meurt avec la vue
        // (une rotation se ré-abonne et reconstruit depuis le rejeu).
        toleranceBasPx = (TOLERANCE_BAS_DP * resources.displayMetrics.density).toInt()
        poserTamponVierge()
        viewModel.lignesBrutesConsole
            .onEach { evenement -> accumuler(evenement) }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.etatGradle.collectWithLifecycle(viewLifecycleOwner) { rendre(it) }
    }

    override fun onSaveInstanceState(etat: Bundle) {
        super.onSaveInstanceState(etat)
        etat.putString(CLE_ACTION_COURANTE, action.name)
    }

    override fun onDestroyView() {
        // v0.42.0 : le lot en attente appartient à la vue morte — la
        // reconstruction repassera par le rejeu, le garder doublerait.
        viderEnAttente = false
        vidagePlanifie = false
        defilementPlanifie = false
        lignesEnAttente.clear()
        liaisonAmorce = null
        super.onDestroyView()
    }

    /** v0.41.1 : envoie le texte du champ de saisie au build en cours (stdin). */
    private fun envoyerEntreeConsole() {
        val texte = liaison.editEntreeConsole.text?.toString() ?: return
        if (texte.isBlank()) return
        viewModel.onAction(ActionEditor.EnvoyerEntreeConsole(texte))
        liaison.editEntreeConsole.text?.clear()
    }

    /** Bandeau d'échec (§3.3) : « Voir les problèmes » (onglet dédié) et
     *  « Réessayer » (relance des MÊMES tâches) — plus d'échec muet. */
    private fun brancherActionsEchec() {
        liaison.boutonVoirProblemes.setOnClickListener {
            viewModel.onAction(ActionEditor.SelectionnerOngletPanneau(OngletPanneau.PROBLEMES))
        }
        liaison.boutonReessayerBuild.setOnClickListener {
            val taches = viewModel.etatGradle.value.taches
            if (taches.isNotEmpty()) {
                viewModel.onAction(ActionEditor.ExecuterTaches(taches))
            }
        }
    }

    /** Rend le statut (balisé de SON canal — v0.32.5), l'annulation, le
     *  bouton Tâches, le bandeau d'échec (message du serveur sinon
     *  libellé générique, « Réessayer » seulement si des tâches existent),
     *  le chip d'action courante (v0.40.1 §3), la console structurée
     *  (fenêtre bornée, arbre en vue Sync), la VISIBILITÉ de la zone
     *  texte (v0.42.0 : vue Build uniquement — la vue Sync reste l'arbre
     *  seul de l'aperçu v5) et l'état vide (aucune rangée ET aucune
     *  ligne brute). */
    private fun rendre(etat: EtatGradle) {
        liaison.statutSortie.text =
            PresentationTooling.libelleStatut(etat).resoudre(requireContext())
        baliserCanalStatut(etat)
        liaison.boutonAnnulerBuild.isVisible = etat.statutBuild == StatutBuild.EN_COURS

        // Tâches : armé dès que la sync a REMPLI le cache (v4) — v0.39.1
        // (correctif n°2) : `tachesDisponibles` vaut désormais `emptyList()`
        // après une sync réussie dont le listage a échoué (au lieu de
        // `null` silencieux) — le bouton s'active quand même, le clic
        // retente le listage via l'orchestrateur (cf. `ouvrirSelecteurTaches`).
        liaison.boutonTachesSortie.isEnabled = etat.tachesDisponibles != null

        val echoue = etat.statutBuild == StatutBuild.ECHOUE
        liaison.bandeauEchecBuild.isVisible = echoue
        if (echoue) {
            liaison.messageEchecBuild.text =
                etat.messageEchecBuild
                    ?: getString(R.string.editor_console_echec_defaut)
            liaison.boutonReessayerBuild.isVisible = etat.taches.isNotEmpty()
        }

        // v0.40.1 (§3) : chip d'action UNIQUE reflétant l'action courante.
        // Aucun chip s'il n'y a eu aucune action Gradle (état vierge).
        rendreChipAction(etat)

        // v0.41.1 : champ de saisie stdin visible pendant un build EN_COURS.
        liaison.champEntreeConsole.isVisible = etat.statutBuild == StatutBuild.EN_COURS

        // v0.42.0 (phase 1) : la zone texte n'existe qu'en vue BUILD —
        // la vue Sync reste l'arbre + son pied (aperçu v5), le plein
        // écran pour le RecyclerView.
        val zoneTexteVisible = action == FiltreCanalConsole.BUILD
        liaison.zoneTexteDefilement.isVisible = zoneTexteVisible
        liaison.separateurConsole.isVisible = zoneTexteVisible

        val rangees = construireRangeesConsole(etat, action)
        nbRangeesDernierRendu = rangees.size
        adaptateur.submitList(rangees)
        val enVol = etat.statutBuild == StatutBuild.EN_COURS || etat.synchronisationEnCours
        if (enVol && rangees.size > tailleDerniereFenetre && rangees.isNotEmpty()) {
            liaison.listeSortie.scrollToPosition(rangees.lastIndex)
        }
        tailleDerniereFenetre = rangees.size
        majEtatVide()
    }

    /** État vide honnête (v0.42.0) : aucune rangée structurée, et — en
     *  vue BUILD — aucune ligne brute dans la zone texte non plus ; la
     *  vue Sync s'appuie sur ses seules rangées (l'arbre absent parle). */
    private fun majEtatVide() {
        val texteBrutPresent = !liaison.texteBrutConsole.text.isNullOrEmpty()
        liaison.texteSortieVide.isVisible =
            nbRangeesDernierRendu == 0 &&
            (action == FiltreCanalConsole.SYNC || !texteBrutPresent)
    }

    // ---- Zone texte : accumulation, vidage loti, auto-défilement -------

    /**
     * Accumule UN événement de la zone texte (v0.42.0, phase 1) : une
     * ligne s'ajoute au lot en attente, un vidage arme le tampon vierge
     * (et jette le lot en cours — ces lignes appartenaient à l'ancienne
     * console). Le vidage est PLANIFIÉ : toutes les lignes arrivées dans
     * la même passe de la boucle de messages partent en UN seul `append`
     * — une seule notification de changement, une seule passe de layout
     * par trame.
     */
    private fun accumuler(evenement: EvenementConsoleTexte) {
        when (evenement) {
            is EvenementConsoleTexte.Vider -> {
                viderEnAttente = true
                lignesEnAttente.clear()
            }

            is EvenementConsoleTexte.Ligne -> {
                lignesEnAttente += ligneStylee(evenement)
            }
        }
        planifierVidage()
    }

    /** Planifie le vidage du lot (dédupliqué : un post vivant au plus). */
    private fun planifierVidage() {
        if (vidagePlanifie) return
        vidagePlanifie = true
        // Capture de la vue : un runnable exécuté après destruction de la
        // vue ne doit rien appliquer (les champs ont été réinitialisés).
        val vue = liaison
        vue.root.post {
            vidagePlanifie = false
            if (liaisonAmorce === vue) viderLot(vue)
        }
    }

    /**
     * Applique le lot accumulé (v0.42.0) : tampon vierge si un vidage est
     * armé, PUIS les lignes en un seul `append` (le `TextView` garde un
     * `Editable` : l'ajout est en place, O(longueur de la ligne), les
     * spans de couleurs voyagent avec). L'auto-défilement suit le bas si
     * l'utilisateur y ÉTAIT (l'intention de lecture est capturée AVANT
     * l'ajout) — un utilisateur remonté dans l'historique n'est jamais
     * rabattu en bas.
     */
    private fun viderLot(vue: FragmentPanneauConsoleBinding) {
        val suivreBas = estAuBas(vue)
        if (viderEnAttente) {
            viderEnAttente = false
            poserTamponVierge()
        }
        if (lignesEnAttente.isNotEmpty()) {
            val lot = SpannableStringBuilder()
            lignesEnAttente.forEach { lot.append(it) }
            lignesEnAttente.clear()
            vue.texteBrutConsole.append(lot)
            if (suivreBas) suivreLeBas(vue)
        }
        majEtatVide()
    }

    /** Pose un tampon `Editable` VIERGE — `append()` modifie en place. */
    private fun poserTamponVierge() {
        liaison.texteBrutConsole.text = Editable.Factory.getInstance().newEditable("")
    }

    /** L'utilisateur est-il au bas de la zone texte (tolérance : une ligne) ? */
    private fun estAuBas(vue: FragmentPanneauConsoleBinding): Boolean {
        val contenu = vue.zoneTexteDefilement.getChildAt(0) ?: return true
        return vue.zoneTexteDefilement.scrollY >=
            contenu.height - vue.zoneTexteDefilement.height - toleranceBasPx
    }

    /** Défile vers le bas (dédupliqué : un post vivant au plus). */
    private fun suivreLeBas(vue: FragmentPanneauConsoleBinding) {
        if (defilementPlanifie) return
        defilementPlanifie = true
        vue.zoneTexteDefilement.post {
            defilementPlanifie = false
            vue.zoneTexteDefilement.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * Construit la ligne stylée (v0.42.0) : le texte porte sa couleur —
     * stderr en rouge d'erreur, stdout (et l'avertissement bénin apaisé,
     * C5) en couleur de sortie atténuée pour l'apaisé. Le retour à la
     * ligne final fait qu'aucune ligne n'en chevauche une autre, et
     * l'auto-défilement « plein bas » repose sur un dernier retour
     * toujours présent.
     */
    private fun ligneStylee(ligne: EvenementConsoleTexte.Ligne): CharSequence {
        val contexte = requireContext()
        val couleurBrute =
            when {
                !ligne.apaisee && ligne.flux == FluxSortieBuild.STDERR -> {
                    androidx.core.content.ContextCompat.getColor(
                        contexte,
                        jo.codeide.core.ui.R.color.codeide_stderr,
                    )
                }

                else -> {
                    androidx.core.content.ContextCompat.getColor(
                        contexte,
                        jo.codeide.core.ui.R.color.codeide_stdout,
                    )
                }
            }
        val couleur =
            if (ligne.apaisee) {
                androidx.core.graphics.ColorUtils
                    .setAlphaComponent(couleurBrute, ALPHA_LIGNE_APAISEE)
            } else {
                couleurBrute
            }
        val texte = SpannableString(ligne.texte + "\n")
        texte.setSpan(
            ForegroundColorSpan(couleur),
            0,
            texte.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return texte
    }

    /**
     * Publie le chip d'action UNIQUE (v0.40.1, prompt de suivi §3) —
     * reflète l'action Gradle courante (Sync / Build / Tâches / Classpaths…).
     * NON cliquable. Aucun chip s'il n'y a eu aucune action (état vierge :
     * aucune sync annoncée, aucun build, aucune tâche).
     *
     * Couleur de canal harmonisée (cf. aperçu v3 §6 : bordure et texte
     * en couleur de canal harmonisée, fond teinté 12 %). Le chip n'a
     * PAS d'icône — l'icône vit dans l'en-tête du panneau (pastille de
     * canal, cf. PanneauToolingController).
     */
    private fun rendreChipAction(etat: EtatGradle) {
        val canal =
            when {
                etat.synchronisationEnCours || etat.synchronisationReussie != null ||
                    etat.messageEchecSync != null -> CanalTooling.SYNC

                etat.statutBuild != null -> CanalTooling.BUILD

                else -> null
            }
        if (canal == null) {
            // Aucune action Gradle n'a eu lieu — pas de chip.
            liaison.chipActionCourante.isVisible = false
            return
        }
        val libelle =
            when (canal) {
                CanalTooling.SYNC -> R.string.editor_tooling_canal_sync
                CanalTooling.BUILD -> R.string.editor_tooling_canal_build
                CanalTooling.TACHES -> R.string.editor_tooling_canal_taches
            }
        liaison.chipActionCourante.text = getString(libelle)
        val couleurHarmonisee = ThemeHarmonizer.harmoniserAvecPrimaire(requireContext(), canal.couleur)
        liaison.chipActionCourante.chipBackgroundColor =
            android.content.res.ColorStateList.valueOf(
                androidx.core.graphics.ColorUtils
                    .setAlphaComponent(couleurHarmonisee, ALPHA_FOND_TINTE_CHIP),
            )
        liaison.chipActionCourante.chipStrokeColor =
            android.content.res.ColorStateList
                .valueOf(couleurHarmonisee)
        liaison.chipActionCourante.setTextColor(couleurHarmonisee)
        liaison.chipActionCourante.isVisible = true
    }

    /** Canal du statut (v0.32.5, ADR 0056 décision 5) : l'icône signature
     *  de la provenance de l'information — Sync quand la ligne parle de
     *  synchronisation, Build quand elle parle du build ; éteinte quand
     *  la console est vide (aucune information, aucun canal). */
    private fun baliserCanalStatut(etat: EtatGradle) {
        val canal =
            when {
                etat.synchronisationEnCours || etat.synchronisationReussie != null ||
                    etat.messageEchecSync != null -> CanalTooling.SYNC

                etat.statutBuild != null -> CanalTooling.BUILD

                else -> null
            }
        liaison.iconeCanalSortie.isVisible = canal != null
        if (canal != null) {
            liaison.iconeCanalSortie.setImageResource(canal.icone)
            // Couleur de marque du canal : harmonisée avec le primaire du
            // thème courant (ADR 0059 — se rapproche du fond d'écran en
            // couleurs dynamiques).
            liaison.iconeCanalSortie.setColorFilter(
                ThemeHarmonizer.harmoniserAvecPrimaire(requireContext(), canal.couleur),
            )
        }
    }

    // ---- Configuration intégrée (§3.3) ----------------------------------

    /** Ouvre la page de configuration DANS le conteneur de la console
     *  (fragment enfant ajouté une fois, montré/caché ensuite — l'état de
     *  la page survit aux allers-retours, le DataStore reste la vérité). */
    private fun ouvrirConfiguration() {
        val gestionnaire = childFragmentManager
        val page =
            gestionnaire.findFragmentByTag(ETIQUETTE_CONFIG)
                ?: PanneauConfigToolingFragment().also { page ->
                    page.surFermeture = { fermerConfiguration() }
                    gestionnaire
                        .beginTransaction()
                        .add(R.id.conteneur_config_tooling, page, ETIQUETTE_CONFIG)
                        .commit()
                }
        gestionnaire.beginTransaction().show(page).commit()
        liaison.contenuConsole.isVisible = false
        retourConfiguration.isEnabled = true
    }

    /** Referme la configuration et rend la console (retour en tête ou
     *  retour système). */
    private fun fermerConfiguration() {
        childFragmentManager.findFragmentByTag(ETIQUETTE_CONFIG)?.let { page ->
            childFragmentManager.beginTransaction().hide(page).commit()
        }
        liaison.contenuConsole.isVisible = true
        retourConfiguration.isEnabled = false
    }

    private companion object {
        /** Étiquette de la page de configuration (anti-doublon). */
        const val ETIQUETTE_CONFIG = "config-tooling"

        /** Clé de l'action courante dans l'état d'instance (v0.40.1). */
        const val CLE_ACTION_COURANTE = "action-courante-console"

        /**
         * Alpha d'une ligne brute apaisée (v0.42.0 — avertissement bénin du
         * daemon, C5 : de l'information, pas du bruit ; même valeur que
         * l'ancien `LigneGradleHolder`).
         */
        const val ALPHA_LIGNE_APAISEE = 140

        /**
         * Tolérance « l'utilisateur est au bas » de la zone texte (v0.42.0)
         * : une LIGNE de marge en dp — un lecteur à une ligne du fond
         * reste considéré comme SUIVEUR du bas.
         */
        const val TOLERANCE_BAS_DP = 16f

        /**
         * Alpha du fond teinté du chip d'action (v0.40.1, prompt de suivi
         * §6 — `color-mix(in srgb, var(--c) 12%, transparent)` dans l'aperçu
         * v3). 12 % de 255 ≈ 31.
         */
        const val ALPHA_FOND_TINTE_CHIP = 31
    }
}
