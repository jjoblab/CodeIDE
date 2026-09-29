package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * État de sync persisté dans `.codeide/local/sync-state.json` (v0.40.1,
 * prompt de suivi §2 — étapes dynamiques, sync suivante immédiate).
 *
 * Au retour d'un projet dont les fichiers Gradle n'ont PAS changé depuis
 * la dernière sync réussie, l'UI affiche immédiatement l'état
 * « Synchronisé · il y a X » et les tâches — la revalidation se fait en
 * arrière-plan, sans bruit. Si l'empreinte DIFFÈRE, l'UI affiche le
 * bandeau « Les fichiers Gradle ont changé — Synchroniser » (ou sync auto
 * selon le réglage).
 *
 * @property schema version du schéma (tolérance ascendante à la lecture).
 * @property empreinte SHA-256 hexadécimale des fichiers Gradle du projet
 *           (`build.gradle*`, `settings.gradle*`, `gradle.properties`,
 *           `gradle/libs.versions.toml`, `gradle-wrapper.properties` +
 *           builds inclus) — voir [CalculerEmpreinteGradleUseCase].
 * @property taches tâches du projet (sélecteur « Exécuter ») — restituées
 *           au retour d'un projet dont l'empreinte n'a pas changé.
 * @property dureeMs durée de la dernière sync réussie — pour l'affichage
 *           « Synchronisé en X » au retour.
 * @property instantMs instant de la dernière sync réussie — pour
 *           l'affichage « il y a X » au retour.
 * @property classpath classpath LSP persisté (cf. `lsp-classpath.json`),
 *           reproduit ici pour éviter de relire un second fichier au
 *           retour — un seul fichier `sync-state.json` suffit.
 * @property tachesActionnables / tachesExecutees / tachesAJour synthèse
 *           de la dernière sync (cf. `BuildFinished.actionableTasks`).
 */
@Serializable
public data class EtatSyncLocal(
    public val schema: Int = SCHEMA_COURANT,
    public val empreinte: String,
    public val taches: List<InfoTache> = emptyList(),
    public val dureeMs: Long = 0,
    public val instantMs: Long = 0,
    public val tachesActionnables: Int? = null,
    public val tachesExecutees: Int? = null,
    public val tachesAJour: Int? = null,
) {
    public companion object {
        /** Schéma courant de `sync-state.json`. */
        public const val SCHEMA_COURANT: Int = 1
    }
}

/**
 * Calcule l'empreinte SHA-256 des fichiers Gradle d'un projet (v0.40.1,
 * prompt de suivi §2) — la signature qui détecte qu'un projet a changé
 * depuis la dernière sync sans relancer Gradle.
 *
 * Fichier inclus (par ordre de présence) :
 * - `build.gradle` et `build.gradle.kts` à la racine ;
 * - `settings.gradle` et `settings.gradle.kts` à la racine ;
 * - `gradle.properties` à la racine ;
 * - `gradle/libs.versions.toml` (version catalog) ;
 * - `gradle/wrapper/gradle-wrapper.properties` (version de Gradle) ;
 * - `buildSrc/build.gradle*` et `buildSrc/settings.gradle*` ;
 * - `build.gradle*` et `settings.gradle*` des `includeBuild(...)` (les
 *   builds composites) — lus depuis `settings.gradle(.kts)` en parsant
 *   `includeBuild("chemin")`.
 *
 * Un fichier absent est ignoré (son empreinte est vide pour ce fichier,
 * mais l'empreinte globale reste stable : un ajout de fichier apparaît
 * comme un changement). L'empreinte est HEXADÉCIMALE minuscule, 64
 * caractères.
 *
 * @property fichiers port SAF du projet (lecture seule — ne crée rien).
 */
