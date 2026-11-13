package jo.codeide.feature.editor

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.FiltreSorties
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.ui.ThemeHarmonizer
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentPanneauConsoleBinding
import jo.codeide.feature.sorties.FamilleSorties
import jo.codeide.feature.sorties.FragmentLecteurSorties
import javax.inject.Inject

/**
 * Onglet Sortie du panneau inférieur (G5 §6, v0.32.4 ADR 0055 ; v4, §3.3 :
 * CHIPS Sync/Build) — **L5b (spec SORTIES § 5.7) : PANNEAU COMPACT
 * REBRANCHÉ** — il N'EMBARQUE PLUS de moteur d'affichage à lui (les deux
 * consoles `TextView` et leur vidage loti ADR 0078 sont SUPPRIMÉS) : le
 * corps est une FENÊTRE sur le MÊME composant, le lecteur du Centre de
 * sorties (famille GRADLE, fragment enfant `FragmentLecteurSorties`).
 * Un lecteur qui s'attache ne voit jamais deux fois la même ligne
 * (cliché de publication, lot L3) ; l'onglet ne rejoue rien.
 *
 * Le chip de canal (Sync | Build — INC-20) pousse son filtre PAR SOURCE
 * au lecteur (`FiltreSorties.sources` : SYNC → [SourceSortie.SYNC], BUILD
 * → [SourceSortie.BUILD]) — le tampon ne bouge pas, les lignes masquées
 * sont comptées et annoncées par le lecteur ; basculer ne perd rien, ne
 * rejoue rien. La recherche, la pause, l'effacement, le regroupé et la
 * consultation vivent DANS le lecteur (jamais un second chrome).
 *
 * Le chrome d'ACTIONS du panneau est conservé à l'identique : bouton
 * Tâches (armé par le cache de la sync), configuration intégrée (page
 * enfant, flèche retour), « Expliquer » IA (dernières rangées VISIBLES
 * du lecteur), bandeau d'échec PAR CANAL (« Voir les problèmes » +
 * « Réessayer »), annulation en vol, saisie stdin pendant un build
 * (v0.41.1), point d'actualité du canal non affiché (INC-20).
 *
 * Sonde T6 (ADR 0152) conservée : la cadence d'images du fil principal
 * pendant que la vue est attachée. La mesure T5 du vidage loti s'éteint
 * AVEC le moteur qu'elle chronométrait (suppression justifiée, le
 * lecteur a ses propres budgets et ses propres mesures).
 *
 * Exemption detekt ciblée (même précédent que `TerminalTiroirFragment` et
 * l'écran d'installation) : TooManyFunctions — un fragment de panneau est un
 * CONTRAT de câblage (cycle de vie + chip + bandeau + configuration
 * intégrée), chaque fonction a son écouteur ou son rappel.
 */
@Suppress("TooManyFunctions")
@AndroidEntryPoint
class PanneauConsoleFragment : Fragment() {
    private var liaisonAmorce: FragmentPanneauConsoleBinding? = null

    /** Journal applicatif (Phase 1, ADR 0152 : sonde T5/T6 de la vue). */
    @Inject
    lateinit var journal: AppLogger

    /** Réglages (Phase 1 : le drapeau d'instrumentation arme la sonde de vue). */
    @Inject
    lateinit var observerReglages: ObserveSettingsUseCase

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
     * Console affichée (v0.40.1 : pilotée par le ViewModel via
     * `EtatEditor.filtreConsole` ; INC-20, volet C : le sélecteur de
     * la barre d'outils est redevenu CLIQUABLE — Sync | Build). La vérité
     * vit dans [EditorViewModel] : l'action qui démarre pousse son canal,
     * le clic utilisateur choisit l'autre console en pleine activité et
     * persiste jusqu'à la prochaine action. La sauvegarde locale
     * [CLE_ACTION_COURANTE] reste pour la continuité de cycle entre
     * l'instance du fragment et le ViewModel (re-création du ViewModel
     * avant le onCreateView). L5b : le canal pousse AUSSI son filtre par
     * source au lecteur (voir [pousserFiltreAuLecteur]).
     */
    private var action: FiltreCanalConsole = FiltreCanalConsole.SYNC

    /** L5b : dernier canal dont le filtre a été poussé au lecteur (garde
     *  d'idempotence — une émission d'état Gradle ne repousse pas). */
    private var canalPousse: FiltreCanalConsole? = null

