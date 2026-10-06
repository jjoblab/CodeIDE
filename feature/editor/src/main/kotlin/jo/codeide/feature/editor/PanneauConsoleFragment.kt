package jo.codeide.feature.editor

import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.ui.ThemeHarmonizer
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentPanneauConsoleBinding
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Onglet Sortie du panneau inférieur (G5 §6, v0.32.4 ADR 0055 ; v4, §3.3 :
 * CHIPS Sync/Build ; v5, aperçu ; v0.40.1 : UN SEUL chip d'action reflète
 * l'action Gradle courante ; v0.42.0 : console HYBRIDE (zone structurée +
 * zone texte) ; **v0.46.0 — REFONTE TOTALE (ADR 0078) : CONSOLE FLUX BRUT
 * UNIQUE** — un `TextView` monospace PAR CANAL (Sync et Build), le flux
 * stdout/stderr de Gradle ENTIÈREMENT reconstitué comme la fenêtre Build
 * d'Android Studio : ses propres lignes « > Task :app:xxx », « BUILD
 * SUCCESSFUL in 6s », les diagnostics de compilation, plus les statuts de
 * l'orchestrateur (connexion au daemon, téléchargements, configuration) et
 * les étapes de sync (vue Sync). PLUS AUCUN RecyclerView, plus de DiffUtil,
 * plus de rangées structurées : l'information vit dans le flux ou dans
 * l'en-tête du panneau — jamais aux deux.
 *
 * Deux `TextView` (un par canal) s'accumulent EN PARALLÈLE : le chip
 * Sync/Build ne fait que choisir lequel est VISIBLE — basculer ne perd
 * rien, ne rejoue rien, ne reconstruit rien. L'application des lignes est
 * LOTIE PAR TRAME (un seul `append` par frame, O(1) par ligne — la leçon
 * de l'ADR 0074 reste : jamais de reconstruction, jamais de DiffUtil).
 *
 * Bouton Tâches armé par le cache de la sync, BANDEAU d'échec avec « Voir
 * les problèmes » et « Réessayer », annulation visible en vol,
 * auto-défilement honnête (le suivi s'arrête quand l'utilisateur remonte
 * — un build fini ne défile plus).
 *
 * Exemption detekt ciblée (même précédent que `TerminalTiroirFragment` et
 * l'écran d'installation) : TooManyFunctions — un fragment de panneau est un
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

    // ---- Console flux brut (v0.46.0, ADR 0078) --------------------------

    /** Événements en attente du prochain vidage (LOTIS par trame). */
    private val evenementsEnAttente = mutableListOf<EvenementConsoleTexte>()

    /** Un vidage est déjà planifié (déduplication des posts). */
    private var vidagePlanifie = false

    /** Un défilement vers le bas est déjà planifié (déduplication). */
    private var defilementPlanifie = false

    /** Tolérance « l'utilisateur est au bas » (une ligne ≈ 16 dp, en px). */
    private var toleranceBasPx = 0

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

        // v0.46.0 (ADR 0078) : les DEUX consoles sont posées VIERGES puis
        // l'abonnement rejoue l'historique borné du service (reconstruction
        // exacte, canal par canal) avant de suivre le direct — chaque
        // événement s'accumule, le vidage LOTI PAR TRAME applique. La
        // collecte vit sur le `viewLifecycleOwner` : elle survit à un
        // onStop (un onglet du panneau ne doit PAS rejouer l'historique à
        // son retour — doublement) et meurt avec la vue (une rotation se
        // ré-abonne et reconstruit depuis le rejeu).
        toleranceBasPx = (TOLERANCE_BAS_DP * resources.displayMetrics.density).toInt()
        poserTamponVierge(liaison.texteConsoleSync)
        poserTamponVierge(liaison.texteConsoleBuild)
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
        // v0.46.0 : le lot en attente appartient à la vue morte — la
        // reconstruction repassera par le rejeu, le garder doublerait.
        vidagePlanifie = false
        defilementPlanifie = false
        evenementsEnAttente.clear()
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

    /**
     * Rend le statut (balisé de SON canal — v0.32.5), l'annulation, le
     * bouton Tâches, le bandeau d'échec (message du serveur sinon
     * libellé générique, « Réessayer » seulement si des tâches existent),
     * le chip d'action courante (v0.40.1 §3) et la VISIBILITÉ de la
     * console du canal courant (v0.46.0 : deux `TextView`, le chip
     * choisit — plus de RecyclerView, plus de rangées à reconstruire).
     */
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

        // v0.46.0 : la console du canal courant est la SEULE visible —
        // l'autre continue de s'accumuler en coulisses, basculer ne perd
        // rien.
        majVisibiliteConsoles()
    }

    /** Visibilité des deux consoles selon l'action courante + état vide. */
    private fun majVisibiliteConsoles() {
        val vueSync = action == FiltreCanalConsole.SYNC
        liaison.defilementConsoleSync.isVisible = vueSync
        liaison.defilementConsoleBuild.isVisible = !vueSync
        majEtatVide()
    }

    /** État vide honnête (v0.46.0) : la console du canal COURANT n'a
     *  encore rien écrit — ni flux, ni étapes. */
    private fun majEtatVide() {
        val texteCourant =
            if (action == FiltreCanalConsole.SYNC) {
                liaison.texteConsoleSync.text
            } else {
                liaison.texteConsoleBuild.text
            }
        liaison.texteSortieVide.isVisible = texteCourant.isNullOrEmpty()
    }

    // ---- Console : accumulation, vidage loti, auto-défilement ----------

    /**
     * Accumule UN événement de la console (v0.46.0) : la ligne rejoint le
     * lot en attente, le vidage d'un canal arme son tampon vierge (et
     * jette les lignes du lot qui lui appartiennent — elles appartenaient
     * à l'ancienne console). Le vidage est PLANIFIÉ : tous les événements
     * arrivés dans la même passe de la boucle de messages partent en UN
     * seul lot — une seule notification de changement, une seule passe de
     * layout par trame et par canal.
     */
    private fun accumuler(evenement: EvenementConsoleTexte) {
        if (evenement is EvenementConsoleTexte.Vider) {
            evenementsEnAttente.removeAll { it is EvenementConsoleTexte.Vider && it.canal == evenement.canal }
            evenementsEnAttente.removeAll { it is EvenementConsoleTexte.Ligne && it.canal == evenement.canal }
            evenementsEnAttente += evenement
        } else {
            evenementsEnAttente += evenement
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
     * Applique le lot accumulé (v0.46.0) : chaque événement va à SA
     * console (Sync ou Build — les deux s'accumulent, seule la visible se
     * défile), tampon vierge si un vidage est armé, PUIS les lignes en un
     * seul `append` par console (le `TextView` garde un `Editable` :
     * l'ajout est en place, O(longueur de la ligne), les spans de couleurs
     * voyagent avec). L'auto-défilement suit le bas si l'utilisateur y
     * ÉTAIT (l'intention de lecture est capturée AVANT l'ajout) — un
     * utilisateur remonté dans l'historique n'est jamais rabattu en bas.
     */
    private fun viderLot(vue: FragmentPanneauConsoleBinding) {
        if (evenementsEnAttente.isEmpty()) {
            majEtatVide()
            return
        }
        val suivreBasSync = action == FiltreCanalConsole.SYNC && estAuBas(vue.defilementConsoleSync)
        val suivreBasBuild = action == FiltreCanalConsole.BUILD && estAuBas(vue.defilementConsoleBuild)
        val vidageSync = evenementsEnAttente.any { it is EvenementConsoleTexte.Vider && it.canal == CanalTooling.SYNC }
        val vidageBuild =
            evenementsEnAttente.any {
                it is EvenementConsoleTexte.Vider && it.canal == CanalTooling.BUILD
            }
        val lotSync = lotDuCanal(CanalTooling.SYNC)
        val lotBuild = lotDuCanal(CanalTooling.BUILD)
        evenementsEnAttente.clear()
        if (vidageSync) poserTamponVierge(vue.texteConsoleSync)
        if (vidageBuild) poserTamponVierge(vue.texteConsoleBuild)
        if (lotSync.isNotEmpty()) {
            vue.texteConsoleSync.append(lotSync)
            if (suivreBasSync) suivreLeBas(vue.defilementConsoleSync)
        }
        if (lotBuild.isNotEmpty()) {
            vue.texteConsoleBuild.append(lotBuild)
            if (suivreBasBuild) suivreLeBas(vue.defilementConsoleBuild)
        }
        majEtatVide()
    }

    /** Stylise et accumule les LIGNES du lot en attente pour un canal. */
    private fun lotDuCanal(canal: CanalTooling): SpannableStringBuilder {
        val lot = SpannableStringBuilder()
        evenementsEnAttente
            .filterIsInstance<EvenementConsoleTexte.Ligne>()
            .filter { ligne -> ligne.canal == canal }
            .forEach { ligne -> lot.append(ligneStylee(ligne)) }
        return lot
    }

    /** Pose un tampon `Editable` VIERGE — `append()` modifie en place. */
    private fun poserTamponVierge(vue: com.google.android.material.textview.MaterialTextView) {
        vue.text = Editable.Factory.getInstance().newEditable("")
    }

    /** L'utilisateur est-il au bas de la console donnée (tolérance : une ligne) ? */
    private fun estAuBas(defilement: androidx.core.widget.NestedScrollView): Boolean {
        val contenu = defilement.getChildAt(0) ?: return true
        return defilement.scrollY >= contenu.height - defilement.height - toleranceBasPx
    }

    /** Défile vers le bas (dédupliqué : un post vivant au plus). */
    private fun suivreLeBas(defilement: androidx.core.widget.NestedScrollView) {
        if (defilementPlanifie) return
        defilementPlanifie = true
        defilement.post {
            defilementPlanifie = false
            defilement.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * Construit la ligne stylée (v0.46.0) : le libellé se résout (une
     * ressource, un brut ou une composition) et porte SA couleur — stderr
     * en rouge d'erreur, stdout en couleur de sortie (l'avertissement
     * bénin apaisé, C5), étapes et synthèses en couleur de surface. Le
     * retour à la ligne final fait qu'aucune ligne n'en chevauche une
     * autre, et l'auto-défilement « plein bas » repose sur un dernier
     * retour toujours présent.
     */
    private fun ligneStylee(ligne: EvenementConsoleTexte.Ligne): CharSequence {
        val contexte = requireContext()
        val couleur =
            when (ligne.style) {
                StyleLigne.ERREUR -> {
                    androidx.core.content.ContextCompat.getColor(
                        contexte,
                        jo.codeide.core.ui.R.color.codeide_stderr,
                    )
                }

                StyleLigne.APAISEE -> {
                    androidx.core.graphics.ColorUtils.setAlphaComponent(
                        androidx.core.content.ContextCompat.getColor(
                            contexte,
                            jo.codeide.core.ui.R.color.codeide_stdout,
                        ),
                        ALPHA_LIGNE_APAISEE,
                    )
                }

                StyleLigne.SORTIE, StyleLigne.TELECHARGEMENT -> {
                    androidx.core.content.ContextCompat.getColor(
                        contexte,
                        jo.codeide.core.ui.R.color.codeide_stdout,
                    )
                }

                StyleLigne.ETAPE, StyleLigne.SYNTHESE -> {
                    com.google.android.material.color.MaterialColors.getColor(
                        liaison.root,
                        com.google.android.material.R.attr.colorOnSurface,
                    )
                }
            }
        val texte = SpannableString(ligne.libelle.resoudre(contexte) + "\n")
        texte.setSpan(
            ForegroundColorSpan(couleur),
            0,
            texte.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (ligne.style == StyleLigne.SYNTHESE) {
            // La conclusion de sync se détache du flux qui la précède —
            // grasse, comme le verdict d'une fenêtre Build.
            texte.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                0,
                texte.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
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
         * Tolérance « l'utilisateur est au bas » de la console (v0.42.0)
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