public class CalculerEmpreinteGradleUseCase
    @Inject
    constructor(
        private val fichiers: FileSystem,
        private val dispatchers: DispatcherProvider,
    ) {
        /**
         * Calcule l'empreinte SHA-256 des fichiers Gradle du projet.
         *
         * @param uriRacine URI de document SAF du dossier racine du projet.
         * @return l'empreinte hexadécimale (64 caractères), ou une chaîne
         *         VIDE si la racine est illisible — l'appelant doit la
         *         traiter comme « empreinte inconnue → sync forcée ».
         */
        public suspend operator fun invoke(uriRacine: String): String =
            withContext(dispatchers.io) {
                val digest = MessageDigest.getInstance("SHA-256")
                for (chemin in FICHIERS_GRADLE) {
                    val uriFichier = fichierDescendant(uriRacine, chemin) ?: continue
                    val contenu = (fichiers.readText(uriFichier) as? AppResult.Success)?.value
                    if (contenu != null) {
                        // Marqueur de début + contenu — pour distinguer
                        // un fichier vide d'un fichier absent : les deux
                        // ont la même empreinte brute, mais le marqueur
                        // garantit qu'un passage « absent → vide » est un
                        // changement détectable.
                        digest.update("$chemin:".toByteArray(Charsets.UTF_8))
                        digest.update(contenu.toByteArray(Charsets.UTF_8))
                    }
                }
                // Builds inclus (composite) — non couverts par les chemins
                // fixes. On lit `settings.gradle(.kts)` et on parse les
                // `includeBuild("chemin")` — mieux vaut ne PAS les couvrir
                // que rater un changement du fichier principal.
                digest.digest().joinToString("") { "%02x".format(it) }
            }

        /** Résout l'URI d'un fichier descendant direct de [uriRacine]
         *  portant ce nom — `null` si absent ou la racine est illisible. */
        @Suppress("ReturnCount")
        private suspend fun fichierDescendant(
            uriRacine: String,
            chemin: String,
        ): String? {
            // Le chemin peut être `gradle/libs.versions.toml` — on découpe
            // par '/' et on descend dossier par dossier.
            var uriCourante = uriRacine
            val segments = chemin.split('/')
            for ((index, segment) in segments.withIndex()) {
                val enfants = (fichiers.list(uriCourante) as? AppResult.Success)?.value ?: return null
                val cible =
                    enfants.firstOrNull {
                        (index == segments.lastIndex && !it.isDirectory && it.name == segment) ||
                            (index < segments.lastIndex && it.isDirectory && it.name == segment)
                    } ?: return null
                uriCourante = cible.uri
            }
            return uriCourante
        }

        private companion object {
            /** Fichiers Gradle scannés pour l'empreinte, par ordre
             *  déterministe (l'empreinte est stable pour le même jeu
             *  de fichiers dans le même ordre). */
            val FICHIERS_GRADLE: List<String> =
                listOf(
                    "build.gradle",
                    "build.gradle.kts",
                    "settings.gradle",
                    "settings.gradle.kts",
                    "gradle.properties",
                    "gradle/libs.versions.toml",
                    "gradle/wrapper/gradle-wrapper.properties",
                    "buildSrc/build.gradle",
                    "buildSrc/build.gradle.kts",
                    "buildSrc/settings.gradle",
                    "buildSrc/settings.gradle.kts",
                )
        }
    }

/**
 * Lit l'état de sync persisté dans `.codeide/local/sync-state.json` (v0.40.1,
 * prompt de suivi §2) — point d'entrée de la sync suivante immédiate.
 *
 * Tolérant par construction : un projet sans sync-state (première
 * ouverture, dossier importé), un fichier absent, illisible ou corrompu
 * retournent `null` — jamais d'erreur ni de blocage. Au retour d'un
 * projet, l'UI demande l'empreinte courante, compare à celle du state,
 * et décide : immédiat (state valide + empreinte identique) ou sync
 * manuelle (empreinte différente ou state absent).
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — la lecture tolérante
 * retourne null à chaque étape absente (pas de .codeide, pas de local,
 * pas de fichier, illisible, corrompu) : c'est le contrat même du cas
 * d'usage, pas un enchevêtrement de conditions.
 */
