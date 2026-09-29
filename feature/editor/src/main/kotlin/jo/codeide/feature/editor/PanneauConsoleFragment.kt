package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * §3.3 : barre d'outils avec CHIPS Sync/Build — l'arbre d'étapes en vue
 * Sync, les tâches et leur synthèse en vue Build — bouton Tâches armé par
 * le cache de la sync, BANDEAU d'échec avec « Voir les problèmes » et
 * « Réessayer », annulation visible en vol, auto-défilement (le suivi
 * s'arrête quand la liste cesse de grandir — un build fini ne défile plus).
 *
 * Le contenu migre du layout empilé de l'activité (v0.32.3) vers ce
 * fragment ; l'activité ne collecte plus l'état tooling — chaque fragment
 * collecte ce qu'il rend.
 *
 * Le filtre de canal (chips) vit dans le fragment : un état de VUE, pas un
 * état d'application — le ViewModel reste celui du tooling. Il survit à la
 * rotation par l'état d'instance.
 */
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

    /** Filtre de canal courant (§3.3) — survit à la rotation. */
    private var filtre: FiltreCanalConsole = FiltreCanalConsole.TOUS

    /** Anti-réentrance : le rendu programme les chips sans déclencher
     *  leurs écouteurs (même garde-fou que le rendu idempotent de la
     *  configuration). */
    private var renduChipsEnCours = false

    /** Taille de la dernière fenêtre rendue (auto-défilement). */
    private var tailleDerniereFenetre = 0

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FragmentPanneauConsoleBinding.inflate(inflateur, conteneur, false)
        filtre =
            etat
                ?.getString(CLE_FILTRE_CANAL)
                ?.let { nom -> FiltreCanalConsole.entries.firstOrNull { it.name == nom } }
                ?: FiltreCanalConsole.TOUS
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
        // Configuration du tooling (v3) : l'engrenage de l'onglet Sortie
        // ouvre l'écran dédié — réglages persistés à l'instant + état vivant
        // de l'orchestrateur, sans quitter l'espace de travail.
        liaison.boutonConfigTooling.setOnClickListener {
            DialogueConfigToolingFragment().show(childFragmentManager, ETIQUETTE_DIALOGUE)
        }

        rendreChips()
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

    /** Chips Sync/Build (§3.3) : filtres EXCLUSIFS — activer l'une couvre
     *  l'autre, les désactiver toutes les deux revient à la chronologie. */
    private fun brancherChips() {
        liaison.chipFiltreSync.setOnCheckedChangeListener { _, coche ->
            if (!renduChipsEnCours) {
                if (coche) {
                    liaison.chipFiltreBuild.isChecked = false
                }
                majFiltre()
            }
        }
        liaison.chipFiltreBuild.setOnCheckedChangeListener { _, coche ->
            if (!renduChipsEnCours) {
                if (coche) {
                    liaison.chipFiltreSync.isChecked = false
                }
                majFiltre()
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

    /** Reçoit le filtre des chips (chips = vérité visuelle de l'état). */
    private fun majFiltre() {
        filtre =
            when {
                liaison.chipFiltreSync.isChecked -> FiltreCanalConsole.SYNC
                liaison.chipFiltreBuild.isChecked -> FiltreCanalConsole.BUILD
                else -> FiltreCanalConsole.TOUS
            }
        tailleDerniereFenetre = 0
        rendre(viewModel.etatGradle.value)
    }

    /** Pose l'état visuel des chips depuis [filtre] sans déclencher les
     *  écouteurs (rendu idempotent). */
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

        // Tâches : armé dès que la sync a rempli le cache — l'action
        // ouvre le sélecteur SANS aller-retour serveur (v4).
        liaison.boutonTachesSortie.isEnabled = etat.tachesDisponibles?.isNotEmpty() == true

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

    private companion object {
        /** Étiquette du dialogue de configuration du tooling (anti-doublon). */
        const val ETIQUETTE_DIALOGUE = "config-tooling"

        /** Clé du filtre de canal dans l'état d'instance. */
        const val CLE_FILTRE_CANAL = "filtre-canal-console"
    }
}
