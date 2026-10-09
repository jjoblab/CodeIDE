package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
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
 *
 * P3-P4 : onglets « Dépendances » et « Variantes » affichant un
 * aperçu « à venir » (livrables à suivre).
 *
 * P5 : onglet « Tâches » qui délègue à `FeuilleTachesFragment`
 * (réutilisé, ADR 0095 §5).
 */
@AndroidEntryPoint
class ProjetFragment : Fragment() {
    private val viewModel: ProjetViewModel by viewModels()

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
        }
        // Premier chargement : les scripts sont affichés à l'ouverture.
        if (savedInstanceState == null) viewModel.chargerScripts()
    }

    /** Construit les 4 onglets Scripts/Dépendances/Variantes/Tâches. */
    private fun configurerOnglets() {
        val onglets = liaison.ongletsProjet
        onglets.removeAllTabs()
        onglets.addTab(onglets.newTab().setText(R.string.projet_onglet_scripts))
        onglets.addTab(onglets.newTab().setText(R.string.projet_onglet_dependances))
        onglets.addTab(onglets.newTab().setText(R.string.projet_onglet_variantes))
        onglets.addTab(onglets.newTab().setText(R.string.projet_onglet_taches))
        onglets.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = basculerOnglet(tab.position)

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            },
        )
        // Onglet initial : Scripts.
        basculerOnglet(POSITION_SCRIPTS)
    }

    /** Bascule la visibilité des vues d'onglet selon [position]. */
    private fun basculerOnglet(position: Int) {
        liaison.vueScripts.isVisible = position == POSITION_SCRIPTS
        liaison.vueDependances.isVisible = position == POSITION_DEPENDANCES
        liaison.vueVariantes.isVisible = position == POSITION_VARIANTES
        liaison.vueTaches.isVisible = position == POSITION_TACHES
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
            // Remplir les tailles après le layout — version simple, on
            // évite un BaseAdapter custom pour rester sous le seuil detekt.
            liaison.listeScripts.post {
                remplirTaillesScripts(etat.scripts)
            }
        }
    }

    /** Remplit le champ « taille » de chaque ligne de la liste. */
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

    /** Met à jour la liste des dépendances ou le message « aucune ». */
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

    /** Remplit le champ « configuration — script » de chaque ligne. */
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

    /** P5 : ouvre FeuilleTachesFragment (réutilisé). */
    private fun ouvrirFeuilleTaches() {
        val feuille = FeuilleTachesFragment()
        feuille.show(parentFragmentManager, "taches_projet")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        liaisonAmorce = null
    }

    private companion object {
        const val POSITION_SCRIPTS: Int = 0
        const val POSITION_DEPENDANCES: Int = 1
        const val POSITION_VARIANTES: Int = 2
        const val POSITION_TACHES: Int = 3
    }
}
