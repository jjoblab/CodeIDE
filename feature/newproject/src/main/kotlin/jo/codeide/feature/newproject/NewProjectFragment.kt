package jo.codeide.feature.newproject

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
 * Hôte du wizard de création de projet (étapes 10-11 — section 12.2, ADR
 * 0155) : barre d'outils (fermeture), **indicateur d'étapes** (progression +
 * libellé « Étape N sur M », M lu dans le **parcours effectif** calculé par
 * le domaine), conteneur des fragments d'étapes et **barre d'actions fixe**
 * (Retour / Aperçu / Suivant — « Créer le projet » sur le récapitulatif).
 *
 * Les fragments d'étapes viennent d'un **registre** (ADR 0155 §5) : les
 * quatre étapes socle ont chacune leur fragment, toute étape de modèle —
 * déclarée ou par défaut — est rendue par le fragment générique
 * [EtapeParametresFragment] ; un manifeste qui déclare une nouvelle étape
 * s'affiche sans toucher au cadre.
 *
 * L'**écran de création** (hors numérotation) remplace les étapes et la
 * barre d'actions tant que [EtatCreation] n'est pas inactif. L'**aperçu en
 * direct** (L5d) s'affiche en panneau latéral sur tablette (sw600dp) ou en
 * feuille basse sur téléphone.
 *
 * Le [WizardViewModel] est scopé à cet hôte — les fragments d'étapes le
 * partagent (`viewModels({ requireParentFragment() })`) et ne portent aucun
 * état propre : la rotation et la mort du processus survivent par l'état
 * unique et le `SavedStateHandle`.
 *
 * Le bouton retour système referme l'aperçu, sinon revient à l'étape
 * précédente ; depuis la première étape — ou depuis la fermeture avec
 * données saisies — un dialogue de confirmation « Abandonner la création ? »
 * s'affiche (section 12.2). L'abandon confirmé relâche l'emplacement
 * éphémère via le ViewModel avant l'effet de fermeture.
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Contenu edge-to-edge : la racine absorbe les barres système.
        binding.root.applySystemBarsInsets(top = true, bottom = true)

        (activity as? AppCompatActivity)?.setSupportActionBar(binding.barreOutils)
        binding.barreOutils.setNavigationOnClickListener { demanderFermeture() }

        // Bouton retour système : referme l'aperçu en direct, annule une
        // création en cours (rollback domaine), sinon revient d'une étape,
        // sinon fermeture confirmée si des données ont été saisies.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        viewModel.etat.value.apercuOuvert -> viewModel.action(ActionWizard.FermerApercu)

                        viewModel.etat.value.etatCreation != EtatCreation.Inactif -> {
                            viewModel.action(ActionWizard.AnnulerCreation)
                        }

                        viewModel.etat.value.indexEtape > 0 -> {
                            viewModel.action(ActionWizard.Precedent)
                        }

                        else -> {
                            demanderFermeture()
                        }
                    }
                }
            },
        )

        binding.boutonPrecedent.setOnClickListener { viewModel.action(ActionWizard.Precedent) }
        binding.boutonApercu.setOnClickListener { viewModel.action(ActionWizard.OuvrirApercu) }
        binding.boutonSuivant.setOnClickListener {
            val etat = viewModel.etat.value
            if (etat.estRecapitulatif) {
                viewModel.action(ActionWizard.Creer)
            } else {
                viewModel.action(ActionWizard.Suivant)
            }
        }

        observerEtat()
        observerEffets()
    }

    /** Observe l'état : indicateur, écran courant, barre d'actions, aperçu. */
    private fun observerEtat() {
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            // Écran de création (hors numérotation) : il remplace tout.
            val creation = etat.etatCreation != EtatCreation.Inactif
            binding.conteneurIndicateur.isVisible = !creation
            binding.barreActions.isVisible = !creation
            if (creation) {
                afficherEcranCreation()
                return@collectWithLifecycle
            }

            // Indicateur : progression linéaire + « Étape N sur M · Titre »
            // — M est la taille du parcours effectif (ADR 0155 §3).
            binding.indicateurEtapes.max = etat.parcours.size.coerceAtLeast(1)
            binding.indicateurEtapes.setProgress(etat.indexEtape + 1, true)
            val titreEtape =
                etat.etapeCourante?.let { etat.titreEtape(requireContext(), it) } ?: ""
            binding.libelleEtape.text =
                getString(
                    R.string.wizard_indicateur_etape,
                    etat.indexEtape + 1,
                    etat.parcours.size.coerceAtLeast(1),
                    titreEtape,
                )

            // Étape affichée : remplacée seulement si nécessaire (après
            // rotation, le gestionnaire enfant a déjà restauré la bonne).
            if (etapeAffichee != etat.etape) {
                val actuel = fragmentEtapeCourante()
                if (actuel == null || doitRemplacer(actuel, etat.etape)) {
                    rendreEtape(transition = etapeAffichee != null)
                }
                etapeAffichee = etat.etape
            }

            // Barre d'actions : Retour masqué sur la première étape ;
            // Aperçu offert sur les étapes à paramètres (ADR 0155 §6) ;
            // le bouton principal devient « Créer le projet » sur le
            // récapitulatif (section 12.3), désactivé si l'étape est
            // invalide.
            binding.boutonPrecedent.isVisible = etat.indexEtape > 0
            binding.boutonApercu.isVisible =
                !etat.estRecapitulatif && etat.etapeAvecParametres && etat.templateId != null
            binding.boutonSuivant.isVisible = true
            binding.boutonSuivant.setText(
                if (etat.estRecapitulatif) R.string.wizard_bouton_creer else R.string.wizard_bouton_suivant,
            )
            binding.boutonSuivant.isEnabled = etat.etapeValide

            // Aperçu en direct : panneau latéral (tablette) ou feuille basse
            // (téléphone) — un seul mécanisme par forme d'appareil.
            rendreApercu(etat.apercuOuvert)
        }
    }

    /** Observe les effets ponctuels (fermeture, création réussie). */
    private fun observerEffets() {
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet ->
            when (effet) {
                EffetWizard.Fermer -> navigator.goBack()
                is EffetWizard.ProjetCree -> navigator.wizardCreeProjet(effet.id.value)
                is EffetWizard.OuvrirProjetEditeur -> navigator.openEditor(effet.id.value)
            }
        }
    }

    /** Le fragment d'étape actuellement affiché, ou `null`. */
    private fun fragmentEtapeCourante(): EtapeFragment<*>? =
        childFragmentManager.findFragmentById(R.id.conteneur_etapes) as? EtapeFragment<*>

    /**
     * Le fragment affiché doit-il être remplacé ? Vrai si sa classe ne
     * correspond pas à l'étape — deux étapes de modèle partagent la classe
     * générique : leur **identifiant** tranche (ADR 0155 §5).
     */
    private fun doitRemplacer(actuel: EtapeFragment<*>, etape: EtapeId): Boolean {
        val classeAttendue = classeEtape(etape)
        if (actuel.javaClass != classeAttendue) return true
        return etape is EtapeId.EtapeModele &&
            (actuel as? EtapeParametresFragment)?.idEtape != etape.id
    }

    /** Classe du fragment d'une étape (socle, ou générique). */
    private fun classeEtape(etape: EtapeId): Class<out EtapeFragment<*>> = when (etape) {
        EtapeId.Modele -> EtapeModeleFragment::class.java
        EtapeId.Identite -> EtapeIdentiteFragment::class.java
        EtapeId.Fichiers -> EtapeFichiersFragment::class.java
        EtapeId.Recapitulatif -> EtapeRecapitulatifFragment::class.java
        is EtapeId.EtapeModele -> EtapeParametresFragment::class.java
    }

    /** Affiche le fragment de l'étape courante, avec transition axe X. */
    private fun rendreEtape(transition: Boolean) {
        val etat = viewModel.etat.value
        val etape = etat.etape
        etapeAffichee = etape
        val fragment: EtapeFragment<*> =
            when (etape) {
                EtapeId.Modele -> EtapeModeleFragment()
                EtapeId.Identite -> EtapeIdentiteFragment()
                EtapeId.Fichiers -> EtapeFichiersFragment()
                EtapeId.Recapitulatif -> EtapeRecapitulatifFragment()
                is EtapeId.EtapeModele -> EtapeParametresFragment.nouvelleInstance(etape.id)
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

    /**
     * Affiche ou masque l'aperçu en direct (L5d) : panneau latéral embarqué
     * sur tablette (deux colonnes — le parcours borné se centre à nouveau
     * quand le panneau se replie), feuille basse sur téléphone.
     */
    private fun rendreApercu(ouvert: Boolean) {
        if (apercuEnPanneau()) {
            // Le champ n'existe que dans la variante sw600dp : nullable.
            binding.conteneurApercu?.isVisible = ouvert
            centrerColonneParcours(!ouvert)
            val actuel = childFragmentManager.findFragmentById(R.id.conteneur_apercu)
            if (ouvert && actuel == null) {
                childFragmentManager.commit(allowStateLoss = true) {
                    setReorderingAllowed(true)
                    add(R.id.conteneur_apercu, ApercuPlanFragment())
                }
            } else if (!ouvert && actuel != null) {
                childFragmentManager.commit(allowStateLoss = true) {
                    setReorderingAllowed(true)
                    remove(actuel)
                }
            }
        } else {
            val feuille = childFragmentManager.findFragmentByTag(ETIQUETTE_APERCU)
            if (ouvert && feuille == null) {
                ApercuFeuilleFragment().show(childFragmentManager, ETIQUETTE_APERCU)
            } else if (!ouvert && feuille is ApercuFeuilleFragment && feuille.isAdded) {
                feuille.dismissAllowingStateLoss()
            }
        }
    }

    /** Recentre la colonne bornée du parcours quand l'aperçu est fermé. */
    private fun centrerColonneParcours(centrer: Boolean) {
        val colonne = binding.conteneurIndicateur.parent as? LinearLayout ?: return
        colonne.gravity =
            if (centrer) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.NO_GRAVITY
    }

    /** L'aperçu est-il rendu en panneau latéral (tablette) ? */
    private fun apercuEnPanneau(): Boolean = resources.getBoolean(R.bool.apercu_en_panneau)

    /**
     * Appelé par la feuille basse (glissement de fermeture, retour
     * système) : l'état suit — sans re-déclencher la fermeture en boucle.
     */
    fun notifierFermetureApercu() {
        if (viewModel.etat.value.apercuOuvert) {
            viewModel.action(ActionWizard.FermerApercu)
        }
    }

    /**
     * Affiche l'écran de création dans le même conteneur (une seule
     * instance) ; sans animation — l'état visuel suffit (section 12.1 :
     * animation sobre).
     */
    private fun afficherEcranCreation() {
        val actuel = childFragmentManager.findFragmentById(R.id.conteneur_etapes)
        if (actuel is EcranCreationFragment) return
        // Le retour au wizard (annulation, échec) devra réafficher l'étape :
        // la mémoire « étape affichée » est invalidée volontairement.
        etapeAffichee = null
        childFragmentManager.commit(allowStateLoss = true) {
            setReorderingAllowed(true)
            replace(R.id.conteneur_etapes, EcranCreationFragment(), ETIQUETTE_ETAPE)
        }
    }

    /** Le réglage système « réduire les animations » est-il inactif ? */
    private fun animationsActivees(): Boolean = android.provider.Settings.Global.getFloat(
        requireContext().contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) != 0f

    /**
     * Demande de fermeture (✕, retour système depuis la première étape) :
     * dialogue de confirmation si des données ont été saisies, fermeture
     * immédiate sinon (section 12.2). Pendant une création, ✕ annule la
     * création (rollback) au lieu de fermer le wizard.
     */
    fun demanderFermeture() {
        when (viewModel.etat.value.etatCreation) {
            is EtatCreation.EnCours -> viewModel.action(ActionWizard.AnnulerCreation)
            is EtatCreation.Succes -> viewModel.action(ActionWizard.RetourAccueil)
            is EtatCreation.Echec -> viewModel.action(ActionWizard.RetourRecapitulatif)
            EtatCreation.Inactif -> confirmerAbandonEtapes()
        }
    }

    /** Fermeture depuis les étapes : confirmation si des données sont saisies. */
    private fun confirmerAbandonEtapes() {
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

        /** Étiquette de la feuille basse de l'aperçu (téléphone). */
        const val ETIQUETTE_APERCU = "wizard.apercu"
    }
}
