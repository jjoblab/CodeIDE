package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentProjetBinding

/**
 * Fragment de la section « Projet » du tiroir (mission Projet P2-P6,
 * ADR 0095).
 *
 * P2 : onglet « Scripts » affichant la liste des scripts de build lus
 * par le serveur de tooling (`BuildScriptsHandler`, protocole v7).
 * Clic sur un script → ouverture dans l'éditeur (P6+).
 *
 * P3 : onglet « Dépendances » affichant les dépendances déclarées
 * parsées depuis les scripts.
 *
 * P6 : onglet « Mises à jour » interrogeant Maven
 * (`MavenVersionesDisponibles`, ADR 0097) pour comparer les versions
 * disponibles à celles déclarées.
 *
 * P5 (reporté) : onglet « Variantes » — aperçu, branchement AGP TAPI
 * nécessite ~10 Mo de dépendance serveur.
 *
 * P5 : onglet « Tâches » qui délègue à `FeuilleTachesFragment`
 * (réutilisé, ADR 0095 §5).
 */
@AndroidEntryPoint
@Suppress("TooManyFunctions")
class ProjetFragment : Fragment() {
    private val viewModel: ProjetViewModel by viewModels()

    /**
     * v0.80.4 : ViewModel d'éditeur **porté par l'activité** — le tiroir
     * Projet lui délègue l'exécution des tâches et l'ouverture des
     * fichiers, exactement comme la console et l'explorateur : une
     * seule source de vérité pour l'état Gradle et les onglets ouverts.
     */
    private val viewModelEditeur: EditorViewModel by activityViewModels()

