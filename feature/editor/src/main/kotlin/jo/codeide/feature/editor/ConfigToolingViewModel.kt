package jo.codeide.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.GradleToolingRepository
import jo.codeide.core.domain.InstantaneTas
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.UpdateSettingsUseCase
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.onFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de l'écran de configuration du tooling (v3) — ouvert par
 * l'engrenage de l'onglet Console, scopé au fragment enfant que la
 * console héberge (INC-25/36 : glossaire — « Console » partout, plus de
 * « Sortie », et c'est un FRAGMENT, pas un dialogue).
 *
 * Réglages **persistés à l'instant** (même contrat que les sections des
 * Paramètres, ADR 0059) : chaque bascule/interrupteur écrit le champ dans
 * DataStore dès le geste — l'état observé revient confirmer le rendu, un
 * écran rouvert repart du persisté, jamais d'un tampon local divergent.
 *
 * L'état vivant de l'orchestrateur (connexion, tas) complète le réglage :
 * l'utilisateur voit QUI il configure, pas seulement CE qu'il règle.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — une fonction
 * PAR réglage de l'écran (hors ligne, injection, arguments, arguments
 * sync, niveau de journal, instrumentation, banc × 5, redémarrage) : la
 * façade d'un écran de réglages EST sa liste de setters, les regrouper
 * en classes artificielles casserait le contrat idempotent partagé.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class ConfigToolingViewModel
