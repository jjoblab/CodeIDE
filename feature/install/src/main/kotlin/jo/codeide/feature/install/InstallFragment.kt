package jo.codeide.feature.install

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.progressindicator.CircularProgressIndicator
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.AppError
import jo.codeide.core.model.EtapeInstallation
import jo.codeide.core.terminalruntime.TerminalRuntime
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.install.databinding.FragmentInstallBinding
import jo.codeide.feature.install.databinding.RangeeOutilInstallBinding
import java.util.Locale
import javax.inject.Inject
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
 * Refonte v0.31.4 (rapport d'appareil réel : « l'écran ne se met pas à
 * jour correctement… un design plus propre » + ADR 0048) :
 * - la progression est **structurée en deux sections** —
 *   « Environnement de base » (huit étapes obligatoires, jusqu'à
 *   `apt update`) et « Outils de développement » (paquets optionnels,
 *   installés à la demande) ;
 * - le journal en direct ne s'efface plus entre les étapes (état et
 *   journal combinés côté ViewModel) ;
 * - les états terminaux montrent ENFIN leurs actions : « Fermer » et
 *   « Installer les outils maintenant » à la réussite de la base,
 *   « Réessayer » à l'échec (v0.31.2 laissait l'écran sans aucun
 *   bouton une fois l'installation lancée) ;
 * - l'échec **des outils** est un état distinct : la base reste
 *   installée, seule la phase d'outils est reprise.
 *
 * v0.52.0 (ADR 0083, comportement demandé : « une fois que le bootstrap
 * installé et pkg update, la configuration de l'environnement avec
 * l'installation de java, android sdk, etc. ») : la réussite de la base
 * **déclenche la configuration automatique** — la commande `codeide-env`
 * est « tapée » dans une session dédiée ; le repli par paquets (OpenJDK
 * seul — git retiré) ne sert que si la session n'a pas pu être créée.
 *
 * v0.54.0 (retour utilisateur : « pour le journal live, il fallait le
 * remplacer complètement par un mini écran TerminalView et non créer
 * une nouvelle session terminal ») : le journal live de la
 * configuration est un **mini TerminalView intégré à cet écran** —
 * [brancherTerminal] y attache la session (le pty reste le moteur : un
 * TerminalView ne rend qu'une session vivante), le rendu suit les
 * sorties au fil de l'eau et le toucher ouvre le clavier (la session
 * est interactive). Plus de bascule vers TerminalActivity, plus de
 * bouton « Ouvrir le terminal » : le journal TextView ne survit qu'à
 * la phase de BASE (le shell n'existe pas avant l'extraction du
 * bootstrap — aucun terminal n'y serait rendable).
 *
 * Le retour système referme l'écran sans jamais interrompre une
 * installation en cours : l'annulation est un choix explicite.
 *
 * Exemption detekt ciblée (règle 16 du prompt maître, précédent
 * v0.31.2) : le rendu par zone (invite, progression, journal, résultat,
 * échec) — chaque zone d'affichage a son gestionnaire privé cohésif.
 */
@AndroidEntryPoint
@Suppress("TooManyFunctions")
class InstallFragment : BaseFragment<FragmentInstallBinding>() {
    private val viewModel: InstallViewModel by viewModels()

    /**
     * API de rendu du terminal (v0.54.0) : la session réelle de la
     * configuration, à brancher sur le mini TerminalView de l'écran.
     */
    @Inject
    lateinit var runtime: TerminalRuntime

    /** Identifiant de la session déjà rendue par le mini terminal
     * (anti-rebranchement — même garde que TerminalActivity). */
    private var idSessionRendue: String? = null

    /** Dernière taille de police réellement appliquée (évite les
     * re-créations de fonte à chaque état). */
    private var tailleRenduePx = AUCUNE_TAILLE

    /** Rangée de la checklist de base gonflée : ses trois vues pilotables. */
    private data class RangeeEtape(
        val icone: ImageView,
        val rotation: CircularProgressIndicator,
        val libelle: TextView,
    )

    /** Rangée d'un outil gonflée : ses quatre vues pilotables. */
    private data class RangeeOutil(
        val icone: ImageView,
        val rotation: CircularProgressIndicator,
        val libelle: TextView,
        val statut: TextView,
    )

    private val rangees = mutableListOf<RangeeEtape>()

    private val rangeesOutils = mutableListOf<RangeeOutil>()

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

        /** Police du mini TerminalView (dp) — lisible sans étouffer la
         * carte (v0.54.0). */
        private const val POLICE_MINI_DP = 13

        /** Valeur sentinelle « aucune taille appliquée ». */
        private const val AUCUNE_TAILLE = -1

        /** Indices de la palette Termux (disposition jackpal, 259 entrées). */
        private const val INDICE_PREMIER_PLAN = 256

        /** Arrière-plan du rendu. */
        private const val INDICE_ARRIERE_PLAN = 257

        /** Couleur du curseur. */
        private const val INDICE_CURSEUR = 258

        /** Modèles d'étapes de la base, dans l'ordre du pipeline (ADR 0048 :
         *  la checklist s'arrête à `apt update` — les outils vivent dans
         *  leur propre section). */
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
        binding.boutonInstallerOutils.setOnClickListener { viewModel.onAction(ActionInstallation.InstallerOutils) }
        binding.boutonAnnuler.setOnClickListener { viewModel.onAction(ActionInstallation.Annuler) }
        binding.boutonFermer.setOnClickListener { findNavController().popBackStack() }
        binding.boutonDetails.setOnClickListener {
            detailsOuverts = !detailsOuverts
            rendreDetails()
        }

        construireChecklist()

        // v0.54.0 : le journal live de la configuration EST le mini
        // TerminalView intégré — le toucher ouvre le clavier (la session
        // est interactive), le rendu suit les sorties au fil de l'eau
        // (même architecture que TerminalActivity : c'est l'écran qui
        // repeint, signal sans throttle).
        binding.vueTerminalMini.setTerminalViewClient(ClientTerminalMini(binding.vueTerminalMini))
        runtime.observeSorties().collectWithLifecycle(viewLifecycleOwner) {
            binding.vueTerminalMini.onScreenUpdated()
        }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rendu complet de l'état : une phase visible à la fois. */
    private fun rendre(etat: EtatInstallation) {
        binding.invite.isVisible = etat.phase == PhaseInstallation.INVITE || etat.phase == PhaseInstallation.ANNULEE
        binding.progression.isVisible = etat.phase == PhaseInstallation.PROGRESSION
        binding.resultat.isVisible = etat.phase == PhaseInstallation.TERMINEE
        binding.echec.isVisible =
            etat.phase == PhaseInstallation.ECHEC || etat.phase == PhaseInstallation.OUTILS_ECHEC
        binding.texteAnnulee.isVisible = etat.phase == PhaseInstallation.ANNULEE

        when (etat.phase) {
            PhaseInstallation.INVITE, PhaseInstallation.ANNULEE -> {
                rendreInvite(etat.phase == PhaseInstallation.ANNULEE)
            }

            PhaseInstallation.PROGRESSION -> {
                rendreProgression(etat)
            }

            PhaseInstallation.TERMINEE -> {
                rendreResultat(etat)
            }

            PhaseInstallation.ECHEC -> {
                rendreEchecBase(etat)
            }

            PhaseInstallation.OUTILS_ECHEC -> {
                rendreEchecOutils(etat)
            }
        }

        // v0.54.0 : la session de configuration (le pty, moteur du
        // journal live) se rend DANS l'écran — visible dès qu'elle existe,
        // quel que soit le reste (une phase d'échec n'efface pas le
        // terminal : sa sortie diagnostique).
        brancherTerminal(etat.sessionConfiguration)
    }

    /** Progression (base ou outils) : en-tête d'étape, sections, journal. */
    private fun rendreProgression(etat: EtatInstallation) {
        binding.boutonInstaller.isVisible = false
        binding.boutonInstallerOutils.isVisible = false
        binding.boutonFermer.isVisible = false
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
        rendreSectionOutils(etat.libelleEtape, etat.paquetsOutils)
        rendreJournal(etat, defiler = true)
    }

    /** Résultat : la configuration de l'environnement vit dans le
     * terminal (v0.52.0, ADR 0083) — le bouton « Ouvrir le terminal » y
     * mène ; le repli par paquets ne sert que si la session n'a pas pu
     * être créée. */
    private fun rendreResultat(etat: EtatInstallation) {
        binding.boutonInstaller.isVisible = false
        binding.boutonAnnuler.isVisible = false
        binding.boutonFermer.isVisible = true
        binding.carteJournal.isVisible = false
        // Environnement complet : le résultat célèbre la configuration
        // prête — ni repli, ni rangées de paquets.
        val montrerRepli = !etat.envComplet && (etat.outils.isEmpty() || etat.outils.any { !it.installe })
        binding.texteOutilsRequis.isVisible = !etat.envComplet
        binding.conteneurResultatOutils.isVisible = montrerRepli
        binding.boutonInstallerOutils.isVisible = montrerRepli
        // v0.54.0 : plus de bouton « Ouvrir le terminal » — la session se
        // rend dans le mini TerminalView (brancherTerminal, appelé par
        // rendre quel que soit l'état).
        if (montrerRepli) {
            rendreResultatOutils(etat.outils, etat.paquetsOutils)
        }
    }

    /** Échec de la base : reprise complète proposée. */
    private fun rendreEchecBase(etat: EtatInstallation) {
        binding.boutonInstaller.isVisible = true
        binding.boutonInstaller.setText(R.string.installation_reessayer)
        binding.boutonInstallerOutils.isVisible = false
        binding.boutonFermer.isVisible = false
        binding.boutonAnnuler.isVisible = false
        binding.texteErreur.setText(messageErreur(etat.erreur))
        binding.boutonDetails.isVisible = etat.detailsEchec != null
        binding.conteneurEchecOutils.isVisible = false
        rendreDetails()
        // L'échec garde le journal ouvert : c'est la sortie EN ÉCHEC qui
        // diagnostique (« la configuration des paquets a échoué » sans
        // elle ne veut rien dire).
        rendreJournal(etat, defiler = true)
    }

    /** Échec des outils : base intacte, reprise de la seule phase d'outils. */
    private fun rendreEchecOutils(etat: EtatInstallation) {
        binding.boutonInstaller.isVisible = false
        binding.boutonInstallerOutils.isVisible = true
        binding.boutonInstallerOutils.setText(R.string.installation_reessayer_outils)
        binding.boutonFermer.isVisible = true
        binding.boutonAnnuler.isVisible = false
        binding.texteErreur.setText(R.string.installation_erreur_outils)
        binding.boutonDetails.isVisible = etat.detailsEchec != null
        binding.conteneurEchecOutils.isVisible = true
        rendreOutilsSimples(binding.conteneurEchecOutils, etat.outils, etat.paquetsOutils)
        rendreDetails()
        rendreJournal(etat, defiler = true)
    }

    /** Invite : le bouton propose l'installation (ou la reprise). */
    private fun rendreInvite(annulee: Boolean) {
        binding.boutonInstaller.isVisible = true
        binding.boutonInstaller.setText(
            if (annulee) R.string.installation_reessayer else R.string.installation_installer,
        )
        binding.boutonInstallerOutils.isVisible = false
        binding.boutonAnnuler.isVisible = false
        binding.boutonFermer.isVisible = false
        binding.carteJournal.isVisible = false
    }

    /** Section « Outils de développement » pendant la progression. */
    private fun rendreSectionOutils(
        etape: EtapeInstallation?,
        paquets: List<String>,
    ) {
        val enCours = etape as? EtapeInstallation.InstallationPaquets
        binding.texteOutilsAttente.isVisible = enCours == null
        if (enCours == null) {
            // Phase de base : les outils attendent, tous « optionnels ».
            rendreOutilsEnAttente(paquets)
            return
        }
        // Phase d'outils : le paquet courant tourne, les précédents sont
        // installés (les échecs sont rapportés à l'état terminal), les
        // suivants attendent.
        val noms = paquets.ifEmpty { List(enCours.total) { enCours.paquet } }
        construireRangeesOutils(noms)
        for ((index, rangee) in rangeesOutils.withIndex()) {
            val rang = index + 1
            when {
                rang < enCours.index -> {
                    rendreRangeeOutil(rangee, EtatRangeeOutil.INSTALLE, R.string.installation_outil_statut_installe)
                }

                rang == enCours.index -> {
                    rendreRangeeOutil(rangee, EtatRangeeOutil.ENCOURS, R.string.installation_outil_statut_encours)
                }

                else -> {
                    rendreRangeeOutil(rangee, EtatRangeeOutil.ATTENTE, R.string.installation_outil_statut_attente)
                }
            }
        }
    }

    /** Résultat : propositions d'action selon l'état des outils. */
    private fun rendreResultatOutils(
        outils: List<jo.codeide.core.model.OutilResume>,
        paquets: List<String>,
    ) {
        if (outils.isEmpty()) {
            // Jamais demandés : proposition claire, rangée par rangée.
            construireRangeesOutils(paquets)
            for (rangee in rangeesOutils) {
                rendreRangeeOutil(rangee, EtatRangeeOutil.ATTENTE, R.string.installation_outil_statut_attente)
            }
            return
        }
        construireRangeesOutils(outils.map { it.paquet })
        for ((index, outil) in outils.withIndex()) {
            if (outil.installe) {
                rendreRangeeOutil(
                    rangeesOutils[index],
                    EtatRangeeOutil.INSTALLE,
                    R.string.installation_outil_statut_installe,
                )
            } else {
                rendreRangeeOutil(
                    rangeesOutils[index],
                    EtatRangeeOutil.ABSENT,
                    R.string.installation_outil_statut_absent,
                )
            }
        }
    }

    /** Outils tous « en attente » (phase de base, section 2). */
    private fun rendreOutilsEnAttente(paquets: List<String>) {
        construireRangeesOutils(paquets)
        for (rangee in rangeesOutils) {
            rendreRangeeOutil(rangee, EtatRangeeOutil.ATTENTE, R.string.installation_outil_statut_attente)
        }
    }

    /**
     * Rendu d'outils dans un conteneur quelconque (échec des outils) :
     * état par paquet de la dernière tentative.
     */
    private fun rendreOutilsSimples(
        conteneur: ViewGroup,
        outils: List<jo.codeide.core.model.OutilResume>,
        paquets: List<String>,
    ) {
        conteneur.removeAllViews()
        val gonfleur = LayoutInflater.from(requireContext())
        val sources =
            outils.ifEmpty {
                paquets.map { paquet ->
                    jo.codeide.core.model
                        .OutilResume(paquet, false)
                }
            }
        for (outil in sources) {
            val rangee = RangeeOutilInstallBinding.inflate(gonfleur, conteneur, true)
            rangee.libelleOutil.text = outil.paquet
            if (outil.installe) {
                configurerRangeeOutil(
                    icone = rangee.iconeOutil,
                    rotation = rangee.rotationOutil,
                    statut = rangee.statutOutil,
                    etat = EtatRangeeOutil.INSTALLE,
                    libelleStatut = getString(R.string.installation_outil_statut_installe),
                )
            } else {
                configurerRangeeOutil(
                    icone = rangee.iconeOutil,
                    rotation = rangee.rotationOutil,
                    statut = rangee.statutOutil,
                    etat = EtatRangeeOutil.ABSENT,
                    libelleStatut = getString(R.string.installation_outil_statut_absent),
                )
            }
        }
    }

    /** Gonfle les rangées d'outils (une fois par vue, sur changement de liste). */
    private fun construireRangeesOutils(paquets: List<String>) {
        val nomsCourants = rangeesOutils.map { it.libelle.text.toString() }
        if (nomsCourants == paquets) return
        binding.conteneurOutils.removeAllViews()
        rangeesOutils.clear()
        val gonfleur = LayoutInflater.from(requireContext())
        for (paquet in paquets) {
            val rangee = RangeeOutilInstallBinding.inflate(gonfleur, binding.conteneurOutils, true)
            rangee.libelleOutil.text = paquet
            rangeesOutils +=
                RangeeOutil(
                    icone = rangee.iconeOutil,
                    rotation = rangee.rotationOutil,
                    libelle = rangee.libelleOutil,
                    statut = rangee.statutOutil,
                )
        }
    }

    /** État d'affichage d'une rangée d'outil. */
    private enum class EtatRangeeOutil {
        ATTENTE,
        ENCOURS,
        INSTALLE,
        ABSENT,
    }

    /** Applique un état à une rangée d'outil (icône, rotation, statut). */
    private fun rendreRangeeOutil(
        rangee: RangeeOutil,
        etat: EtatRangeeOutil,
        ressourceStatut: Int,
    ) {
        configurerRangeeOutil(
            icone = rangee.icone,
            rotation = rangee.rotation,
            statut = rangee.statut,
            etat = etat,
            libelleStatut = getString(ressourceStatut),
        )
    }

    /** Configuration effective d'une rangée d'outil gonflée. */
    private fun configurerRangeeOutil(
        icone: ImageView,
        rotation: CircularProgressIndicator,
        statut: TextView,
        etat: EtatRangeeOutil,
        libelleStatut: String,
    ) {
        when (etat) {
            EtatRangeeOutil.ENCOURS -> {
                icone.isVisible = false
                rotation.isVisible = true
            }

            else -> {
                icone.isVisible = true
                rotation.isVisible = false
                icone.setImageResource(
                    when (etat) {
                        EtatRangeeOutil.INSTALLE -> R.drawable.etape_faite
                        else -> R.drawable.etape_attente
                    },
                )
                icone.imageTintList =
                    ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(
                            requireContext(),
                            when (etat) {
                                EtatRangeeOutil.INSTALLE -> jo.codeide.core.ui.R.color.codeide_succes
                                else -> jo.codeide.core.ui.R.color.codeide_outline
                            },
                        ),
                    )
            }
        }
        statut.text = libelleStatut
    }

    /** Gonfle les huit rangées de la checklist de base (une fois par vue). */
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

    /** Met à jour la checklist de base : terminées, courante, en attente. */
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
            if (faite) jo.codeide.core.ui.R.color.codeide_succes else jo.codeide.core.ui.R.color.codeide_outline,
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
                    // Pluriel propre (v0.31.5, CI lint PluralsCandidate).
                    resources.getQuantityString(
                        R.plurals.installation_detail_extraction,
                        etape.entreesTraitees,
                        etape.entreesTraitees,
                    )
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

    /**
     * Branche le mini TerminalView sur la session de configuration
     * (v0.54.0) — le cœur du « journal live intégré » : sans changement
     * d'identifiant, rien à faire (le signal de sorties repeint le
     * transcript au fil de l'eau) ; la carte n'existe que lorsqu'une
     * session existe. Même architecture que TerminalActivity.
     */
    @Suppress("ReturnCount") // Clauses de garde : une par cas non rendable (règle 16).
    private fun brancherTerminal(idSession: String?) {
        if (idSession == idSessionRendue && binding.carteTerminal.isVisible == (idSession != null)) {
            return
        }
        idSessionRendue = idSession
        binding.carteTerminal.isVisible = idSession != null
        if (idSession == null) return
        val session = runtime.sessionFor(idSession) ?: return
        binding.vueTerminalMini.attachSession(session)
        appliquerThemeTerminal()
        appliquerPoliceMini()
        // Le branchement doit s'afficher IMMÉDIATEMENT (même garantie que
        // TerminalActivity) : attachSession passe par updateSize →
        // invalidate, un repaint explicite garantit le contenu à CE frame.
        binding.vueTerminalMini.onScreenUpdated()
    }

    /**
     * Applique le thème de l'app au mini terminal — version compacte du
     * `appliquerThemeRendu` de feature:terminal (interne à ce module) :
     * indices 256/257/258 = premier plan/arrière-plan/curseur de la
     * palette Termux (disposition jackpal).
     */
    private fun appliquerThemeTerminal() {
        val fond = ContextCompat.getColor(requireContext(), jo.codeide.core.ui.R.color.codeide_terminal_fond)
        val texte = ContextCompat.getColor(requireContext(), jo.codeide.core.ui.R.color.codeide_terminal_texte)
        binding.vueTerminalMini.setBackgroundColor(fond)
        val emulateur = binding.vueTerminalMini.mEmulator ?: return
        val couleurs = emulateur.mColors.mCurrentColors
        couleurs[INDICE_PREMIER_PLAN] = texte
        couleurs[INDICE_ARRIERE_PLAN] = fond
        couleurs[INDICE_CURSEUR] = texte
    }

    /** Police à chasse fixe du mini écran (une fois par vue). */
    private fun appliquerPoliceMini() {
        if (tailleRenduePx != AUCUNE_TAILLE) return
        tailleRenduePx =
            TypedValue
                .applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    POLICE_MINI_DP.toFloat(),
                    resources.displayMetrics,
                ).toInt()
        binding.vueTerminalMini.setTextSize(tailleRenduePx)
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