    private var liaisonAmorce: FragmentProjetBinding? = null
    private val liaison: FragmentProjetBinding
        get() = checkNotNull(liaisonAmorce) { "Binding détruit" }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaisonAmorce = FragmentProjetBinding.inflate(inflater, container, false)
        return liaison.root
    }

    @Suppress("LongMethod")
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        liaison.boutonActualiser.setOnClickListener { viewModel.chargerScripts() }
        liaison.boutonOuvrirTaches.setOnClickListener { ouvrirFeuilleTaches() }
        configurerOnglets()
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            afficherEtatScripts(etat)
            afficherEtatDependances(etat)
            afficherEtatMisesAJour(etat)
        }
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet ->
            when (effet) {
                is EffetProjet.OuvrirScript -> ouvrirScriptDansEditeur(effet.uri, effet.cheminRelatif)
            }
        }
        if (savedInstanceState == null) viewModel.chargerScripts()
    }

    /** Construit les 5 onglets Scripts/Dépendances/Mises à jour/Variantes/Tâches. */
    private fun configurerOnglets() {
        val onglets = liaison.ongletsProjet
        onglets.removeAllTabs()
        onglets.addTab(onglets.newTab().setText(R.string.projet_onglet_scripts))
        onglets.addTab(onglets.newTab().setText(R.string.projet_onglet_dependances))
        onglets.addTab(onglets.newTab().setText(R.string.projet_onglet_mises_a_jour))
        onglets.addTab(onglets.newTab().setText(R.string.projet_onglet_variantes))
        onglets.addTab(onglets.newTab().setText(R.string.projet_onglet_taches))
        onglets.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = basculerOnglet(tab.position)

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            },
        )
        basculerOnglet(POSITION_SCRIPTS)
    }

    /** Bascule la visibilité des vues d'onglet selon [position]. */
    private fun basculerOnglet(position: Int) {
        liaison.vueScripts.isVisible = position == POSITION_SCRIPTS
        liaison.vueDependances.isVisible = position == POSITION_DEPENDANCES
        liaison.vueMisesAJour.isVisible = position == POSITION_MISES_A_JOUR
        liaison.vueVariantes.isVisible = position == POSITION_VARIANTES
        liaison.vueTaches.isVisible = position == POSITION_TACHES
        // P6 : charge paresseusement les mises à jour au premier accès.
        if (position == POSITION_MISES_A_JOUR && !viewModel.etat.value.misesAJour.termine) {
            viewModel.chargerMisesAJour()
        }
    }

    /** Met à jour la liste des scripts ou les messages d'état. */
    private fun afficherEtatScripts(etat: EtatProjet) {
        liaison.chargementScripts.isVisible = etat.chargement
        liaison.messageChargement.isVisible = etat.chargement
        liaison.messageErreur.isVisible = !etat.chargement && etat.erreur
        liaison.messageVide.isVisible = !etat.chargement && !etat.erreur && etat.scripts.isEmpty()
        liaison.listeScripts.isVisible = !etat.chargement && !etat.erreur && etat.scripts.isNotEmpty()
        if (etat.scripts.isNotEmpty()) {
            val contexte = requireContext()
            liaison.listeScripts.adapter =
                ArrayAdapter(
                    contexte,
                    R.layout.ligne_script_build,
                    R.id.chemin_script,
                    etat.scripts.map { it.cheminRelatif },
                )
            liaison.listeScripts.setOnItemClickListener { _, _, position, _ ->
                etat.scripts.getOrNull(position)?.let { viewModel.ouvrirScript(it) }
            }
            liaison.listeScripts.post {
                remplirTaillesScripts(etat.scripts)
            }
        }
    }

    private fun remplirTaillesScripts(scripts: List<jo.codeide.core.domain.ScriptDeBuild>) {
        val liste = liaison.listeScripts
        val nb = minOf(liste.childCount, scripts.size)
        for (i in 0 until nb) {
            val enfant = liste.getChildAt(i) ?: continue
            val taille = scripts[i].tailleOctets
            enfant.findViewById<android.widget.TextView>(R.id.taille_script)?.text =
                getString(R.string.projet_scripts_octets, taille.toString())
        }
    }

    private fun afficherEtatDependances(etat: EtatProjet) {
        liaison.messageDependancesVides.isVisible = etat.dependances.isEmpty()
        liaison.listeDependances.isVisible = etat.dependances.isNotEmpty()
        if (etat.dependances.isNotEmpty()) {
            val contexte = requireContext()
            liaison.listeDependances.adapter =
                ArrayAdapter(
                    contexte,
                    R.layout.ligne_dependance,
                    R.id.coordonnees_dependance,
                    etat.dependances.map { it.coordonnes },
                )
            liaison.listeDependances.post {
                remplirConfigurationsDependances(etat.dependances)
            }
        }
    }

    private fun remplirConfigurationsDependances(dependances: List<jo.codeide.core.domain.DependanceDeclaree>) {
        val liste = liaison.listeDependances
        val nb = minOf(liste.childCount, dependances.size)
        for (i in 0 until nb) {
            val enfant = liste.getChildAt(i) ?: continue
            val dep = dependances[i]
            enfant.findViewById<android.widget.TextView>(R.id.configuration_dependance)?.text =
                getString(
                    R.string.projet_dependance_config_origine,
                    dep.configuration,
                    dep.scriptOrigine,
                )
        }
    }

    /** P6 : met à jour la liste des mises à jour disponibles. */
    private fun afficherEtatMisesAJour(etat: EtatProjet) {
        val mj = etat.misesAJour
        liaison.chargementMisesAJour.isVisible = mj.chargement
        liaison.messageMisesAJourVide.isVisible =
            mj.termine && !mj.chargement && mj.entrees.isEmpty()
        liaison.listeMisesAJour.isVisible = mj.entrees.isNotEmpty()
        if (mj.entrees.isNotEmpty()) {
            val contexte = requireContext()
            liaison.listeMisesAJour.adapter =
                ArrayAdapter(
                    contexte,
                    R.layout.ligne_mise_a_jour,
                    R.id.coordonnees_mise_a_jour,
                    mj.entrees.map { it.coordonnes },
                )
            liaison.listeMisesAJour.post {
                remplirVersionsMisesAJour(mj.entrees)
            }
        }
    }

    /** Remplit le champ « version courante → dernière » de chaque ligne. */
    private fun remplirVersionsMisesAJour(entrees: List<EntreeMiseAJour>) {
        val liste = liaison.listeMisesAJour
        val nb = minOf(liste.childCount, entrees.size)
        for (i in 0 until nb) {
            val enfant = liste.getChildAt(i) ?: continue
            val entree = entrees[i]
            enfant.findViewById<android.widget.TextView>(R.id.versions_mise_a_jour)?.text =
                getString(
                    R.string.projet_mise_a_jour_versions,
                    entree.versionCourante,
                    entree.versionDerniere,
                )
            enfant.findViewById<View>(R.id.indicateur_mise_a_jour)?.isVisible =
                entree.miseAJourDisponible
        }
    }

    /**
     * P5 : ouvre le sélecteur de tâches — v0.80.4 (correctif crash
     * a6d72e9d) : l'action passe par l'[EditorViewModel] d'activité
     * (`OuvrirSelecteurTaches`), exactement comme le bouton Tâches de
     * la console. C'est lui qui garantit les arguments de la feuille :
     * cache de sync d'abord, listage orchestrateur en repli, échec
     * affiché avec « Réessayer » — au lieu d'instancier une feuille
     * SANS arguments (crash `requireArguments`).
     */
    private fun ouvrirFeuilleTaches() {
        viewModelEditeur.onAction(ActionEditor.OuvrirSelecteurTaches)
    }

    /**
     * P6+ : ouvre un script de build dans l'éditeur (v0.80.4 : ouverture
     * RÉELLE — l'URI de document est résolue par le ViewModel, l'onglet
     * s'ouvre via l'[EditorViewModel] d'activité ; le snackbar n'est plus
     * qu'un repli d'erreur).
     */
    private fun ouvrirScriptDansEditeur(
        uri: String?,
        cheminRelatif: String,
    ) {
        if (uri != null) {
            viewModelEditeur.onAction(ActionEditor.OuvrirFichier(uri))
        } else {
            Snackbar
                .make(
                    liaison.root,
                    getString(R.string.projet_ouverture_script, cheminRelatif),
                    Snackbar.LENGTH_SHORT,
                ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        liaisonAmorce = null
    }

    private companion object {
        const val POSITION_SCRIPTS: Int = 0
        const val POSITION_DEPENDANCES: Int = 1
        const val POSITION_MISES_A_JOUR: Int = 2
        const val POSITION_VARIANTES: Int = 3
        const val POSITION_TACHES: Int = 4
    }
}
