package jo.codeide.feature.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.onboarding.databinding.FragmentOnboardingBinding
import javax.inject.Inject

/**
 * Hôte de l'assistant de premier lancement (étape 5) : pager non swipable
 * des pages, indicateur de progression et barre de navigation.
 *
 * Le fragment ne fait que **rendre l'état** et **émettre des actions**
 * (section 5.3) : les changements de page partent du ViewModel, et
 * l'animation [MaterialSharedAxis] (axe Z, sens du parcours) accompagne
 * chaque transition. Le bouton retour système recule d'une page au lieu
 * de quitter l'assistant.
 */
@AndroidEntryPoint
class OnboardingFragment : BaseFragment<FragmentOnboardingBinding>() {
    private val viewModel: OnboardingViewModel by viewModels()

    /** Navigation découplée : la feature ne connaît jamais les autres. */
    @Inject
    lateinit var navigator: AppNavigator

    /** Retour système : recule d'une page, désarmé sur la bienvenue. */
    private val retourPage: OnBackPressedCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                viewModel.onAction(ActionOnboarding.PagePrecedente)
            }
        }

    /** Sélecteur SAF du dossier de travail (section 5.6). */
    private val selecteurDossier =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                viewModel.onAction(ActionOnboarding.DossierChoisi(uri.toString()))
            }
        }

    /**
     * Requête directe d'autorisation de notification (page Notifications,
     * v0.31.2) : utile sous Android 13+ ; la cible 28 (ADR 0045) laisse
     * le système accorder par défaut — l'état réel est relevé au retour,
     * quelle que soit la voie (dialogue système, réglages).
     */
    private val requeteNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            viewModel.onAction(ActionOnboarding.ConsignerNotifications(etatNotifications(requireContext())))
        }

    /**
     * Requête runtime du stockage partagé (v0.31.3, ADR 0047 —
     * Android < 11 : READ+WRITE, même groupe de permissions). Sous
     * Android 11+, [OuvrirAutorisationStockage] mène au réglage « Tous
     * les fichiers » (ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) et
     * ce lanceur n'est pas utilisé.
     */
    private val requeteStockage =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            viewModel.onAction(ActionOnboarding.ConsignerStockage(etatStockagePartage(requireContext())))
        }

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentOnboardingBinding = FragmentOnboardingBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Contenu edge-to-edge : la racine absorbe barres système et clavier
        // — la barre d'actions « Commencer / Suivant » doit rester visible
        // et cliquable au-dessus de la barre de navigation comme du clavier.
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, retourPage)

        binding.pagerPages.isUserInputEnabled = false
        binding.pagerPages.adapter = OnboardingPagerAdapter(this)

        binding.boutonPrecedent.setOnClickListener {
            viewModel.onAction(ActionOnboarding.PagePrecedente)
        }
        binding.boutonSuivant.setOnClickListener {
            viewModel.onAction(ActionOnboarding.PageSuivante)
        }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet -> appliquer(effet) }
    }

    /** Rendu de l'état : page courante, progression, barre de navigation. */
    private fun rendre(etat: EtatOnboarding) {
        binding.progression.max = PageOnboarding.entries.size
        binding.progression.progress = etat.page.ordinal + 1
        allerPage(etat.page)

        retourPage.isEnabled = etat.page != PageOnboarding.BIENVENUE
        binding.boutonPrecedent.isVisible = etat.page != PageOnboarding.BIENVENUE
        binding.boutonSuivant.setText(libelleSuivant(etat.page))
        // Finalisation en vol : le bouton reste inerte le temps de l'écriture
        // (garde anti double-appui du ViewModel, miroir visuel).
        binding.boutonSuivant.isEnabled = !etat.finalisation
    }

    /** Change la page du pager avec l'animation MaterialSharedAxis. */
    private fun allerPage(page: PageOnboarding) {
        val cible = page.ordinal
        if (binding.pagerPages.currentItem == cible) return

        val enAvant = cible > binding.pagerPages.currentItem
        val axe =
            MaterialSharedAxis(MaterialSharedAxis.Z, enAvant).apply {
                duration = DUREE_AXE_MS
            }
        TransitionManager.beginDelayedTransition(binding.pagerPages, axe)
        binding.pagerPages.setCurrentItem(cible, false)
    }

    /** Libellé du bouton d'action selon la page (commencer, suivant, finir). */
    private fun libelleSuivant(page: PageOnboarding): Int =
        when (page) {
            PageOnboarding.BIENVENUE -> R.string.onboarding_commencer
            PageOnboarding.TERMINE -> R.string.onboarding_terminer
            else -> R.string.onboarding_suivant
        }

    /** Application des effets ponctuels (section 5.3). */
    private fun appliquer(effet: EffetOnboarding) {
        when (effet) {
            EffetOnboarding.OuvrirSelecteurDossier -> {
                selecteurDossier.launch(null)
            }

            EffetOnboarding.OuvrirInstallation -> {
                navigator.openBootstrapInstall()
            }

            EffetOnboarding.OuvrirAutorisationNotifications -> {
                demanderNotifications()
            }

            EffetOnboarding.OuvrirReglagesNotifications -> {
                ouvrirReglages(Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                    putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                }
            }

            EffetOnboarding.OuvrirAutorisationStockage -> {
                demanderStockage()
            }

            EffetOnboarding.OuvrirReglagesStockage -> {
                ouvrirReglages(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
            }

            EffetOnboarding.RetourAccueil -> {
                navigator.openHome()
            }
        }
    }

    /**
     * Requête d'autorisation de notification : directe sous Android 13+,
     * relève immédiate de l'état réel sinon (accordée par défaut pour
     * une cible ≤ 32 — l'état fait foi, pas la théorie).
     */
    private fun demanderNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requeteNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.onAction(ActionOnboarding.ConsignerNotifications(etatNotifications(requireContext())))
        }
    }

    /**
     * Demande d'accès au stockage partagé (v0.31.3, ADR 0047) : le
     * réglage système « Tous les fichiers » sous Android 11+ (la
     * permission runtime n'y suffit plus), la requête runtime
     * READ+WRITE sinon (cible 28 = stockage legacy, ADR 0045).
     *
     * Termux, même contrainte cible 28, fait exactement ce choix — le
     * modèle est éprouvé sur le terrain.
     */
    private fun demanderStockage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intention =
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}"),
                )
            startActivity(intention)
        } else {
            requeteStockage.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    /**
     * Ouvre une page de réglages système — repli commun après un refus
     * (notifications v0.31.2, stockage v0.31.3, ADR 0046/0047) : la
     * page réglages des notifications directement pour celle-ci, la
     * fiche de l'application pour le stockage. Tous les droits y
     * restent réversibles — la page relit l'état réel au retour
     * ([NotificationsPage.onResume]).
     */
    private fun ouvrirReglages(
        action: String,
        configurer: Intent.() -> Unit,
    ) {
        val intention = Intent(action).apply(configurer)
        startActivity(intention)
    }

    private companion object {
        /** Durée de l'animation d'axe partagé entre pages. */
        const val DUREE_AXE_MS = 300L
    }
}