@Inject
constructor(
    observerReglages: ObserveSettingsUseCase,
    private val majReglages: UpdateSettingsUseCase,
    private val tooling: GradleToolingRepository,
    private val journal: AppLogger,
) : ViewModel() {
    /** Réglages courants (retour du DataStore — source de vérité,
     *  exposée au rendu idempotent du dialogue). */
    val reglages: StateFlow<AppSettings> =
        observerReglages()
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** État vivant de l'orchestrateur, exposé en lecture seule. */
    private val etatVivantInterne = MutableStateFlow(EtatVivantTooling())

    /** État observable de l'écran. */
    val etatVivant: StateFlow<EtatVivantTooling> = etatVivantInterne.asStateFlow()

    init {
        // Connexion + tas de l'orchestrateur : deux flux froids fusionnés
        // dans UN état (le dialogue ne collecte qu'une fois).
        viewModelScope.launch {
            combine(tooling.observeConnectionState(), tooling.observeHeap()) { connexion, tas ->
                EtatVivantTooling(connexion = connexion, tas = tas)
            }.collect { etatVivantInterne.value = it }
        }
    }

    /** Mode hors ligne (`--offline`) des builds. */
    fun definirHorsLigne(horsLigne: Boolean) {
        viewModelScope.launch { persister { it.copy(toolingHorsLigne = horsLigne) } }
    }

    /**
     * Injection de la bibliothèque applog-runtime dans les builds debug
     * des projets (mission « Exécuter » R2, ADR 0103) — s'applique à la
     * prochaine vie du process orchestrateur (redémarrage de l'app ou
     * de la chaîne d'outils), dit tel quel dans l'écran.
     */
    fun definirInjectionAppLog(active: Boolean) {
        viewModelScope.launch { persister { it.copy(injectionAppLog = active) } }
    }

    /** Arguments Gradle libres (séparés par des espaces). */
    fun definirArguments(arguments: String) {
        viewModelScope.launch { persister { it.copy(toolingArguments = arguments.trim()) } }
    }

    /**
     * Arguments Gradle RÉSERVÉS à la synchronisation (INC-31, Phase 2) :
     * vides → la sync retombe sur les arguments généraux (comportement
     * d'avant). S'applique à la PROCHAINE synchronisation.
     */
    fun definirArgumentsSync(arguments: String) {
        viewModelScope.launch { persister { it.copy(toolingArgumentsSync = arguments.trim()) } }
    }

    /**
     * Niveau de journal de l'orchestrateur (INC-32, Phase 2) : `INFO`,
     * `WARN` ou `ERROR` — toute autre valeur retombe sur `INFO`. S'applique
     * à la prochaine vie du process (l'action « Redémarrer » la provoque).
     */
    fun definirNiveauJournal(niveau: String) {
        val valeur = if (niveau in NIVEAUX_JOURNAL) niveau else "INFO"
        viewModelScope.launch { persister { it.copy(niveauJournalServeur = valeur) } }
    }

    /**
     * Instrumentation de latence du transport (Phase 1, ADR 0152) — arme
     * la sonde du fil lecteur (T3), la mesure de la vue (T5/T6) et le
     * drapeau `--instru-transport` du serveur : s'applique à la prochaine
     * vie du process orchestrateur, dit tel quel dans l'écran.
     */
    fun definirInstrumentationTransport(active: Boolean) {
        viewModelScope.launch { persister { it.copy(instrumentationTransport = active) } }
    }

    /** Banc « socket seul » (Phase 1, §3.1b) — mêmes règles d'application. */
    fun definirBancSocketSeul(active: Boolean) {
        viewModelScope.launch { persister { it.copy(bancSocketSeul = active) } }
    }

    /** Lignes du banc (bornées à la lecture côté DataStore, resserrées ici). */
    fun definirBancLignes(lignes: Int) {
        viewModelScope.launch { persister { it.copy(bancLignes = lignes.coerceIn(1, BANC_LIGNES_MAX)) } }
    }

    /** Cadence du banc en ms (0 = rafale). */
    fun definirBancCadenceMs(cadenceMs: Int) {
        viewModelScope.launch { persister { it.copy(bancCadenceMs = cadenceMs.coerceIn(0, BANC_CADENCE_MS_MAX)) } }
    }

    /** Longueur des lignes du banc. */
    fun definirBancLongueurLigne(longueur: Int) {
        viewModelScope.launch {
            persister { it.copy(bancLongueurLigne = longueur.coerceIn(1, BANC_LONGUEUR_LIGNE_MAX)) }
        }
    }

    /**
     * Contenu des lignes du banc — `plain`, `ansi`, `utf8` ou `multi` ;
     * toute autre valeur retombe sur `plain` (le serveur valide aussi,
     * l'app ne lui envoie rien d'invraisemblable).
     */
    fun definirBancContenu(contenu: String) {
        val valeur = contenu.trim().lowercase()
        viewModelScope.launch {
            persister { it.copy(bancContenu = if (valeur in CONTENUS_BANC) valeur else "plain") }
        }
    }

    /**
     * Redémarre le serveur de build À LA DEMANDE (INC-33, Phase 2) : les
     * réglages « à la prochaine vie du process » (niveau de journal,
     * instrumentation, banc) s'appliquent immédiatement — l'état vivant
     * de la carte État montre la reconnexion EN DIRECT.
     */
    fun redemarrerServeur() {
        tooling.redemarrerOrchestrateur()
    }

    /** Transformation atomique du réglage, échec journalisé (règle 15 :
     *  identifiants seulement, le contenu des arguments n'y paraît pas). */
    private suspend fun persister(transformation: (AppSettings) -> AppSettings) {
        majReglages(transformation)
            .onFailure { erreur ->
                journal.w(TAG) { "réglage tooling non persisté (${erreur::class.simpleName})" }
            }
    }

    /** Regroupement de l'état vivant de l'orchestrateur (v3). */
    data class EtatVivantTooling(
        val connexion: EtatConnexion = EtatConnexion.DECONNECTEE,
        val tas: InstantaneTas = InstantaneTas(moUtilises = 0, moMax = 0),
    )

    private companion object {
        const val TAG = "ConfigTooling"

        /** Valeurs de contenu acceptées par le drapeau `--banc-contenu`. */
        val CONTENUS_BANC = setOf("plain", "ansi", "utf8", "multi")

        /** Niveaux de journal acceptés par le drapeau `--log-level` (INC-32). */
        val NIVEAUX_JOURNAL = setOf("INFO", "WARN", "ERROR")

        /** Bornes de saisie des paramètres du banc (miroir des bornes de lecture du DataStore). */
        const val BANC_LIGNES_MAX = 200_000
        const val BANC_CADENCE_MS_MAX = 60_000
        const val BANC_LONGUEUR_LIGNE_MAX = 2_000
    }
}
