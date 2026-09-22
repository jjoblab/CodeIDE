package jo.codeide.feature.newproject

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.newproject.databinding.FragmentNewprojectBinding
import javax.inject.Inject

/**
 * Hôte du wizard de création de projet (étape 10 — section 12.2) : barre
 * d'outils (fermeture), **indicateur d'étapes** (progression + libellé
 * « Étape N sur M »), conteneur des fragments d'étapes et **barre d'actions
 * fixe** (Retour / Suivant).
 *
 * Le [WizardViewModel] est scopé à cet hôte — les fragments d'étapes le
 * partagent (`viewModels({ requireParentFragment() })`) et ne portent aucun
 * état propre : la rotation et la mort du processus survivent par l'état
 * unique et le `SavedStateHandle`.
 *
 * Le bouton retour système revient à l'étape précédente ; depuis la
 * première étape — ou depuis la fermeture avec données saisies — un
 * dialogue de confirmation « Abandonner la création ? » s'affiche
 * (section 12.2). L'abandon confirmé relâche l'emplacement éphémère via le
 * ViewModel avant l'effet de fermeture.
 */
@AndroidEntryPoint
@Suppress("TooManyFunctions") // Rendu par état, transitions, dialogues (règle 16).
class NewProjectFragment : BaseFragment<FragmentNewprojectBinding>() {
    /** Navigation découplée : la feature ne connaît jamais les autres. */
    @Inject
    lateinit var navigator: AppNavigator

    private val viewModel: WizardViewModel by viewModels()

    /** Dernière étape affichée dans le conteneur (garde les transitions). */
    private var etapeAffichee: EtapeId? = null

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentNewprojectBinding = FragmentNewprojectBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Contenu edge-to-edge : la racine absorbe les barres système.
        binding.root.applySystemBarsInsets(top = true, bottom = true)

        (activity as? AppCompatActivity)?.setSupportActionBar(binding.barreOutils)
        binding.barreOutils.setNavigationOnClickListener { demanderFermeture() }

        // Bouton retour système : étape précédente, sinon fermeture
        // confirmée si des données ont été saisies (section 12.2).
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.etat.value.indexEtape > 0) {
                        viewModel.action(ActionWizard.Precedent)
                    } else {
                        demanderFermeture()
                    }
                }
            },
        )

        binding.boutonPrecedent.setOnClickListener { viewModel.action(ActionWizard.Precedent) }
        binding.boutonSuivant.setOnClickListener { viewModel.action(ActionWizard.Suivant) }

        observerEtat()
        observerEffets()
    }

    /** Observe l'état : indicateur, étape courante, barre d'actions. */
    private fun observerEtat() {
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            // Indicateur : progression linéaire + « Étape N sur M · Titre ».
            binding.indicateurEtapes.max = ETAPES_WIZARD.size
            binding.indicateurEtapes.setProgress(etat.indexEtape + 1, true)
            val etape = ETAPES_WIZARD[etat.indexEtape]
            binding.libelleEtape.text =
                getString(
                    R.string.wizard_indicateur_etape,
                    etat.indexEtape + 1,
                    ETAPES_WIZARD.size,
                    getString(etape.titreRes),
                )

            // Étape affichée : remplacée seulement si nécessaire (après
            // rotation, le gestionnaire enfant a déjà restauré la bonne).
            if (etapeAffichee != etat.etape) {
                val classeAttendue = classeEtape(etat.etape)
                val actuel = fragmentEtapeCourante()
                if (actuel == null || actuel.javaClass != classeAttendue) {
                    rendreEtape(transition = etapeAffichee != null)
                }
                etapeAffichee = etat.etape
            }

            // Barre d'actions : Retour masqué sur la première étape ;
            // Suivant masqué sur la dernière étape livrée (étape 11 :
            // Fichiers, Récapitulatif, Créer) et désactivé si invalide.
            binding.boutonPrecedent.isVisible = etat.indexEtape > 0
            binding.boutonSuivant.isVisible = etat.aUneEtapeSuivante
            binding.boutonSuivant.isEnabled = etat.etapeValide
        }
    }

    /** Observe les effets ponctuels (fermeture après abandon confirmé). */
    private fun observerEffets() {
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet ->
            when (effet) {
                EffetWizard.Fermer -> navigator.goBack()
            }
        }
    }

    /** Le fragment d'étape actuellement affiché, ou `null`. */
    private fun fragmentEtapeCourante(): EtapeFragment<*>? =
        childFragmentManager.findFragmentById(R.id.conteneur_etapes) as? EtapeFragment<*>

    /** Classe du fragment d'une étape (garde de restauration). */
    private fun classeEtape(etape: EtapeId): Class<out EtapeFragment<*>> =
        when (etape) {
            EtapeId.MODELE -> EtapeModeleFragment::class.java
            EtapeId.CONFIGURATION -> EtapeConfigurationFragment::class.java
            EtapeId.INFORMATIONS -> EtapeInformationsFragment::class.java
            EtapeId.FICHIERS, EtapeId.RECAPITULATIF -> EtapeModeleFragment::class.java
        }

    /** Affiche le fragment de l'étape courante, avec transition axe X. */
    private fun rendreEtape(transition: Boolean) {
        val etape = ETAPES_WIZARD[viewModel.etat.value.indexEtape]
        etapeAffichee = etape.id
        val fragment: EtapeFragment<*> =
            when (etape.id) {
                EtapeId.MODELE -> EtapeModeleFragment()
                EtapeId.CONFIGURATION -> EtapeConfigurationFragment()
                EtapeId.INFORMATIONS -> EtapeInformationsFragment()
                EtapeId.FICHIERS, EtapeId.RECAPITULATIF -> EtapeModeleFragment()
            }
        if (transition && animationsActivees()) {
            // Transitions Material (axe X) entre étapes, section 12.1 —
            // posées avant la transaction pour animer l'aller comme le retour.
            fragment.enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
            fragment.exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        }
        childFragmentManager.commit(allowStateLoss = true) {
            setReorderingAllowed(true)
            replace(R.id.conteneur_etapes, fragment, ETIQUETTE_ETAPE)
        }
    }

    /** Le réglage système « réduire les animations » est-il inactif ? */
    private fun animationsActivees(): Boolean =
        android.provider.Settings.Global.getFloat(
            requireContext().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f

    /**
     * Demande de fermeture (✕, retour système depuis la première étape) :
     * dialogue de confirmation si des données ont été saisies, fermeture
     * immédiate sinon (section 12.2).
     */
    fun demanderFermeture() {
        if (!viewModel.etat.value.donneesSaisies) {
            viewModel.action(ActionWizard.Fermer)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wizard_abandon_titre)
            .setMessage(R.string.wizard_abandon_message)
            .setNegativeButton(R.string.wizard_abandon_rester, null)
            .setPositiveButton(R.string.wizard_abandon_confirmer) { _, _ ->
                viewModel.action(ActionWizard.Fermer)
            }.show()
    }

    private companion object {
        /** Étiquette du fragment d'étape dans le gestionnaire enfant. */
        const val ETIQUETTE_ETAPE = "wizard.etape"
    }
}
