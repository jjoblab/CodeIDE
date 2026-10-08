package jo.codeide.feature.install

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.EnvironmentSetupState
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.ui.AppNavigator
import jo.codeide.feature.install.databinding.CartePhaseInstallationBinding
import jo.codeide.feature.install.databinding.FragmentInstallationBinding
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Écran d'installation de l'environnement (E5, § 7 — ADR 0090) : stepper
 * vertical de quatre cartes, journal en direct repliable (toujours visible
 * sur tablette sw600dp), consentement licence avant la phase
 * `ANDROID_SDK`, actions contextuelles (masquées hors contexte, jamais
 * grisées), vitesse et temps restant du téléchargement courant. Survit à
 * la rotation et à la mort du processus : l'installation vit dans le
 * service de premier plan (ADR 0087), le fragment ne fait que projeter
 * l'état.
 */
@AndroidEntryPoint
class InstallationFragment : Fragment() {
    private val viewModel: InstallationViewModel by viewModels()

    @Inject
    lateinit var navigateur: AppNavigator

    private var liaisonPrivee: FragmentInstallationBinding? = null
    private val liaison: FragmentInstallationBinding get() = liaisonPrivee!!

    /** La carte licence était-elle visible à la dernière émission ? */
    private var licenceVisible = false

    /** Un défilement vers le bas est déjà planifié (déduplication, ADR 0078). */
    private var defilementPlanifie = false

