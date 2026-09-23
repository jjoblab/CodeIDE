package jo.codeide.feature.home

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.ProjectId
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.home.databinding.DialogueRenommerBinding
import jo.codeide.feature.home.databinding.FragmentHomeBinding
import javax.inject.Inject

/**
 * Écran d'accueil — destination initiale du graphe de navigation
 * (étape 7 : liste des projets).
 *
 * Le fragment ne fait que **rendre l'état** et **émettre des actions** :
 * recherche avec délai, tri, tirer-relâcher, actions par projet (ouvrir,
 * renommer, épingler, retirer, supprimer du disque avec confirmation
 * rappelant le nom), import de dossier existant et relocalisation d'un
 * projet dont l'accès est rompu — tout vit dans [HomeViewModel].
 *
 * Les confirmations destructives sont locales au dialogue ; les messages
 * d'issue arrivent en événements ([EffetAccueil]) et partent en snackbar.
 */
@AndroidEntryPoint
@Suppress("TooManyFunctions") // Rendu par état, dialogues et écouteurs — un écran complet (règle 16).
class HomeFragment :
    BaseFragment<FragmentHomeBinding>(),
    ProjetsAccueilAdapter.EcouteurProjets {
    /** Navigation découplée : la feature ne connaît jamais les autres. */
    @Inject
    lateinit var navigator: AppNavigator

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var adapteur: ProjetsAccueilAdapter

    /** Sélecteur SAF « Ouvrir un dossier existant » (import au registre). */
    private val selecteurImport =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { viewModel.action(ActionAccueil.ImporterDossier(it.toString())) }
        }

    /** Projet en cours de relocalisation (état du sélecteur associé). */
    private var projetEnRelocalisation: ProjectId? = null

    /** Sélecteur SAF de relocalisation d'un projet (accès rompu). */
    private val selecteurRelocalisation =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val id = projetEnRelocalisation
            projetEnRelocalisation = null
            if (uri != null && id != null) {
                viewModel.action(ActionAccueil.RelocaliserProjet(id, uri.toString()))
            }
        }

    /** Restaure la recherche une seule fois (puis la vue gère son texte). */
    private var premiereRendu = true

    /** Le défilement vers le projet créé a-t-il déjà eu lieu ? */
    private var surlignageDejaDefile = false

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentHomeBinding = FragmentHomeBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Contenu edge-to-edge : la racine absorbe les barres système.
        binding.root.applySystemBarsInsets(top = true, bottom = true)

        adapteur = ProjetsAccueilAdapter(this)
        binding.listeProjets.adapter = adapteur
        binding.listeProjets.setHasFixedSize(true)

        // Projet créé par le wizard (étape 11) : mise en évidence — le
        // navigateur porte l'identifiant via la pile de retour (une fois).
        navigator.consommerProjetCree()?.let { identifiant ->
            viewModel.action(ActionAccueil.SurlignerProjet(ProjectId(identifiant)))
        }

        brancherActions()
        observerEtatEtEffets()
    }

    /** Branche les interactions : titre, recherche, tri, rafraîchissement, FAB. */
    private fun brancherActions() {
        binding.buttonSettings.setOnClickListener { navigator.openSettings() }
        binding.bandeauDossier.boutonConfigurer.setOnClickListener { navigator.openOnboarding() }
        // Include optionnel aux yeux de ViewBinding : appels sûrs, jamais de `!!`.
        binding.bandeauTerminal?.boutonInstallerTerminal?.setOnClickListener { navigator.openBootstrapInstall() }

        // Recherche : chaque frappe part au ViewModel, qui fusionne les
        // frappes successives avec le délai (250 ms).
        binding.saisieRecherche.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    texte: CharSequence?,
                    debut: Int,
                    nombre: Int,
                    apres: Int,
                ) = Unit

                override fun onTextChanged(
                    texte: CharSequence?,
                    debut: Int,
                    avant: Int,
                    nombre: Int,
                ) = Unit

                override fun afterTextChanged(texte: Editable?) {
                    viewModel.action(ActionAccueil.Rechercher(texte?.toString().orEmpty()))
                }
            },
        )

        binding.groupeTri.addOnButtonCheckedListener { _, idPredefini, coche ->
            if (coche) {
                val tri =
                    when (idPredefini) {
                        R.id.bouton_tri_nom -> TriAccueil.NOM
                        else -> TriAccueil.RECENTS
                    }
                viewModel.action(ActionAccueil.ChangerTri(tri))
            }
        }

        binding.rafraichissementAccueil.setOnRefreshListener {
            viewModel.action(ActionAccueil.Rafraichir)
        }

        // « Nouveau projet » : vers le wizard (écran placeholder à
        // l'étape 7, assistant complet à l'étape 10).
        binding.fabNouveauProjet.setOnClickListener { navigator.openNewProjectWizard() }

        // « Ouvrir un dossier existant » : sélecteur SAF, ajout au registre.
        binding.fabOuvrirDossier.setOnClickListener { selecteurImport.launch(null) }

        // L'action de l'état vide dépend de sa nature : créer le premier
        // projet, ou effacer une recherche sans résultat.
        binding.boutonEtatVideAction.setOnClickListener {
            if (viewModel.etat.value.requete
                    .isBlank()
            ) {
                navigator.openNewProjectWizard()
            } else {
                binding.saisieRecherche.setText("")
            }
        }
    }

    /** Collecte l'état (rendu) et les effets (snackbars) sur le cycle de vie. */
    private fun observerEtatEtEffets() {
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet ->
            if (effet is EffetAccueil.OuvrirEditeur) {
                // Espace de travail (étape 13) : l'activité s'affiche par-dessus,
                // l'accueil survit en dessous.
                navigator.openEditor(effet.id.value)
            } else {
                annoncer(effet)
            }
        }
    }

    /** Rendu de l'état : bandeau, états superposés, liste, tri, rafraîchissement. */
    private fun rendre(etat: EtatAccueil) {
        binding.bandeauDossier.root.isVisible = etat.montrerBandeau
        binding.bandeauTerminal?.root?.isVisible = etat.montrerBandeauTerminal
        binding.etatChargement.isVisible = etat.chargement
        binding.etatErreur.isVisible = etat.erreur != null
        binding.rafraichissementAccueil.isRefreshing = etat.rafraichissement

        val sansContenu = !etat.chargement && etat.erreur == null && etat.projets.isEmpty()
        binding.colonneEtatVide.isVisible = sansContenu
        if (sansContenu) {
            if (etat.requete.isBlank()) {
                binding.etatVide.title = getString(R.string.accueil_vide_titre)
                binding.etatVide.message = getString(R.string.accueil_vide_message)
                binding.boutonEtatVideAction.setText(R.string.accueil_vide_action)
            } else {
                binding.etatVide.title = getString(R.string.accueil_sans_resultat_titre)
                binding.etatVide.message = getString(R.string.accueil_sans_resultat_message, etat.requete)
                binding.boutonEtatVideAction.setText(R.string.accueil_sans_resultat_action)
            }
        }

        binding.listeProjets.isVisible = !etat.chargement && etat.erreur == null && etat.projets.isNotEmpty()
        adapteur.projetEnEvidence = etat.projetEnEvidence
        adapteur.submitList(etat.projets.map { ProjetAffiche(it, etat.etatsAcces[it.id]) })

        // Défilement vers le projet créé — une seule fois, quand il est
        // enfin présent dans la liste (le registre peut émettre avant).
        val cible = etat.projetEnEvidence
        if (cible != null && !surlignageDejaDefile) {
            etat.projets.indexOfFirst { it.id == cible }.takeIf { it >= 0 }?.let { position ->
                surlignageDejaDefile = true
                (binding.listeProjets.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(position, binding.root.height / FRACTION_ECRAN_CIBLE)
            }
        }

        synchroniserTri(etat.tri)

        // Restauration de la recherche (rotation / mort du processus) :
        // une seule fois, puis le champ reste maître de son texte.
        if (premiereRendu) {
            premiereRendu = false
            if (etat.requete.isNotBlank() && binding.saisieRecherche.text?.toString() != etat.requete) {
                binding.saisieRecherche.setText(etat.requete)
            }
        }
    }

    /** Reflète le tri choisi sans déclencher de nouvelle action. */
    private fun synchroniserTri(tri: TriAccueil) {
        val attendu =
            when (tri) {
                TriAccueil.RECENTS -> R.id.bouton_tri_recents
                TriAccueil.NOM -> R.id.bouton_tri_nom
            }
        if (binding.groupeTri.checkedButtonId != attendu) {
            binding.groupeTri.check(attendu)
        }
    }

    /** Traduit un effet ponctuel en message (snackbar). */
    private fun annoncer(effet: EffetAccueil) {
        val message =
            when (effet) {
                // L'ouverture de l'éditeur est interceptée par le collecteur
                // (navigation, pas de snackbar) — elle n'arrive jamais ici.
                is EffetAccueil.OuvrirEditeur -> return

                is EffetAccueil.ProjetImporte -> getString(R.string.accueil_snackbar_importe, effet.nom)

                EffetAccueil.DossierDejaPresent -> getString(R.string.accueil_snackbar_deja_present)

                is EffetAccueil.DossierRefuse -> getString(TraductionsAccueil.refus(effet.raison))

                is EffetAccueil.ProjetDeplace -> getString(R.string.accueil_snackbar_deplace, effet.nom)

                EffetAccueil.ProjetRetire -> getString(R.string.accueil_snackbar_retire)

                EffetAccueil.ProjetSupprime -> getString(R.string.accueil_snackbar_supprime)

                is EffetAccueil.Echec -> getString(TraductionsAccueil.message(effet.erreur))
            }
        Snackbar.make(binding.racineAccueil, message, Snackbar.LENGTH_SHORT).show()
    }

    /** Toucher une carte : ouvrir le projet, ou ses actions si l'accès est rompu. */
    override fun surClicProjet(
        projet: Project,
        acces: ProjectAccessState?,
    ) {
        if (acces == ProjectAccessState.Missing || acces == ProjectAccessState.PermissionLost) {
            ouvrirMenuActions(ProjetAffiche(projet, acces))
        } else {
            viewModel.action(ActionAccueil.OuvrirProjet(projet.id))
        }
    }

    /** Toucher le bouton d'actions d'une ligne : menu contextuel. */
    override fun surMenuProjet(
        projet: Project,
        acces: ProjectAccessState?,
    ) {
        ouvrirMenuActions(ProjetAffiche(projet, acces))
    }

    /**
     * Menu contextuel d'un projet : les actions de résolution ouvrent la
     * marche quand l'accès est rompu (« Relocaliser », « Retirer ») ;
     * « Ouvrir » n'est proposé que sur un projet joignable.
     */
    private fun ouvrirMenuActions(affiche: ProjetAffiche) {
        val projet = affiche.projet
        val rompu = affiche.acces == ProjectAccessState.Missing || affiche.acces == ProjectAccessState.PermissionLost

        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (rompu) {
            actions += R.string.accueil_action_relocaliser to {
                projetEnRelocalisation = projet.id
                selecteurRelocalisation.launch(null)
            }
        } else {
            actions += R.string.accueil_action_ouvrir to {
                viewModel.action(ActionAccueil.OuvrirProjet(projet.id))
            }
        }
        actions += R.string.accueil_action_renommer to { ouvrirDialogueRenommage(projet) }
        actions +=
            (if (projet.isPinned) R.string.accueil_action_desepingler else R.string.accueil_action_epingler) to {
                viewModel.action(ActionAccueil.EpinglerProjet(projet.id, !projet.isPinned))
            }
        actions += R.string.accueil_action_retirer_liste to {
            viewModel.action(ActionAccueil.RetirerProjet(projet.id))
        }
        actions += R.string.accueil_action_supprimer to { ouvrirConfirmationSuppression(projet) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(projet.name)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, indice ->
                actions[indice].second()
            }.setNegativeButton(R.string.accueil_annuler, null)
            .show()
    }

    /** Dialogue de renommage : champ pré-rempli, validation au ViewModel. */
    private fun ouvrirDialogueRenommage(projet: Project) {
        val vue = DialogueRenommerBinding.inflate(layoutInflater)
        vue.saisieNouveauNom.setText(projet.name)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.accueil_renommer_titre)
            .setView(vue.root)
            .setPositiveButton(R.string.accueil_renommer_confirmer) { _, _ ->
                viewModel.action(
                    ActionAccueil.RenommerProjet(
                        projet.id,
                        vue.saisieNouveauNom.text
                            ?.toString()
                            .orEmpty(),
                    ),
                )
            }.setNegativeButton(R.string.accueil_annuler, null)
            .show()
    }

    /** Confirmation de suppression du disque, avec rappel du nom (étape 7). */
    private fun ouvrirConfirmationSuppression(projet: Project) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.accueil_suppression_titre)
            .setMessage(getString(R.string.accueil_suppression_message, projet.name))
            .setPositiveButton(R.string.accueil_suppression_confirmer) { _, _ ->
                viewModel.action(ActionAccueil.SupprimerDuDisque(projet.id))
            }.setNegativeButton(R.string.accueil_annuler, null)
            .show()
    }

    private companion object {
        /** Diviseur d'écran : tiers au-dessus du projet mis en évidence. */
        const val FRACTION_ECRAN_CIBLE = 3
    }
}
