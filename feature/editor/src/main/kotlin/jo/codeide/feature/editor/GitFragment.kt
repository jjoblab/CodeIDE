package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.EtatDepot
import jo.codeide.core.domain.RaisonDepotInaccessible
import jo.codeide.core.domain.StatutFichier
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentGitBinding

/**
 * Fragment Git du tiroir (mission Git G2, ADR 0092) : entête avec branche
 * courante, champ de message de commit, liste des changements, bouton Commit.
 *
 * Structure VS Code + vocabulaire/icônes Android Studio. Le ViewModel
 * délègue au [jo.codeide.core.domain.MoteurGit] qui exécute le binaire
 * `git` via le pont FUSE.
 */
@AndroidEntryPoint
class GitFragment : Fragment() {
    private val viewModel: GitViewModel by viewModels()

    private var liaisonAmorce: FragmentGitBinding? = null
    private val liaison: FragmentGitBinding
        get() = checkNotNull(liaisonAmorce) { "Binding détruit" }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaisonAmorce = FragmentGitBinding.inflate(inflater, container, false)
        return liaison.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        liaison.boutonActualiserGit.setOnClickListener { viewModel.rafraichir() }
        liaison.boutonInitialiserGit.setOnClickListener { viewModel.initialiser() }
        liaison.boutonCommitter.setOnClickListener { viewModel.committer() }
        liaison.messageCommit.setOnEditorActionListener { _, _, _ ->
            if (viewModel.etat.value.commitPossible) {
                viewModel.committer()
                true
            } else {
                false
            }
        }
        liaison.messageCommit.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: android.text.Editable?) {
                    viewModel.messageCommit(s?.toString() ?: "")
                }
            },
        )
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /**
     * v0.80.5 (correctif « section figée sur initialiser un dépôt ») :
     * sélectionner l'onglet Git recharge l'état — comme la fenêtre Git
     * d'Android Studio se rafraîchit à la prise de focus. Les fragments
     * du tiroir sont pré-créés puis montrés/cachés par transactions : leur
     * état vivait dans une photographie prise à l'ouverture de l'éditeur,
     * un dépôt cloné depuis l'accueil ou initialisé dans le terminal
     * laissait donc « Initialiser un dépôt » à l'écran indéfiniment.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) viewModel.rafraichir()
    }

    /**
     * v0.80.5 : la section devient visible avec l'éditeur — la sonde
     * discrète du dépôt démarre (dépôt créé, commit, checkout détectés
     * en arrière-plan) ; [onStop] l'arrête (aucune stat de fichier quand
     * l'éditeur n'est pas à l'écran).
     */
    override fun onStart() {
        super.onStart()
        viewModel.demarrerSurveillance()
    }

    /** L'éditeur n'est plus visible : la surveillance s'arrête. */
    override fun onStop() {
        viewModel.arreterSurveillance()
        super.onStop()
    }

    private fun rendre(etat: EtatGit) {
        // v0.80.4 : pendant l'initialisation, la zone « pas un dépôt »
        // s'efface au profit du repère de chargement — le bouton ne peut
        // plus être martelé pendant un git init en cours.
        liaison.chargementGit.isVisible = etat.chargement
        // v0.90.1 (étape A) : état indéterminé (erreurDepot) — la zone
        // « pas un dépôt » reste CACHÉE (git ne l'a pas dit) et le corps
        // aussi (rien à montrer) : l'erreur observable prime.
        liaison.zonePasDepot.isVisible = etat.pasDepot && !etat.chargement
        liaison.boutonInitialiserGit.isEnabled = !etat.chargement
        liaison.corpsGit.isVisible = !etat.chargement && !etat.pasDepot && etat.erreurDepot == null
        liaison.boutonCommitter.isVisible = !etat.chargement && !etat.pasDepot && etat.erreurDepot == null

        liaison.brancheGit.text = etat.branche
        liaison.brancheGit.isVisible = etat.branche != null

        val nb = etat.nbChangements
        liaison.resumeChangements.isVisible = !etat.chargement && !etat.pasDepot && etat.statut != null
        liaison.resumeChangements.text =
            if (nb == 0) {
                getString(R.string.git_aucun_changement)
            } else {
                resources.getQuantityString(R.plurals.git_n_changements, nb, nb)
            }

        liaison.listeChangements.isVisible = nb > 0 && etat.statut != null
        liaison.listeChangements.text =
            etat.statut?.modifications?.joinToString("\n") { mod ->
                val code = codeStatutAffichage(mod.statutIndex, mod.statutTravail)
                "$code ${mod.chemin}"
            }

        val erreurDepot = etat.erreurDepot
        if (erreurDepot != null) {
            liaison.erreurGit.text = texteErreurDepot(erreurDepot)
        } else {
            liaison.erreurGit.text = etat.erreur
        }
        liaison.erreurGit.isVisible = etat.erreur != null || erreurDepot != null

        liaison.boutonCommitter.isEnabled = etat.commitPossible
    }

    /**
     * Message observable d'un dépôt à l'état indéterminé (v0.90.1,
     * étape A) : préfixe localisé par raison + stderr réel de git (la
     * preuve) — l'utilisateur peut relancer par le bouton Actualiser,
     * « Initialiser » lui est interdit tant que git n'a pas confirmé.
     */
    private fun texteErreurDepot(erreur: EtatDepot.Inaccessible): String {
        val prefixe =
            when (erreur.raison) {
                RaisonDepotInaccessible.BINAIRE_ABSENT -> getString(R.string.git_erreur_depot_binaire_absent)
                RaisonDepotInaccessible.LANCEMENT_IMPOSSIBLE -> getString(R.string.git_erreur_depot_lancement)
                RaisonDepotInaccessible.REFUS_GIT -> getString(R.string.git_erreur_depot_refus)
                RaisonDepotInaccessible.STDOUT_INATTENDU -> getString(R.string.git_erreur_depot_stdout)
            }
        val details = erreur.stderrExpurge.trim()
        return if (details.isEmpty()) prefixe else "$prefixe\n$details"
    }

    /** Code d'affichage du statut (lettre + couleur, format Android Studio). */
    private fun codeStatutAffichage(
        index: StatutFichier,
        travail: StatutFichier,
    ): String =
        when {
            index == StatutFichier.AJOUTE -> "A"
            index == StatutFichier.MODIFIE -> "M"
            index == StatutFichier.SUPPRIME -> "D"
            index == StatutFichier.RENOMME -> "R"
            travail == StatutFichier.MODIFIE -> "M"
            travail == StatutFichier.NON_SUIVI -> "U"
            travail == StatutFichier.CONFLIT -> "C"
            else -> " "
        }

    override fun onDestroyView() {
        super.onDestroyView()
        liaisonAmorce = null
    }
}
