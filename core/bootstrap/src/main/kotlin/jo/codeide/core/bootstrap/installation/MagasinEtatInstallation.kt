package jo.codeide.core.bootstrap.installation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.ComponentIssue
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstallStateStore
import jo.codeide.core.domain.InstalledComponent
import jo.codeide.core.domain.PersistedInstallState
import jo.codeide.core.domain.PhaseState
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistance de l'état du parcours dans `install-state.json` (port
 * [InstallStateStore], ADR 0085 § 2 / ADR 0087 § 4) — `org.json`
 * (précédent maison `CrashReportFileStore`), écriture **atomique**
 * (fichier temporaire + renommage) : un état à moitié écrit corromprait
 * la reprise.
 *
 * Seuls les états **stables** sont écrits ; `Running` est normalisé
 * `NotStarted` à l'écriture **et** à la lecture (défense en profondeur :
 * jamais d'état « en cours » relu comme vérité). Un fichier absent,
 * illisible, ou dont la version de schéma ou un champ requis manque,
 * rend `load()` nul : le parcours repart de zéro — jamais d'état déduit
 * du disque. Le codage JSON vit dans le [Codec] imbriqué (fonctions
 * pures, testées avec la classe).
 */
@Singleton
internal class MagasinEtatInstallation
    @Inject
    constructor(
        @ApplicationContext contexte: Context,
        private val dispatchers: DispatcherProvider,
    ) : InstallStateStore {
        private val fichier = File(contexte.filesDir, Codec.NOM_FICHIER)

        override suspend fun load(): PersistedInstallState? =
            withContext(dispatchers.io) {
                if (!fichier.isFile) return@withContext null
                runCatching { Codec.analyser(JSONObject(fichier.readText())) }.getOrNull()
            }

        override suspend fun save(state: PersistedInstallState) {
            withContext(dispatchers.io) {
                val dossier = fichier.parentFile
                if (dossier != null && !dossier.isDirectory) dossier.mkdirs()
                val temporaire = File(fichier.parentFile, "${Codec.NOM_FICHIER}.tmp")
                temporaire.bufferedWriter().use { ecrivain ->
                    ecrivain.write(Codec.serialiser(normaliser(state)).toString())
                }
                if (fichier.isFile && !fichier.delete()) {
                    temporaire.delete()
                    return@withContext
                }
                if (!temporaire.renameTo(fichier)) temporaire.delete()
            }
        }

        // Décodage : chaque champ invalide rend la main tôt — seuil de
        // retours assumé pour la fonction d'analyse (règle 8).

        /** Normalisation : `Running` → `NotStarted` (jamais persisté comme vérité). */
        internal fun normaliser(state: PersistedInstallState): PersistedInstallState =
            state.copy(
                phases =
                    state.phases.mapValues { (_, phase) ->
                        if (phase is PhaseState.Running) PhaseState.NotStarted else phase
                    },
            )

        /**
         * Codage `install-state.json` : fonctions pures, schéma versionné 1.
         * Décodage : chaque champ invalide rend la main tôt — seuil de
         * retours assumé (règle 8 : exception ciblée et commentée).
         */
        @Suppress("ReturnCount")
        private object Codec {
            const val NOM_FICHIER = "install-state.json"

            /** Analyse stricte : tout champ requis absent rend l'état illisible (`null`). */
            fun analyser(racine: JSONObject): PersistedInstallState? {
                if (racine.optInt(CHAMP_SCHEMA) != PersistedInstallState.SCHEMA_VERSION) return null
                val phasesJson = racine.optJSONObject(CHAMP_PHASES) ?: return null
                val phases = mutableMapOf<InstallPhase, PhaseState>()
                for (phase in InstallPhase.entries) {
                    // Seules les phases ÉCRITES sont relues : une carte
                    // partielle vaut `NotStarted` par accès (EnvironmentSetupState.phase),
                    // un état inconnu rend le fichier invalide (version future).
                    val json = phasesJson.optJSONObject(phase.name) ?: continue
                    phases[phase] = analyserPhase(json) ?: return null
                }
                return PersistedInstallState(
                    schemaVersion = racine.optInt(CHAMP_SCHEMA),
                    phases = phases,
                    installedComponents = analyserComposants(racine.optJSONArray(CHAMP_COMPOSANTS)) ?: return null,
                    sdkLicenseAcceptedAtMillis =
                        if (racine.has(CHAMP_LICENCE)) racine.optLong(CHAMP_LICENCE) else null,
                )
            }

            /** Les trois états stables, chacun avec sa charge complète. */
            fun analyserPhase(json: JSONObject): PhaseState? =
                when (json.optString(CHAMP_ETAT)) {
                    ETAT_REUSSIE -> {
                        json.optJSONObject(CHAMP_VERSIONS)?.let { versions ->
                            PhaseState.Succeeded(json.optLong(CHAMP_VERIFIE_A), lireCarte(versions))
                        }
                    }

                    ETAT_DEGRADEE -> {
                        json.optJSONArray(CHAMP_AVERTISSEMENTS)?.let { avertissements ->
                            PhaseState.Degraded(json.optLong(CHAMP_VERIFIE_A), lireAvertissements(avertissements))
                        }
                    }

                    ETAT_ECHOUEE -> {
                        json.optJSONObject(CHAMP_ERREUR)?.let { erreur ->
                            PhaseState.Failed(
                                error = analyserErreur(erreur),
                                logTail = json.optJSONArray(CHAMP_JOURNAL)?.let(::lireLignes) ?: emptyList(),
                            )
                        }
                    }

                    else -> {
                        null
                    }
                }

            fun analyserErreur(json: JSONObject): jo.codeide.core.model.AppError.EnvironmentSetup =
                jo.codeide.core.model.AppError.EnvironmentSetup(
                    reason =
                        runCatching {
                            jo.codeide.core.model.AppError.EnvironmentSetupReason.valueOf(
                                json.getString(CHAMP_RAISON),
                            )
                        }.getOrDefault(jo.codeide.core.model.AppError.EnvironmentSetupReason.Commande),
                    details = json.optString(CHAMP_DETAILS),
                    sortie =
                        json.optJSONObject(CHAMP_SORTIE)?.let { sortie ->
                            jo.codeide.core.model.AppError.CommandOutput(
                                commande = sortie.getString(CHAMP_COMMANDE),
                                exitCode = sortie.getInt(CHAMP_CODE),
                                lastLines = lireLignes(sortie.getJSONArray(CHAMP_LIGNES)),
                            )
                        },
                )

            fun analyserComposants(composantsJson: JSONArray?): List<InstalledComponent>? {
                val composants = mutableListOf<InstalledComponent>()
                for (index in 0 until (composantsJson?.length() ?: 0)) {
                    val json = composantsJson?.optJSONObject(index) ?: continue
                    val requis =
                        listOf(
                            CHAMP_ID,
                            CHAMP_VERSION,
                            CHAMP_REVISION,
                            CHAMP_SHA256,
                            CHAMP_INSTALLE_A,
                            CHAMP_INSTALL_PATH,
                        )
                    if (requis.any { !json.has(it) }) return null
                    composants +=
                        InstalledComponent(
                            id = json.getString(CHAMP_ID),
                            version = json.getString(CHAMP_VERSION),
                            revision = json.getString(CHAMP_REVISION),
                            sha256 = json.getString(CHAMP_SHA256),
                            installedAtMillis = json.getLong(CHAMP_INSTALLE_A),
                            installPath = json.optString(CHAMP_INSTALL_PATH).takeIf { it.isNotBlank() },
                        )
                }
                return composants
            }

            fun serialiser(state: PersistedInstallState): JSONObject {
                val racine = JSONObject()
                racine.put(CHAMP_SCHEMA, state.schemaVersion)
                val phases = JSONObject()
                for ((phase, etat) in state.phases) {
                    if (etat is PhaseState.NotStarted) continue
                    phases.put(phase.name, serialiserEtat(etat))
                }
                racine.put(CHAMP_PHASES, phases)
                val composants = JSONArray()
                state.installedComponents.forEach { composant ->
                    composants.put(
                        JSONObject()
                            .put(CHAMP_ID, composant.id)
                            .put(CHAMP_VERSION, composant.version)
                            .put(CHAMP_REVISION, composant.revision)
                            .put(CHAMP_SHA256, composant.sha256)
                            .put(CHAMP_INSTALLE_A, composant.installedAtMillis)
                            .put(CHAMP_INSTALL_PATH, composant.installPath ?: ""),
                    )
                }
                racine.put(CHAMP_COMPOSANTS, composants)
                state.sdkLicenseAcceptedAtMillis?.let { racine.put(CHAMP_LICENCE, it) }
                return racine
            }

            private fun serialiserEtat(etat: PhaseState): JSONObject {
                val json = JSONObject()
                when (etat) {
                    is PhaseState.Succeeded -> {
                        json.put(CHAMP_ETAT, ETAT_REUSSIE)
                        json.put(CHAMP_VERIFIE_A, etat.verifiedAtMillis)
                        json.put(CHAMP_VERSIONS, JSONObject(etat.versions))
                    }

                    is PhaseState.Degraded -> {
                        json.put(CHAMP_ETAT, ETAT_DEGRADEE)
                        json.put(CHAMP_VERIFIE_A, etat.verifiedAtMillis)
                        val avertissements = JSONArray()
                        etat.warnings.forEach { avertissement ->
                            avertissements.put(
                                JSONObject()
                                    .put(CHAMP_COMPONENT_ID, avertissement.componentId)
                                    .put(CHAMP_VERSION, avertissement.version)
                                    .put(CHAMP_DIAGNOSTIC, avertissement.diagnostic),
                            )
                        }
                        json.put(CHAMP_AVERTISSEMENTS, avertissements)
                    }

                    is PhaseState.Failed -> {
                        json.put(CHAMP_ETAT, ETAT_ECHOUEE)
                        json.put(
                            CHAMP_ERREUR,
                            JSONObject()
                                .put(CHAMP_RAISON, etat.error.reason.name)
                                .put(CHAMP_DETAILS, etat.error.details)
                                .putOpt(
                                    CHAMP_SORTIE,
                                    etat.error.sortie?.let { sortie ->
                                        JSONObject()
                                            .put(CHAMP_COMMANDE, sortie.commande)
                                            .put(CHAMP_CODE, sortie.exitCode)
                                            .put(CHAMP_LIGNES, JSONArray(sortie.lastLines))
                                    },
                                ),
                        )
                        json.put(CHAMP_JOURNAL, JSONArray(etat.logTail))
                    }

                    PhaseState.NotStarted, is PhaseState.Running -> {
                        // Normalisés en amont : jamais sérialisés (défense).
                        json.put(CHAMP_ETAT, ETAT_NON_DEMARREE)
                    }
                }
                return json
            }

            private fun lireCarte(json: JSONObject): Map<String, String> {
                val carte = mutableMapOf<String, String>()
                json.keys().forEach { cle -> carte[cle] = json.getString(cle) }
                return carte
            }

            private fun lireLignes(json: JSONArray): List<String> {
                val lignes = mutableListOf<String>()
                for (index in 0 until json.length()) lignes += json.getString(index)
                return lignes
            }

            private fun lireAvertissements(json: JSONArray): List<ComponentIssue> {
                val avertissements = mutableListOf<ComponentIssue>()
                for (index in 0 until json.length()) {
                    avertissements += json.optJSONObject(index)?.let { objet ->
                        ComponentIssue(
                            componentId = objet.getString(CHAMP_COMPONENT_ID),
                            version = objet.getString(CHAMP_VERSION),
                            diagnostic = objet.getString(CHAMP_DIAGNOSTIC),
                        )
                    } ?: return emptyList()
                }
                return avertissements
            }

            private const val CHAMP_SCHEMA = "schemaVersion"
            private const val CHAMP_PHASES = "phases"
            private const val CHAMP_COMPOSANTS = "installedComponents"
            private const val CHAMP_LICENCE = "sdkLicenseAcceptedAtMillis"
            private const val CHAMP_ETAT = "etat"
            private const val CHAMP_VERIFIE_A = "verifiedAtMillis"
            private const val CHAMP_VERSIONS = "versions"
            private const val CHAMP_AVERTISSEMENTS = "warnings"
            private const val CHAMP_ERREUR = "erreur"
            private const val CHAMP_JOURNAL = "journal"
            private const val CHAMP_RAISON = "raison"
            private const val CHAMP_DETAILS = "details"
            private const val CHAMP_SORTIE = "sortie"
            private const val CHAMP_COMMANDE = "commande"
            private const val CHAMP_CODE = "exitCode"
            private const val CHAMP_LIGNES = "lastLines"
            private const val CHAMP_ID = "id"
            private const val CHAMP_VERSION = "version"
            private const val CHAMP_REVISION = "revision"
            private const val CHAMP_SHA256 = "sha256"
            private const val CHAMP_INSTALLE_A = "installedAtMillis"
            private const val CHAMP_INSTALL_PATH = "installPath"
            private const val CHAMP_COMPONENT_ID = "componentId"
            private const val CHAMP_DIAGNOSTIC = "diagnostic"
            private const val ETAT_REUSSIE = "Succeeded"
            private const val ETAT_DEGRADEE = "Degraded"
            private const val ETAT_ECHOUEE = "Failed"
            private const val ETAT_NON_DEMARREE = "NotStarted"
        }
    }
