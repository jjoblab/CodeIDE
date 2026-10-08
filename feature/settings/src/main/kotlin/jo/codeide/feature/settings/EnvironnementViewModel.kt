package jo.codeide.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.AuditeurComposants
import jo.codeide.core.domain.DiagnosticInstallation
import jo.codeide.core.domain.EnvironmentSetupOrchestrator
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstallStateStore
import jo.codeide.core.domain.PhaseState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Écran Environnement (E5, § 7 — ADR 0090) : projection des composants
 * installés (quadruplets du magasin d'état, schéma 2) avec **tailles
 * réelles mesurées sur disque** ([AuditeurComposants], lecture seule),
 * d'une rangée JDK (paquet APT de la phase 3, hors manifeste), du
 * journal de l'orchestrateur (diagnostic) et des actions Vérifier
 * (légère/approfondie), Réparer, Désinstaller.
 */
@HiltViewModel
class EnvironnementViewModel
    @Inject
    constructor(
        private val orchestrateur: EnvironmentSetupOrchestrator,
        private val magasin: InstallStateStore,
        private val auditeur: AuditeurComposants,
    ) : ViewModel() {
        private val composantsInternes = MutableStateFlow<List<ComposantEnv>>(emptyList())
        val composants: StateFlow<List<ComposantEnv>> = composantsInternes.asStateFlow()

        /** Journal de l'orchestrateur (diagnostic, expurgé par le cadre). */
        val journal: StateFlow<List<String>> = orchestrateur.journal

        /** État du parcours (pour Réparer : la phase à réparer). */
        val etat =
            orchestrateur.state
                .stateIn(viewModelScope, SharingStarted.Eagerly, orchestrateur.state.value)

        init {
            viewModelScope.launch { rafraichir() }
            viewModelScope.launch {
                orchestrateur.state.collect { rafraichir() }
            }
        }

        /** Recharge les composants installés : magasin (quadruplets) + tailles réelles. */
        private suspend fun rafraichir() {
            val persiste = magasin.load() ?: return
            val versions =
                etat.value.phases.values
                    .filterIsInstance<PhaseState.Succeeded>()
                    .flatMap { it.versions.entries }
                    .associate { it.key to it.value }

            // Composants du manifeste : la présence réelle de l'installPath
            // fait foi (taille null = absent), pas un booléen déduit.
            val composantsSdk =
                persiste.installedComponents.map { composant ->
                    val taille = auditeur.tailleOctets(composant)
                    ComposantEnv(
                        id = composant.id,
                        version = versions[composant.id] ?: composant.version,
                        revision = composant.revision,
                        tailleOctets = taille,
                        verifie = taille != null,
                        desinstallable = true,
                        paquetApt = false,
                    )
                }

            // Rangée JDK (phase 3, paquet APT — jamais désinstallable ici) :
            // visible dès que la phase JAVA est vérifiée.
            val javaVerifie =
                etat.value.phase(InstallPhase.JAVA) is PhaseState.Succeeded ||
                    etat.value.phase(InstallPhase.JAVA) is PhaseState.Degraded
            val jdk =
                versions["jdk"]?.let { version ->
                    ComposantEnv(
                        id = ID_JDK,
                        version = version,
                        revision = null,
                        tailleOctets = auditeur.tailleJdkOctets(),
                        verifie = javaVerifie,
                        desinstallable = false,
                        paquetApt = true,
                    )
                }

            composantsInternes.value = composantsSdk + listOfNotNull(jdk)
        }

        /** Vérification légère (`deep = false`) ou approfondie (projet + assembleDebug réel). */
        fun verifier(profonde: Boolean) {
            viewModelScope.launch { orchestrateur.verify(deep = profonde) }
        }

        /** Répare la première phase non vérifiée ou en échec (§ 3.5, réparation ciblée). */
        fun reparer() {
            viewModelScope.launch {
                val cible =
                    etat.value.premierePhaseNonVerifiee()
                        ?: InstallPhase.entries.firstOrNull { etat.value.phase(it) is PhaseState.Failed }
                        ?: return@launch
                orchestrateur.repair(cible)
            }
        }

        /**
         * v0.69.0 : reprend l'installation complète (toutes les phases non
         * terminées). Contrairement à `reparer()` qui ne cible qu'une phase,
         * `reprendreTout()` appelle `orchestrateur.run()` — le parcours
         * reprend à `premierePhaseNonVerifiee()` et skip les phases déjà
         * `Succeeded` (verify-first, § 3.5). Utile après une fermeture
         * mid-install : l'utilisateur reprend là où il s'était arrêté.
         */
        fun reprendreTout() {
            viewModelScope.launch { orchestrateur.run() }
        }

        /** Désinstalle un composant (confirmation portée par l'écran, § 7). */
        fun desinstaller(id: String) {
            viewModelScope.launch {
                orchestrateur.uninstallComponent(id)
                rafraichir()
            }
        }

        /** Diagnostic copiable (journal + récapitulatif, partagé avec l'installation). */
        fun diagnostic(): String = DiagnosticInstallation.diagnostic(etat.value, journal.value)

        /** Identifiant de la rangée JDK (paquet APT, hors manifeste). */
        private companion object {
            private const val ID_JDK: String = "jdk"
        }
    }
