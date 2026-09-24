package jo.codeide.feature.install

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.AppError
import jo.codeide.core.model.EtapeInstallation
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.install.databinding.FragmentInstallBinding
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Écran d'installation du bootstrap natif (étape T3, prompt Terminal-1
 * sections 3.4 et 6).
 *
 * **Écran de progression partagé** : l'état vit dans le singleton du
 * domaine ([jo.codeide.core.domain.BootstrapInstaller]) — ouvrir cet
 * écran depuis l'onboarding ou le bandeau de l'accueil pendant une
 * installation déjà lancée y affiche la même progression, la refermer
 * ne l'interrompt pas.
 *
 * Refonte v0.31.2 (rapport d'appareil réel : « la configuration des
 * paquets a échoué » sans indice) : la progression montre **ce qui se
 * fait réellement** — checklist des neuf étapes du pipeline (terminée /
 * en cours avec rotation / en attente), compteur de l'étape courante
 * (octets, fichiers, paquet) et **journal en direct** de la sortie des
 * sous-processus (second stage, apt). À l'échec, le message actionnable
 * reste court et les détails techniques (code de sortie, dernières
 * lignes d'erreur) se déplient sous pli : la prochaine panne sur
 * l'appareil est diagnostiquable depuis l'écran.
 *
 * Le retour système referme l'écran sans jamais interrompre une
 * installation en cours : l'annulation est un choix explicite.
 *
 * Exemption detekt ciblée (règle 16 du prompt maître, précédent
 * OnboardingViewModel) : la refonte v0.31.2 rend cinq phases (invite,
 * progression détaillée, journal, résultat, échec) — chaque zone
 * d'affichage a son gestionnaire privé cohésif, l'éclater nuirait à
 * la localité du rendu par phase.
 */
@AndroidEntryPoint
@Suppress("TooManyFunctions")
class InstallFragment : BaseFragment<FragmentInstallBinding>() {
    private val viewModel: InstallViewModel by viewModels()

    /** Rangée de checklist gonflée : ses trois vues pilotables. */
    private data class RangeeEtape(
        val icone: ImageView,
        val rotation: com.google.android.material.progressindicator.CircularProgressIndicator,
        val libelle: TextView,
    )

    private val rangees = mutableListOf<RangeeEtape>()

    /** Pli des détails techniques (état d'affichage éphémère). */
    private var detailsOuverts = false

    private companion object {
        /** Passage d'une fraction 0..1 vers l'échelle entière de la barre. */
        private const val ECHELLE_POURCENT = 100

        /** Octets par mébioctet (formats humains de la progression). */
        private const val OCTETS_PAR_MO = 1024.0

        /** Mébioctets par gibioctet. */
        private const val OCTETS_PAR_GO = 1024.0

        /** Opacité d'une étape en attente dans la checklist. */
        private const val ALPHA_ETAPE_ATTENTE = 0.55f

        /** Opacité des étapes terminée et courante. */
        private const val ALPHA_ETAPE_ACTIVE = 1f

        /** Modèles d'étapes de la checklist, dans l'ordre du pipeline. */
        private val MODELES_CHECKLIST =
            listOf(
                EtapeInstallation.VerificationEspaceDisque,
                EtapeInstallation.Telechargement(0, null),
                EtapeInstallation.Extraction(0),
                EtapeInstallation.LiensSymboliques,
                EtapeInstallation.BasculeVersPrefixe,
                EtapeInstallation.SecondStage,
                EtapeInstallation.ConfigurationApt,
                EtapeInstallation.MiseAJourApt,
                EtapeInstallation.InstallationPaquets("", 0, 0),
            )
    }

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentInstallBinding = FragmentInstallBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.boutonInstaller.setOnClickListener { viewModel.onAction(ActionInstallation.Installer) }
        binding.boutonAnnuler.setOnClickListener { viewModel.onAction(ActionInstallation.Annuler) }
        binding.boutonFermer.setOnClickListener { findNavController().popBackStack() }
        binding.boutonDetails.setOnClickListener {
            detailsOuverts = !detailsOuverts
            rendreDetails()
        }

        construireChecklist()

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rendu complet de l'état : une phase visible à la fois. */
    private fun rendre(etat: EtatInstallation) {
        binding.invite.isVisible = etat.phase == PhaseInstallation.INVITE || etat.phase == PhaseInstallation.ANNULEE
        binding.progression.isVisible = etat.phase == PhaseInstallation.PROGRESSION
        binding.resultat.isVisible = etat.phase == PhaseInstallation.TERMINEE
        binding.echec.isVisible = etat.phase == PhaseInstallation.ECHEC
        binding.texteAnnulee.isVisible = etat.phase == PhaseInstallation.ANNULEE

        when (etat.phase) {
            PhaseInstallation.INVITE, PhaseInstallation.ANNULEE -> {
                binding.boutonInstaller.isVisible = true
                binding.boutonInstaller.setText(
                    if (etat.phase == PhaseInstallation.ANNULEE) {
                        R.string.installation_reessayer
                    } else {
                        R.string.installation_installer
                    },
                )
                binding.carteJournal.isVisible = false
            }

            PhaseInstallation.PROGRESSION -> {
                binding.boutonInstaller.isVisible = false
                binding.boutonAnnuler.isVisible = true
                binding.texteEtape.setText(libelleEtape(etat.libelleEtape))
                rendreDetailEtape(etat.libelleEtape)
                val progression = etat.progressionTelechargement
                if (progression != null) {
                    binding.barreProgression.isIndeterminate = false
                    binding.barreProgression.progress = (progression * ECHELLE_POURCENT).roundToInt()
                } else {
                    binding.barreProgression.isIndeterminate = true
                }
                rendreChecklist(etat.libelleEtape)
                rendreJournal(etat, defiler = true)
            }

            PhaseInstallation.TERMINEE -> {
                binding.boutonAnnuler.isVisible = false
                binding.carteJournal.isVisible = false
                rendreOutils(etat.outils)
            }

            PhaseInstallation.ECHEC -> {
                binding.boutonAnnuler.isVisible = false
                binding.texteErreur.setText(messageErreur(etat.erreur))
                binding.boutonDetails.isVisible = etat.detailsEchec != null
                rendreDetails()
                // L'échec garde le journal ouvert : c'est la sortie EN
                // ÉCHEC qui diagnostique (« la configuration des paquets
                // a échoué » sans elle ne veut rien dire).
                rendreJournal(etat, defiler = true)
            }
        }
    }

    /** Résumé des outils installés (paquet par paquet). */
    private fun rendreOutils(outils: List<jo.codeide.core.model.OutilResume>) {
        if (outils.isEmpty()) {
            binding.texteOutils.setText(R.string.installation_outils_aucun)
            return
        }
        binding.texteOutils.text =
            outils.joinToString(separator = "\n") { outil ->
                val suffixe =
                    getString(
                        if (outil.installe) {
                            R.string.installation_outil_installe
                        } else {
                            R.string.installation_outil_absent
                        },
                    )
                "${outil.paquet} — $suffixe"
            }
    }

    /** Gonfle les neuf rangées de la checklist (une fois par vue). */
    private fun construireChecklist() {
        val libelles =
            listOf(
                R.string.installation_etape_verification,
                R.string.installation_etape_telechargement,
                R.string.installation_etape_extraction,
                R.string.installation_etape_liens,
                R.string.installation_etape_bascule,
                R.string.installation_etape_second_stage,
                R.string.installation_etape_sources,
                R.string.installation_etape_apt,
                R.string.installation_etape_paquets,
            )
        val gonfleur = LayoutInflater.from(requireContext())
        for (libelle in libelles) {
            val rangee = gonfleur.inflate(R.layout.rangee_etape_install, binding.conteneurEtapes, true)
            rangees +=
                RangeeEtape(
                    icone = rangee.findViewById(R.id.icone_etape),
                    rotation = rangee.findViewById(R.id.rotation_etape),
                    libelle = rangee.findViewById(R.id.libelle_etape),
                )
            rangee.findViewById<TextView>(R.id.libelle_etape).setText(libelle)
        }
    }

    /** Met à jour la checklist : terminées, courante (rotation), en attente. */
    private fun rendreChecklist(etape: EtapeInstallation?) {
        val indexCourant = etape?.let(::positionChecklist) ?: -1
        for ((index, rangee) in rangees.withIndex()) {
            val faite = index < indexCourant
            val courante = index == indexCourant
            rangee.icone.isVisible = !courante
            rangee.rotation.isVisible = courante
            rangee.icone.setImageResource(if (faite) R.drawable.etape_faite else R.drawable.etape_attente)
            rangee.icone.imageTintList = ColorStateList.valueOf(couleurEtat(faite))
            rangee.libelle.alpha = if (faite || courante) ALPHA_ETAPE_ACTIVE else ALPHA_ETAPE_ATTENTE
            rangee.libelle.paint.isFakeBoldText = courante
        }
    }

    /** Couleur de l'icône d'état (réalisée = vert, sinon discret). */
    private fun couleurEtat(faite: Boolean): Int =
        androidx.core.content.ContextCompat.getColor(
            requireContext(),
            if (faite) R.color.vert_etape_faite else R.color.gris_etape_attente,
        )

    /** Position d'une étape du pipeline dans la checklist (même classe). */
    private fun positionChecklist(etape: EtapeInstallation): Int =
        MODELES_CHECKLIST.indexOfFirst { it::class == etape::class }.coerceAtLeast(0)

    /** Compteur de l'étape courante (octets, fichiers, paquet). */
    private fun rendreDetailEtape(etape: EtapeInstallation?) {
        val texte =
            when (etape) {
                is EtapeInstallation.Telechargement -> {
                    val recus = formaterOctets(etape.octetsRecus)
                    val total = etape.octetsTotaux
                    if (total != null && total > 0) {
                        getString(
                            R.string.installation_detail_telechargement,
                            recus,
                            formaterOctets(total),
                        )
                    } else {
                        recus
                    }
                }

                is EtapeInstallation.Extraction -> {
                    getString(R.string.installation_detail_extraction, etape.entreesTraitees)
                }

                is EtapeInstallation.InstallationPaquets -> {
                    getString(
                        R.string.installation_detail_paquet,
                        etape.paquet,
                        etape.index,
                        etape.total,
                    )
                }

                else -> {
                    null
                }
            }
        binding.texteDetailEtape.isVisible = texte != null
        binding.texteDetailEtape.text = texte
    }

    /** Journal en direct : texte complet + défilement vers le bas. */
    private fun rendreJournal(
        etat: EtatInstallation,
        defiler: Boolean,
    ) {
        binding.carteJournal.isVisible = etat.journal.isNotEmpty()
        if (etat.journal.isEmpty()) return
        binding.texteJournal.text = etat.journal.joinToString(separator = "\n")
        if (defiler) {
            binding.defilementJournal.post {
                binding.defilementJournal.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    /** Pli des détails techniques. */
    private fun rendreDetails() {
        binding.texteDetails.isVisible = detailsOuverts
        binding.texteDetails.text = viewModel.etat.value.detailsEchec
    }

    /** Libellé localisé de l'étape en cours. */
    private fun libelleEtape(etape: EtapeInstallation?): Int =
        when (etape) {
            null, EtapeInstallation.VerificationEspaceDisque -> R.string.installation_etape_verification
            is EtapeInstallation.Telechargement -> R.string.installation_etape_telechargement
            is EtapeInstallation.Extraction -> R.string.installation_etape_extraction
            EtapeInstallation.LiensSymboliques -> R.string.installation_etape_liens
            EtapeInstallation.BasculeVersPrefixe -> R.string.installation_etape_bascule
            EtapeInstallation.SecondStage -> R.string.installation_etape_second_stage
            EtapeInstallation.ConfigurationApt -> R.string.installation_etape_sources
            EtapeInstallation.MiseAJourApt -> R.string.installation_etape_apt
            is EtapeInstallation.InstallationPaquets -> R.string.installation_etape_paquets
        }

    /** Message actionnable d'une erreur typée (les détails partent au journal). */
    private fun messageErreur(erreur: AppError?): Int =
        when ((erreur as? AppError.Bootstrap)?.reason) {
            AppError.BootstrapReason.ReseauIndisponible -> {
                R.string.installation_erreur_reseau
            }

            AppError.BootstrapReason.EspaceDisqueInsuffisant -> {
                R.string.installation_erreur_espace
            }

            AppError.BootstrapReason.ArchiveCorrompue, AppError.BootstrapReason.EmpreinteInvalide -> {
                R.string.installation_erreur_archive
            }

            AppError.BootstrapReason.PermissionRefusee -> {
                R.string.installation_erreur_permission
            }

            AppError.BootstrapReason.EchecSecondStage, AppError.BootstrapReason.EchecApt -> {
                R.string.installation_erreur_paquets
            }

            AppError.BootstrapReason.AssetAbsent -> {
                R.string.installation_erreur_asset
            }

            AppError.BootstrapReason.ArchitectureNonSupportee -> {
                R.string.installation_erreur_architecture
            }

            else -> {
                R.string.installation_erreur_inconnue
            }
        }

    /** Format humain d'un compteur d'octets (Mo, Go). */
    private fun formaterOctets(octets: Long): String {
        val mo = octets / (OCTETS_PAR_MO * OCTETS_PAR_MO)
        return if (mo >= OCTETS_PAR_GO) {
            String.format(Locale.getDefault(), "%.1f Go", mo / OCTETS_PAR_GO)
        } else {
            String.format(Locale.getDefault(), "%.1f Mo", mo)
        }
    }
}
