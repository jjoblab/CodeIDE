package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.ui.ThemeHarmonizer
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentPanneauConsoleBinding

/**
 * Onglet Sortie du panneau inférieur (G5 §6, v0.32.4 ADR 0055 ; v3 :
 * tâches au fil du build, étapes de sync, bouton de configuration ; v4,
 * §3.3 : CHIPS Sync/Build — l'arbre d'étapes en vue Sync, les tâches et
 * leur synthèse en vue Build ; v5, aperçu : les deux vues sont EXCLUSIVES
 * et l'une est TOUJOURS active (la chronologie brute — sorties stdout/stderr
 * en liste plate — n'est plus un écran) — bouton Tâches armé par le cache
 * de la sync, BANDEAU d'échec avec « Voir les problèmes » et « Réessayer »,
 * annulation visible en vol, auto-défilement (le suivi s'arrête quand la
 * liste cesse de grandir — un build fini ne défile plus).
 *
 * Le contenu migre du layout empilé de l'activité (v0.32.3) vers ce
 * fragment ; l'activité ne collecte plus l'état tooling — chaque fragment
 * collecte ce qu'il rend.
 *
 * Le filtre de canal (chips) vit dans le fragment : un état de VUE, pas un
 * état d'application — le ViewModel reste celui du tooling. Il survit à la
 * rotation par l'état d'instance.
 *
 * Exemption detekt ciblée (même précédent que `TerminalTiroirFragment` et
 * `InstallFragment`) : TooManyFunctions — un fragment de panneau est un
 * CONTRAT de câblage (cycle de vie + filtre + bandeau + configuration
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

    /** Console du tooling (arbre / lignes / synthèse selon le filtre). */
    private lateinit var adaptateur: ConsoleToolingAdapter

    /**
     * Filtre de canal courant (§3.3 ; v5 — Sync par défaut, comme
     * l'aperçu) — v0.39.1 : la vérité vit dans l'[EtatEditor] (persistée
     * par rotation, pilotée par le ViewModel — un build démarre bascule
     * vers BUILD automatiquement). Le fragment ne fait que LIRE l'état
     * et émettre l'action [ActionEditor.BasculerFiltreConsole] au clic.
     * La sauvegarde locale [CLE_FILTRE_CANAL] reste pour la continuité
     * de cycle entre l'instance du fragment et le ViewModel (re-création
     * du ViewModel avant le onCreateView).
     */
    private var filtre: FiltreCanalConsole = FiltreCanalConsole.SYNC

    /** Anti-réentrance : le rendu programme les chips sans déclencher
     *  leurs écouteurs (même garde-fou que le rendu idempotent de la
     *  configuration). */
    private var renduChipsEnCours = false

    /** Taille de la dernière fenêtre rendue (auto-défilement). */
    private var tailleDerniereFenetre = 0

    /** Retour système pendant l'affichage de la configuration (§3.3) : la
     *  referme avant de remonter au retour de l'espace. */
    private lateinit var retourConfiguration: OnBackPressedCallback

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FragmentPanneauConsoleBinding.inflate(inflateur, conteneur, false)
        // v0.39.1 : on AMORCE avec la sauvegarde locale du fragment (survit
        // à la rotation du fragment sans ViewModel), l'observation de
        // `viewModel.etat` ré-appliquera la vérité du ViewModel juste
        // après (l'ouverture d'un build depuis le sélecteur bascule vers
        // BUILD sans attendre un clic de l'utilisateur).
        filtre =
            etat
                ?.getString(CLE_FILTRE_CANAL)
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
        liaison.boutonAnnulerBuild.setOnClickListener {
            viewModel.onAction(ActionEditor.AnnulerBuild)
        }
        brancherChips()
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

        rendreChips()
        // v0.39.1 : l'état du filtre vit dans le ViewModel — on l'observe
        // pour que les chips se mettent à jour quand un build démarre (la
        // bascule est poussée par `executerTachesGradle`).
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            if (filtre != etat.filtreConsole) {
                filtre = etat.filtreConsole
                tailleDerniereFenetre = 0
                rendreChips()
                rendre(viewModel.etatGradle.value)
            }
        }
        viewModel.etatGradle.collectWithLifecycle(viewLifecycleOwner) { rendre(it) }
    }

    override fun onSaveInstanceState(etat: Bundle) {
        super.onSaveInstanceState(etat)
        etat.putString(CLE_FILTRE_CANAL, filtre.name)
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }

    /** Chips Sync/Build (§3.3 ; v5 — aperçu) : vues EXCLUSIVES, l'une des
     *  deux TOUJOURS active (ChipGroup à sélection unique exigée) —
     * Sync = l'arbre des étapes, Build = les tâches ; il n'y a plus de
     * retour à une chronologie brute. */
    private fun brancherChips() {
        liaison.groupeFiltresConsole.setOnCheckedStateChangeListener { _, ids ->
            if (!renduChipsEnCours) {
                val cible =
                    if (R.id.chip_filtre_build in ids) {
                        FiltreCanalConsole.BUILD
                    } else {
                        FiltreCanalConsole.SYNC
                    }
                // v0.39.1 : la vérité vit dans le ViewModel — l'action
                // y remonte, l'observation de `etat` ré-appliquera les
                // chips et rendra la console. Un build démarreur profite
                // du même chemin (bascule automatique vers BUILD).
                if (cible != filtre) {
                    viewModel.onAction(ActionEditor.BasculerFiltreConsole(cible))
                }
            }
        }
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

    /** Pose l'état visuel des chips depuis [filtre] sans déclencher les
     *  écouteurs (rendu idempotent — la sélection unique du ChipGroup
     *  décoche l'autre chip toute seule). */
    private fun rendreChips() {
        renduChipsEnCours = true
        try {
            liaison.chipFiltreSync.isChecked = filtre == FiltreCanalConsole.SYNC
            liaison.chipFiltreBuild.isChecked = filtre == FiltreCanalConsole.BUILD
        } finally {
            renduChipsEnCours = false
        }
    }

    /** Rend le statut (balisé de SON canal — v0.32.5), l'annulation, le
     *  bouton Tâches, le bandeau d'échec (message du serveur sinon
     *  libellé générique, « Réessayer » seulement si des tâches existent),
     *  la console filtrée (fenêtre bornée, arbre en vue Sync) et l'état
     *  vide. */
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

        val rangees = construireRangeesConsole(etat, filtre)
        adaptateur.submitList(rangees)
        val enVol = etat.statutBuild == StatutBuild.EN_COURS || etat.synchronisationEnCours
        if (enVol && rangees.size > tailleDerniereFenetre && rangees.isNotEmpty()) {
            liaison.listeSortie.scrollToPosition(rangees.lastIndex)
        }
        tailleDerniereFenetre = rangees.size
        liaison.texteSortieVide.isVisible = rangees.isEmpty()
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

        /** Clé du filtre de canal dans l'état d'instance. */
        const val CLE_FILTRE_CANAL = "filtre-canal-console"
    }
}