    /** Retour système pendant l'affichage de la configuration (§3.3) : la
     *  referme avant de remonter au retour de l'espace. */
    private lateinit var retourConfiguration: OnBackPressedCallback

    /** Le lecteur hébergé (fenêtre sur le composant — L5b) ou `null`
     *  s'il n'est pas encore attaché. */
    private val lecteur: FragmentLecteurSorties?
        get() = childFragmentManager.findFragmentById(R.id.conteneur_lecteur_panneau) as? FragmentLecteurSorties

    // ---- Phase 1 (ADR 0152) : sonde T6 de la vue ---------------------

    /** Sonde de la vue (T6 images) — armée par le réglage
     *  `instrumentationTransport`, coupée par défaut (règle 10). */
    private val sondeVue by lazy { SondeConsoleVue(journal) }

    /** La boucle d'images du Choreographer est-elle posée ? */
    private var imagesSuivies = false

    /** Rappel d'image (T6) — se retire de lui-même dès que la sonde est coupée. */
    private val rappelImages =
        object : Choreographer.FrameCallback {
            override fun doFrame(tempsNs: Long) {
                if (!imagesSuivies) return
                sondeVue.image(tempsNs / NANOS_PAR_MILLI)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

    override fun onCreateView(inflateur: LayoutInflater, conteneur: ViewGroup?, etat: Bundle?): View {
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

    override fun onViewCreated(vue: View, etat: Bundle?) {
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

        // L5b (spec SORTIES § 5.7) : le corps est le LECTEUR du Centre de
        // sorties (famille GRADLE) — ajouté UNE fois (commitNow : le
        // filtre du canal courant part TOUT DE SUITE, jamais une fenêtre
        // « tous les canaux » par défaillance d'ordonnancement) ; un
        // lecteur qui s'attache ne voit jamais deux fois la même ligne
        // (cliché qui suit la PUBLICATION, lot L3).
        if (childFragmentManager.findFragmentById(R.id.conteneur_lecteur_panneau) == null) {
            childFragmentManager
                .beginTransaction()
                .add(
                    R.id.conteneur_lecteur_panneau,
                    FragmentLecteurSorties.nouvelle(FamilleSorties.GRADLE),
                    ETIQUETTE_LECTEUR,
                )
                .commitNow()
        }
        pousserFiltreAuLecteur()

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            if (action != etat.filtreConsole) {
                action = etat.filtreConsole
                rendre(viewModel.etatGradle.value)
            }
        }
        // INC-20 : sélecteur segmenté Sync | Build — le clic choisit la
        // console affichée (les deux s'accumulent, basculer ne perd rien).
        liaison.chipCanalSync.setOnClickListener {
            viewModel.onAction(ActionEditor.SelectionnerCanalConsole(FiltreCanalConsole.SYNC))
        }
        liaison.chipCanalBuild.setOnClickListener {
            viewModel.onAction(ActionEditor.SelectionnerCanalConsole(FiltreCanalConsole.BUILD))
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

        // L5b : le lecteur est déjà alimenté (il s'attache au flux du
        // Centre LUI-MÊME — aucun abonnement console ici, plus de rejeu
        // canal par canal) ; le rendu du chrome (statut, bandeau, chips)
        // reste piloté par l'état Gradle.
        viewModel.etatGradle.collectWithLifecycle(viewLifecycleOwner) { rendre(it) }

        // Phase 1 (ADR 0152) : le drapeau d'instrumentation arme la sonde
        // de vue (T6 : images du fil principal) — réglage persisté, lu à
        // l'attache de la vue et suivi en direct : coupé, la vue ne paie
        // RIEN (aucune mesure, aucune ligne).
        observerReglages()
            .collectWithLifecycle(viewLifecycleOwner) { reglages ->
                armerSondeVue(reglages.instrumentationTransport)
            }
    }

    override fun onSaveInstanceState(etat: Bundle) {
        super.onSaveInstanceState(etat)
        etat.putString(CLE_ACTION_COURANTE, action.name)
    }

    override fun onDestroyView() {
        // Phase 1 : la boucle d'images meurt avec la vue (un rappel déjà
        // en vol s'arrête de lui-même au drapeau).
        imagesSuivies = false
        sondeVue.active = false
        liaisonAmorce = null
        super.onDestroyView()
    }

    /**
     * Arme ou coupe la sonde de vue (Phase 1, ADR 0152) — idempotent :
     * armer deux fois ne pose pas deux boucles d'images (le rappel se
     * retirerait de lui-même, mais la garde évite le double comptage
     * pendant une image).
     */
    private fun armerSondeVue(active: Boolean) {
        if (imagesSuivies == active) {
            sondeVue.active = active
            return
        }
        imagesSuivies = active
        sondeVue.active = active
        if (active) {
            Choreographer.getInstance().postFrameCallback(rappelImages)
        }
    }

    /** v0.41.1 : envoie le texte du champ de saisie au build en cours (stdin). */
    private fun envoyerEntreeConsole() {
        val texte = liaison.editEntreeConsole.text?.toString() ?: return
        if (texte.isBlank()) return
        viewModel.onAction(ActionEditor.EnvoyerEntreeConsole(texte))
        liaison.editEntreeConsole.text?.clear()
    }

    /**
     * Bandeau d'échec (§3.3 ; INC-21 : PAR CANAL — le bandeau porte le
     * verdict d'échec du canal AFFICHÉ) : « Voir les problèmes » (onglet
     * dédié, seulement si des diagnostics existent) et « Réessayer »
     * (relance la MÊME activité — la sync pour le canal Sync, les mêmes
     * tâches pour le canal Build) — plus d'échec muet.
     */
    private fun brancherActionsEchec() {
        liaison.boutonVoirProblemes.setOnClickListener {
            viewModel.onAction(ActionEditor.SelectionnerOngletPanneau(OngletPanneau.PROBLEMES))
        }
        // L5d (spec SORTIES § 5.7) : le FIL DE L'INCIDENT — un seul geste
        // ouvre les destinations qui comptent. Le message d'échec courant
        // voyage EN ARGUMENT (contexte de l'incident, vérité complète
        // dans les sorties).
        liaison.boutonFilIncident.setOnClickListener {
            FeuilleFilIncidentFragment
                .creer(liaison.messageEchecCanal.text?.toString().orEmpty())
                .show(childFragmentManager, FeuilleFilIncidentFragment.ETIQUETTE)
        }
        liaison.boutonReessayerCanal.setOnClickListener {
            when (action) {
                FiltreCanalConsole.SYNC -> {
                    viewModel.onAction(ActionEditor.Synchroniser)
                }

                FiltreCanalConsole.BUILD -> {
                    val taches = viewModel.etatGradle.value.taches
                    if (taches.isNotEmpty()) {
                        viewModel.onAction(ActionEditor.ExecuterTaches(taches))
                    }
                }
            }
        }
        // « Expliquer » (mission IA, lot IA-5, spec E3) : les dernières
        // rangées VISIBLES DU LECTEUR partent en pré-remplissage du chat
        // (jamais auto-envoyées — l'utilisateur relit puis envoie).
        liaison.boutonExpliquerIa.setOnClickListener {
            (activity as? ControleurPanneauEditeur)?.expliquerAvecIa(
                lecteur?.texteVisible(EXPLIQUER_DERNIERES) ?: "",
            )
        }
    }

    /**
     * L5b : pousse le filtre PAR SOURCE du canal courant au lecteur —
     * SYNC → [SourceSortie.SYNC], BUILD → [SourceSortie.BUILD]. Le tampon
     * ne bouge pas : la projection est redérivée, les lignes masquées
     * sont comptées et annoncées par le lecteur (honnêteté, règle 14).
     * Idempotent : un canal déjà poussé ne repart pas (une redérivation
     * à chaque émission d'état Gradle serait un gaspillage).
     */
    private fun pousserFiltreAuLecteur() {
        if (canalPousse == action) return
        canalPousse = action
        val lecteurAttache = lecteur ?: return
        lecteurAttache.definirFiltre(
            FiltreSorties(
                sources =
                when (action) {
                    FiltreCanalConsole.SYNC -> setOf(SourceSortie.SYNC)
                    FiltreCanalConsole.BUILD -> setOf(SourceSortie.BUILD)
                },
            ),
        )
    }

    /**
     * Rend le statut (balisé de SON canal — v0.32.5 ; INC-02 : le canal
     * dérive de [PresentationTooling.canalAffiche], PLUS de cascade
     * locale), l'annulation, le bouton Tâches, le bandeau d'échec
     * (message du serveur sinon libellé générique, « Réessayer » seulement
     * si des tâches existent), le chip d'action courante (v0.40.1 §3) et
     * pousse le filtre PAR SOURCE du canal au lecteur (L5b — le tampon ne
     * bouge pas, les lignes masquées sont comptées et annoncées).
     */
    private fun rendre(etat: EtatGradle) {
        // INC-02 : UNE dérivation alimente sélecteur, console visible et
        // bandeau — le canal affiché.
        val canalAffiche = PresentationTooling.canalAffiche(etat, action)
        // INC-22 (finale, avec l'INC-10) : l'activité et le verdict vivent
        // dans l'EN-TÊTE du sheet (ligne enrichie sur Console replié,
        // indicateurs compacts partout ailleurs) — la ligne de statut de
        // la console n'apparaît PLUS que pour l'état que l'en-tête ne
        // porte pas : l'orchestrateur déconnecté avant toute activité.
        // ADR 0078 : l'information vit dans le flux ou dans l'en-tête —
        // jamais aux deux.
        liaison.statutSortie.isVisible = canalAffiche == null && etat.connexion == EtatConnexion.ECHOUEE
        if (liaison.statutSortie.isVisible) {
            liaison.statutSortie.text =
                PresentationTooling.libelleStatut(etat).resoudre(requireContext())
        }
        liaison.boutonAnnulerBuild.isVisible = etat.statutBuild == StatutBuild.EN_COURS

        // Tâches : armé dès que la sync a REMPLI le cache (v4) — v0.39.1
        // (correctif n°2) : `tachesDisponibles` vaut désormais `emptyList()`
        // après une sync réussie dont le listage a échoué (au lieu de
        // `null` silencieux) — le bouton s'active quand même, le clic
        // retente le listage via l'orchestrateur (cf. `ouvrirSelecteurTaches`).
        liaison.boutonTachesSortie.isEnabled = etat.tachesDisponibles != null

        // Bandeau d'échec PAR CANAL (§3.3 ; INC-21) : dérivation du
        // présentateur — celui du canal AFFICHÉ ; l'échec de l'autre canal
        // est signalé par le point d'actualité du sélecteur (INC-20) et
        // l'en-tête. Un échec de sync a les MÊMES actions qu'un échec
        // de build.
        val echecCanal = PresentationTooling.messageEchecCanal(etat, canalAffiche)
        liaison.bandeauEchecCanal.isVisible = echecCanal != null
        if (echecCanal != null) {
            liaison.messageEchecCanal.text = echecCanal.resoudre(requireContext())
            liaison.boutonReessayerCanal.isVisible =
                PresentationTooling.reessaiPossible(etat, canalAffiche)
            // « Voir les problèmes » : seulement si des diagnostics
            // existent (INC-21 — pas d'onglet vide promis).
            liaison.boutonVoirProblemes.isVisible = etat.problemesTotal > 0
        }

        // v0.40.1 (§3) ; INC-02 : sélecteur du canal AFFICHÉ — la cascade
        // locale (sync dès qu'une sync a déjà vécu) mentait pendant un
        // build lancé après une sync réussie : le chip disait « Sync »,
        // l'icône celle de la sync, pendant que la console du build
        // tournait. La dérivation unique ne peut plus diverger.
        rendreSelecteurCanaux(etat, canalAffiche)

        // v0.41.1 : champ de saisie stdin visible pendant un build EN_COURS.
        liaison.champEntreeConsole.isVisible = etat.statutBuild == StatutBuild.EN_COURS

        // L5b : le canal affiché pousse son filtre PAR SOURCE au lecteur
        // (le tampon ne bouge pas — basculer ne perd rien, ne rejoue rien).
        pousserFiltreAuLecteur()
    }

    /**
     * Publie le SÉLECTEUR de canaux (INC-20, volet C : le chip unique
     * v0.40.1 devient un sélecteur segmenté Sync | Build CLIQUABLE) —
     * la sélection suit [action] (choix utilisateur + bascule
     * automatique de l'action qui démarre, source unique
     * `EtatEditor.filtreConsole`), le canal d'existence vient de la
     * dérivation [PresentationTooling.canalAffiche] (INC-02). Chaque chip
     * garde sa couleur de canal harmonisée ; le canal NON affiché qui a du
     * neuf (activité en vol) ou un échec porte un point d'actualité — la
     * sortie de la sync de 194 s reste consultable pendant un build, comme
     * les onglets Sync/Build de la fenêtre Build d'Android Studio.
     */
    private fun rendreSelecteurCanaux(etat: EtatGradle, canal: CanalTooling?) {
        liaison.groupeCanauxConsole.isVisible = canal != null
        if (canal == null) {
            // Aucune action Gradle n'a eu lieu — pas de sélecteur.
            return
        }
        liaison.chipCanalSync.isChecked = action == FiltreCanalConsole.SYNC
        liaison.chipCanalBuild.isChecked = action == FiltreCanalConsole.BUILD
        teinterChipCanal(liaison.chipCanalSync, CanalTooling.SYNC)
        teinterChipCanal(liaison.chipCanalBuild, CanalTooling.BUILD)
        rendrePointActualite(
            liaison.chipCanalSync,
            etat,
            CanalTooling.SYNC,
            affiche = action == FiltreCanalConsole.SYNC,
        )
        rendrePointActualite(
            liaison.chipCanalBuild,
            etat,
            CanalTooling.BUILD,
            affiche =
            action == FiltreCanalConsole.BUILD,
        )
    }

    /** Teinte un chip du sélecteur aux couleurs de SON canal (harmonisées
     *  avec le primaire du thème, fond teinté 12 % — même discipline que
     *  l'ancien chip unique v0.40.1). */
    private fun teinterChipCanal(chip: Chip, canal: CanalTooling) {
        val couleurHarmonisee = ThemeHarmonizer.harmoniserAvecPrimaire(requireContext(), canal.couleur)
        chip.chipBackgroundColor =
            ColorStateList.valueOf(
                androidx.core.graphics.ColorUtils.setAlphaComponent(couleurHarmonisee, ALPHA_FOND_TINTE_CHIP),
            )
        chip.chipStrokeColor = ColorStateList.valueOf(couleurHarmonisee)
        chip.setTextColor(couleurHarmonisee)
    }

    /**
     * Point d'actualité (INC-20) : sur le canal NON affiché seulement —
     * couleur de canal harmonisée pendant une activité en vol, couleur
     * d'erreur pour un échec ; consulter la console (la sélectionner)
     * efface le point.
     */
    private fun rendrePointActualite(chip: Chip, etat: EtatGradle, canal: CanalTooling, affiche: Boolean) {
        val enVol = etat.canalActif == canal
        val echoue =
            when (canal) {
                CanalTooling.SYNC -> etat.messageEchecSync != null
                CanalTooling.BUILD -> etat.statutBuild == StatutBuild.ECHOUE
                CanalTooling.TACHES -> false
            }
        val point = !affiche && (enVol || echoue)
        chip.isChipIconVisible = point
        if (point) {
            chip.chipIcon = androidx.core.content.ContextCompat.getDrawable(
                requireContext(),
                R.drawable.point_actualite_canal,
            )
            val couleur =
                if (echoue) {
                    MaterialColors.getColor(liaison.root, androidx.appcompat.R.attr.colorError)
                } else {
                    ThemeHarmonizer.harmoniserAvecPrimaire(requireContext(), canal.couleur)
                }
            chip.chipIconTint = ColorStateList.valueOf(couleur)
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
        /** Nanosecondes par milliseconde (T6 : uptime Choreographer → ms). */
        const val NANOS_PAR_MILLI = 1_000_000L

        /** Étiquette de la page de configuration (anti-doublon). */
        const val ETIQUETTE_CONFIG = "config-tooling"

        /** L5b : étiquette du lecteur hébergé (anti-doublon). */
        const val ETIQUETTE_LECTEUR = "lecteur_panneau_console"

        /** L5b : « Expliquer » IA — le nombre de dernières rangées
         *  visibles DU LECTEUR envoyées en pré-remplissage (fenêtre
         *  bornée, jamais auto-envoyée). */
        const val EXPLIQUER_DERNIERES = 50

        /** Clé de l'action courante dans l'état d'instance (v0.40.1). */
        const val CLE_ACTION_COURANTE = "action-courante-console"

        /**
         * Alpha du fond teinté des chips du sélecteur (v0.40.1, prompt de
         * suivi §6 — `color-mix(in srgb, var(--c) 12%, transparent)` dans
         * l'aperçu v3). 12 % de 255 ≈ 31.
         */
        const val ALPHA_FOND_TINTE_CHIP = 31
    }
}