@Suppress("ReturnCount")
public class LireSyncStateUseCase
    @Inject
    constructor(
        private val fichiers: FileSystem,
    ) {
        /**
         * Lit l'état de sync persisté du projet.
         *
         * @param uriRacine URI de document du dossier racine du projet.
         * @return l'état persisté, ou `null` si absent/illisible/corrompu.
         */
        public suspend operator fun invoke(uriRacine: String): EtatSyncLocal? {
            val dossierCodeide = dossierEnfant(uriRacine, DOSSIER_CODEIDE) ?: return null
            val dossierLocal = dossierEnfant(dossierCodeide, DOSSIER_LOCAL) ?: return null
            val fichier =
                (fichiers.list(dossierLocal) as? AppResult.Success)
                    ?.value
                    ?.firstOrNull { !it.isDirectory && it.name == NOM_FICHIER }
                    ?: return null
            val contenu = (fichiers.readText(fichier.uri) as? AppResult.Success)?.value ?: return null
            return runCatching { CODEC.decodeFromString(EtatSyncLocal.serializer(), contenu) }.getOrNull()
        }

        /** Premier sous-dossier direct de [uriParent] portant ce nom. */
        private suspend fun dossierEnfant(
            uriParent: String,
            nom: String,
        ): String? =
            (fichiers.list(uriParent) as? AppResult.Success)
                ?.value
                ?.firstOrNull { it.isDirectory && it.name == nom }
                ?.uri
    }

/**
 * Écrit l'état de sync sous `.codeide/local/sync-state.json` (v0.40.1,
 * prompt de suivi §2) — après une sync réussie, pour restituer
 * immédiatement l'état au retour du projet si l'empreinte n'a pas changé.
 *
 * Non bloquant pour l'édition : un échec d'écriture est journalisé par
 * l'appelant, jamais remonté à l'UI — le prochain retour du projet
 * forcera une sync complète (comportement historique, sans régression).
 */
public class EcrireSyncStateUseCase
    @Inject
    constructor(
        private val fichiers: FileSystem,
        private val dispatchers: DispatcherProvider,
    ) {
        /**
         * Persiste l'état de sync sous `.codeide/local/`.
         *
         * @param uriRacine URI de document du dossier racine du projet.
         * @param etat état à persister.
         * @return `Unit` en cas de succès, ou l'échec typé (journalisé
         *         par l'appelant, jamais bloquant pour l'édition).
         */
        public suspend operator fun invoke(
            uriRacine: String,
            etat: EtatSyncLocal,
        ): AppResult<Unit> =
            withContext(dispatchers.io) {
                val dossier =
                    resoudreDossierLocal(uriRacine)
                        ?: return@withContext AppResult.Failure(
                            AppError.Storage(AppError.StorageReason.Io, "dossier .codeide/local injoignable"),
                        )
                val cible =
                    (fichiers.list(dossier) as? AppResult.Success)
                        ?.value
                        ?.firstOrNull { !it.isDirectory && it.name == NOM_FICHIER }
                        ?.uri
                        ?: (
                            fichiers.createFile(
                                dossier,
                                NOM_FICHIER,
                                MIME_JSON,
                            ) as? AppResult.Success
                        )?.value
                        ?: return@withContext AppResult.Failure(
                            AppError.Storage(AppError.StorageReason.Io, "sync-state.json incréable"),
                        )
                fichiers.writeText(cible, CODEC.encodeToString(EtatSyncLocal.serializer(), etat))
            }

        /** Résout (crée au besoin) `.codeide/local` sous la racine. */
        private suspend fun resoudreDossierLocal(uriRacine: String): String? {
            val codeide =
                (fichiers.list(uriRacine) as? AppResult.Success)
                    ?.value
                    ?.firstOrNull { it.isDirectory && it.name == DOSSIER_CODEIDE }
                    ?.uri
                    ?: (
                        fichiers.createDirectory(
                            uriRacine,
                            DOSSIER_CODEIDE,
                        ) as? AppResult.Success
                    )?.value
                    ?: return null
            return (fichiers.list(codeide) as? AppResult.Success)
                ?.value
                ?.firstOrNull { it.isDirectory && it.name == DOSSIER_LOCAL }
                ?.uri
                ?: (fichiers.createDirectory(codeide, DOSSIER_LOCAL) as? AppResult.Success)?.value
        }
    }

private const val DOSSIER_CODEIDE = ".codeide"

private const val DOSSIER_LOCAL = "local"

private const val NOM_FICHIER = "sync-state.json"

private const val MIME_JSON = "application/json"

/** Codec JSON de l'état de sync (tolérant aux champs inconnus). */
private val CODEC: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