    /** Tolérance « l'utilisateur est au bas » (une ligne ≈ 16 dp, en px). */
    private val toleranceBasPx: Int by lazy {
        (TOLERANCE_BAS_DP * resources.displayMetrics.density).toInt()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaisonPrivee = FragmentInstallationBinding.inflate(inflater, container, false)
        return liaison.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        liaison.boutonDemarrer.setOnClickListener { viewModel.demarrer() }
        liaison.boutonAnnuler.setOnClickListener { viewModel.annuler() }
        liaison.boutonReessayer.setOnClickListener {
            viewModel.reessayer(
                InstallPhase.entries.firstOrNull { viewModel.etat.value.phase(it) is PhaseState.Failed }
                    ?: return@setOnClickListener,
            )
        }
        liaison.boutonJournal.setOnClickListener { viewModel.basculerJournal() }
        liaison.caseLicence.setOnCheckedChangeListener { _, coche ->
            liaison.boutonDemarrer.isEnabled = coche
        }
        liaison.boutonDiagnostic.setOnClickListener {
            val pressePapiers =
                requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
            pressePapiers?.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    getString(R.string.installation_diagnostic),
                    InstallationViewModel.diagnostic(viewModel.etat.value, viewModel.journal.value),
                ),
            )
        }
        liaison.boutonTerminal.setOnClickListener { navigateur.openTerminal(null) }
        liaison.boutonPremierProjet.setOnClickListener { navigateur.openNewProjectWizard() }
        if (estTablette) {
            // sw600dp : le journal occupe le panneau droit, toujours visible (§ 7).
            liaison.boutonJournal.isVisible = false
        }
        observerLEtat()
    }

    /** Layout tablette (stepper à gauche, journal à droite) ? — figé à la création de la vue. */
    private val estTablette: Boolean by lazy { resources.configuration.screenWidthDp >= SEUIL_TABLETTE_DP }

    /** Projection de l'état : cartes, progression, journal, actions, récapitulatif. */
    private fun observerLEtat() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.etat.collect { etat ->
                        val terminees = InstallationViewModel.phasesTerminees(etat)
                        projeterEnTete(etat, terminees)
                        projeterLicence(etat, terminees)
                        projeterActions(etat)
                        projeterRecapitulatif(etat)
                    }
                }
                launch {
                    viewModel.estimation.collect { estimation ->
                        val texte = estimation?.let(::formaterEstimation)
                        liaison.vitesseTelechargement.isVisible = texte != null
                        liaison.vitesseTelechargement.text = texte ?: ""
                    }
                }
                launch {
                    viewModel.journal.collect { lignes ->
                        // Auto-défilement intelligent (parité avec
                        // PanneauConsoleFragment, ADR 0078) : on ne suit
                        // le bas QUE si l'utilisateur y était déjà avant
                        // la mise à jour. S'il a remonté pour lire
                        // l'historique, on ne le ramène pas en bas —
                        // la nouvelle ligne attendra sagement en
                        // attendant qu'il redescende.
                        val suivreBas = estAuBas(liaison.zoneJournal)
                        liaison.journal.text = lignes.takeLast(NB_LIGNES_JOURNAL).joinToString("\n")
                        if (suivreBas) suivreLeBas(liaison.zoneJournal)
                    }
                }
                launch {
                    viewModel.journalDeplie.collect { deplie ->
                        // Sur tablette le panneau journal est structurel (droite),
                        // jamais masqué ; sur téléphone il suit le pli.
                        if (!estTablette) {
                            liaison.zoneJournal.isVisible = deplie
                        }
                    }
                }
            }
        }
    }

    /** En-tête : compteur « étape N sur 4 », barre, quatre cartes, sous-étape. */
    private fun projeterEnTete(
        etat: EnvironmentSetupState,
        terminees: Int,
    ) {
        liaison.compteurPhases.text =
            getString(R.string.installation_progression, terminees, InstallPhase.entries.size)
        liaison.progressionGlobale.max = InstallPhase.entries.size
        liaison.progressionGlobale.progress = terminees
        afficherCarte(
            liaison.carteBootstrap,
            R.string.installation_phase_bootstrap,
            etat.phase(InstallPhase.BOOTSTRAP),
        )
        afficherCarte(
            liaison.carteOutils,
            R.string.installation_phase_outils,
            etat.phase(InstallPhase.PACKAGE_TOOLS),
        )
        afficherCarte(
            liaison.carteJava,
            R.string.installation_phase_java,
            etat.phase(InstallPhase.JAVA),
        )
        afficherCarte(
            liaison.carteAndroid,
            R.string.installation_phase_android,
            etat.phase(InstallPhase.ANDROID_SDK),
        )
        liaison.sousEtape.text = InstallationViewModel.sousEtapeCourante(etat) ?: ""
        liaison.sousEtape.isVisible = etat.running != null
    }

    /**
     * Consentement licence : carte visible AVANT la phase Android
     * uniquement ; la case n'est réinitialisée qu'à l'APPARITION de la
     * carte — jamais pendant qu'elle est affichée (l'utilisateur coche).
     */
    private fun projeterLicence(
        etat: EnvironmentSetupState,
        terminees: Int,
    ) {
        val avantAndroid =
            etat.phase(InstallPhase.ANDROID_SDK) is PhaseState.NotStarted &&
                etat.sdkLicenseAcceptedAtMillis == null &&
                etat.phase(InstallPhase.JAVA) !is PhaseState.NotStarted
        liaison.carteLicence.isVisible = avantAndroid
        if (avantAndroid && !licenceVisible) {
            liaison.caseLicence.isChecked = false
            liaison.boutonDemarrer.isEnabled = false
            liaison.boutonDemarrer.setText(R.string.installation_installer_sdk)
        }
        licenceVisible = avantAndroid
        liaison.boutonDemarrer.isVisible =
            avantAndroid || (etat.running == null && terminees == 0)
        if (!avantAndroid && terminees == 0 && etat.running == null) {
            liaison.boutonDemarrer.isEnabled = true
            liaison.boutonDemarrer.setText(R.string.installation_demarrer)
        }
    }

    /** Actions contextuelles : masquées hors contexte (§ 7), jamais grisées. */
    private fun projeterActions(etat: EnvironmentSetupState) {
        liaison.boutonAnnuler.isVisible = etat.running != null
        liaison.boutonReessayer.isVisible =
            InstallPhase.entries.any { etat.phase(it) is PhaseState.Failed } && etat.running == null
        liaison.boutonDiagnostic.isVisible = true
        liaison.boutonTerminal.isVisible = true
    }

    /** Récapitulatif final : versions vérifiées + premier projet. */
    private fun projeterRecapitulatif(etat: EnvironmentSetupState) {
        val versions =
            etat.phases.values
                .filterIsInstance<PhaseState.Succeeded>()
                .flatMap { it.versions.entries }
        liaison.boutonPremierProjet.isVisible = etat.estTermine()
        liaison.resumeVersions.text = versions.joinToString("\n") { "${it.key} : ${it.value}" }
        liaison.resumeVersions.isVisible = versions.isNotEmpty()
    }

    /** « 1,2 Mio/s — environ 1 min restante » (mesures, jamais extrapolées). */
    private fun formaterEstimation(estimation: EstimationTelechargement): String {
        val vitesse =
            getString(
                R.string.installation_vitesse_mio_s,
                estimation.octetsParSeconde / MIO.toFloat(),
            )
        val reste =
            estimation.secondesRestantes?.let { secondes ->
                if (secondes < SEUIL_MINUTES) {
                    getString(R.string.installation_duree_secondes, secondes)
                } else {
                    getString(
                        R.string.installation_duree_minutes,
                        (secondes + SEUIL_MINUTES - 1) / SEUIL_MINUTES,
                    )
                }
            }
        return if (reste == null) {
            vitesse
        } else {
            getString(R.string.installation_estimation, vitesse, reste)
        }
    }

    /** État d'une carte : titre, libellé d'état, détail, versions une fois terminée. */
    private fun afficherCarte(
        carte: CartePhaseInstallationBinding,
        titre: Int,
        etat: PhaseState,
    ) {
        carte.titrePhase.setText(titre)
        val (libelleEtat, attributCouleur) =
            when (etat) {
                is PhaseState.Succeeded -> R.string.installation_etat_terminee to androidx.appcompat.R.attr.colorPrimary
                is PhaseState.Degraded -> R.string.installation_etat_degradee to android.R.attr.textColorSecondary
                is PhaseState.Failed -> R.string.installation_etat_echec to androidx.appcompat.R.attr.colorError
                is PhaseState.Running -> R.string.installation_etat_encours to androidx.appcompat.R.attr.colorPrimary
                PhaseState.NotStarted -> R.string.installation_etat_attente to android.R.attr.textColorSecondary
            }
        carte.etatPhase.setText(libelleEtat)
        carte.etatPhase.setTextColor(MaterialColors.getColor(carte.etatPhase, attributCouleur))
        val detail =
            when (etat) {
                is PhaseState.Succeeded -> {
                    etat.versions.entries.joinToString(" · ") { it.value }
                }

                is PhaseState.Degraded -> {
                    carte.etatPhase.context
                        .getString(
                            R.string.installation_avertissements,
                            etat.warnings.joinToString {
                                it.componentId
                            },
                        )
                }

                is PhaseState.Failed -> {
                    etat.error.details
                }

                else -> {
                    ""
                }
            }
        carte.versionsPhase.text = detail
        carte.versionsPhase.isVisible = detail.isNotBlank()
        carte.progressionPhase.isVisible = etat is PhaseState.Running
    }

    /**
     * L'utilisateur est-il au bas du journal ? Sur tablette le panneau
     * droit est toujours visible (`match_parent`) ; sur téléphone la zone
     * est bornée à 160 dp. Si la zone est masquée (GONE, journal replié
     * sur téléphone), on renvoie `true` pour que le défilement ait lieu
     * au prochain dépliage (la nouvelle ligne reste la dernière visible).
     */
    private fun estAuBas(defilement: NestedScrollView): Boolean {
        if (!defilement.isVisible) return true
        val contenu = defilement.getChildAt(0) ?: return true
        return defilement.scrollY >= contenu.height - defilement.height - toleranceBasPx
    }

    /**
     * Défile vers le bas (dédupliqué : un `post` vivant au plus). Le
     * `post` attend la passe de layout pour que le scroll tienne compte
     * de la nouvelle hauteur du TextView — sans quoi `fullScroll`
     * s'arrêterait une ligne trop tôt.
     */
    private fun suivreLeBas(defilement: NestedScrollView) {
        if (defilementPlanifie) return
        defilementPlanifie = true
        defilement.post {
            defilementPlanifie = false
            defilement.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        defilementPlanifie = false
        liaisonPrivee = null
    }

    private companion object {
        /** Journal affiché borné (le flux est déjà borné à 200 par le cadre). */
        private const val NB_LIGNES_JOURNAL: Int = 200

        /** Seuil tablette deux panneaux (sw600dp, § 7). */
        private const val SEUIL_TABLETTE_DP: Int = 600

        /** Bornage minute entière (60 s → 1 min). */
        private const val SEUIL_MINUTES: Long = 60L

        private const val MIO: Long = 1024L * 1024

        /**
         * Tolérance « au bas » du journal (en dp) : une ligne de texte
         * monospace `bodySmall` ≈ 16 dp — sous cette marge, l'utilisateur
         * est considéré au bas et l'auto-défilement reste actif.
         */
        private const val TOLERANCE_BAS_DP: Int = 16
    }
}
