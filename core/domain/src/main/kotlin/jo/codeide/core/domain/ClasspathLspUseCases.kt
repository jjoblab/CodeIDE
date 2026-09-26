package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

/**
 * Classpath LSP d'un projet, persisté dans
 * `.codeide/local/lsp-classpath.json` (ADR 0058) : répertoires sources et
 * classpath compilé de chaque module — jars, AARs et modules frères, jar
 * de sources attaché quand Gradle le connaît. Fichier **local, non
 * synchronisé** avec le projet (les `.gitignore` générés excluent déjà
 * `.codeide/local/`), écrit après chaque sync réussie pour que les LSP
 * s'en servent LE MOMENT VENU, sans re-résolution.
 *
 * @property schema version du schéma (tolérance ascendante à la lecture).
 * @property projectDir dossier racine résolu par l'orchestrateur.
 * @property modules classpath de chaque module, racine incluse.
 */
@Serializable
public data class EtatClasspathLsp(
    public val schema: Int = SCHEMA_COURANT,
    public val projectDir: String,
    public val modules: List<ModuleClasspath> = emptyList(),
) {
    public companion object {
        /** Schéma courant de `lsp-classpath.json`. */
        public const val SCHEMA_COURANT: Int = 1
    }
}

/**
 * Cas d'usage « préparer le classpath LSP » (ADR 0058 — comme Android
 * Studio prépare l'index du projet à la sync) : résout le classpath
 * compilé via l'orchestrateur puis le PERSISTE sous
 * `.codeide/local/lsp-classpath.json` de la racine du projet, en créant
 * les dossiers intermédiaires au besoin (un dossier importé peut ne pas
 * avoir de `.codeide`).
 *
 * La préparation suit la sync dans le parcours d'ouverture (même connexion
 * Tooling API, modèles déjà résolus) : elle ne redemande rien à Gradle
 * que la sync n'ait déjà payé. Son échec n'est jamais bloquant pour
 * l'édition — l'appelant journalise, l'UI ne change pas.
 *
 * Journalisation identifiante (règle 15) : le dossier n'apparaît JAMAIS
 * dans le journal, seulement le fait de préparer.
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — chaque clause de
 * garde est une **issue** du parcours (dossier local injoignable, fichier
 * incréable, échec de résolution, succès) ; les imbriquer en expressions
 * ferait perdre l'évidence du contrat.
 */
@Suppress("ReturnCount")
public class PreparerClasspathLspUseCase
    @Inject
    constructor(
        private val tooling: GradleToolingRepository,
        private val fichiers: FileSystem,
        private val journal: AppLogger,
        private val dispatchers: DispatcherProvider,
    ) {
        /**
         * Prépare (résout + persiste) le classpath LSP du projet.
         *
         * @param dossier répertoire racine du projet (FUSE, celui de la sync).
         * @param uriRacine URI de document SAF du dossier racine du projet.
         * @return le classpath résolu, ou l'échec typé (journalisé par
         *   l'appelant, jamais bloquant pour l'édition).
         */
        public suspend operator fun invoke(
            dossier: File,
            uriRacine: String,
        ): AppResult<ClasspathProjet> =
            withContext(dispatchers.io) {
                journal.i(TAG) { "préparation du classpath LSP demandée" }
                when (val resolu = tooling.classpath(dossier)) {
                    is AppResult.Failure -> {
                        resolu
                    }

                    is AppResult.Success -> {
                        when (ecrireEtat(uriRacine, resolu.value)) {
                            is AppResult.Failure -> {
                                AppResult.Failure(
                                    AppError.Storage(AppError.StorageReason.Io, "lsp-classpath.json incréable"),
                                )
                            }

                            is AppResult.Success -> {
                                resolu
                            }
                        }
                    }
                }
            }

        /** Écrit l'état sous `.codeide/local/`, dossier créé au besoin. */
        private suspend fun ecrireEtat(
            uriRacine: String,
            classpath: ClasspathProjet,
        ): AppResult<Unit> {
            val dossier =
                resoudreDossierLocal(uriRacine)
                    ?: return AppResult.Failure(
                        AppError.Storage(AppError.StorageReason.Io, "dossier .codeide/local injoignable"),
                    )
            val cible =
                (fichiers.list(dossier) as? AppResult.Success)
                    ?.value
                    ?.firstOrNull { !it.isDirectory && it.name == CheminsClasspathLsp.NOM_FICHIER }
                    ?.uri
                    ?: (
                        fichiers.createFile(
                            dossier,
                            CheminsClasspathLsp.NOM_FICHIER,
                            CheminsClasspathLsp.MIME_JSON,
                        ) as? AppResult.Success
                    )?.value
                    ?: return AppResult.Failure(
                        AppError.Storage(AppError.StorageReason.Io, "lsp-classpath.json incréable"),
                    )
            val etat =
                EtatClasspathLsp(
                    projectDir = classpath.projectDir,
                    modules = classpath.modules,
                )
            return fichiers.writeText(cible, CODEC.encodeToString(EtatClasspathLsp.serializer(), etat))
        }

        /** Résout (crée au besoin) `.codeide/local` sous la racine. */
        private suspend fun resoudreDossierLocal(uriRacine: String): String? {
            val codeide =
                (fichiers.list(uriRacine) as? AppResult.Success)
                    ?.value
                    ?.firstOrNull { it.isDirectory && it.name == CheminsClasspathLsp.DOSSIER_CODEIDE }
                    ?.uri
                    ?: (
                        fichiers.createDirectory(
                            uriRacine,
                            CheminsClasspathLsp.DOSSIER_CODEIDE,
                        ) as? AppResult.Success
                    )?.value
                    ?: return null
            return (fichiers.list(codeide) as? AppResult.Success)
                ?.value
                ?.firstOrNull { it.isDirectory && it.name == CheminsClasspathLsp.DOSSIER_LOCAL }
                ?.uri
                ?: (fichiers.createDirectory(codeide, CheminsClasspathLsp.DOSSIER_LOCAL) as? AppResult.Success)?.value
        }
    }

/**
 * Cas d'usage « lire le classpath LSP » (ADR 0058) : lit
 * `.codeide/local/lsp-classpath.json` sous la racine du projet — le
 * point d'entrée des fonctionnalités LSP à venir, qui consomment le
 * classpath PRÉPARÉ sans solliciter l'orchestrateur.
 *
 * Tolérant par construction : un projet sans classpath préparé (première
 * ouverture avant la sync, dossier importé), un fichier absent, illisible
 * ou corrompu retournent `null` — jamais d'erreur ni de blocage, la
 * préparation est un confort.
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — la lecture tolérante
 * retourne null à chaque étape absente (pas de .codeide, pas de local,
 * pas de fichier, illisible, corrompu) : c'est le contrat même du cas
 * d'usage, pas un enchevêtrement de conditions.
 */
@Suppress("ReturnCount")
public class LireClasspathLspUseCase
    @Inject
    constructor(
        private val fichiers: FileSystem,
    ) {
        /**
         * Lit le classpath LSP persisté du projet.
         *
         * @param uriRacine URI de document du dossier racine du projet.
         * @return l'état persisté, ou `null` si absent/illisible/corrompu.
         */
        public suspend operator fun invoke(uriRacine: String): EtatClasspathLsp? {
            val dossierCodeide = dossierEnfant(uriRacine, CheminsClasspathLsp.DOSSIER_CODEIDE) ?: return null
            val dossierLocal = dossierEnfant(dossierCodeide, CheminsClasspathLsp.DOSSIER_LOCAL) ?: return null
            val fichier =
                (fichiers.list(dossierLocal) as? AppResult.Success)
                    ?.value
                    ?.firstOrNull { !it.isDirectory && it.name == CheminsClasspathLsp.NOM_FICHIER }
                    ?: return null
            val contenu = (fichiers.readText(fichier.uri) as? AppResult.Success)?.value ?: return null
            return runCatching { CODEC.decodeFromString(EtatClasspathLsp.serializer(), contenu) }.getOrNull()
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

/** Emplacements internes du classpath LSP (partagés lecture/écriture). */
private object CheminsClasspathLsp {
    /** Dossier des métadonnées du projet. */
    const val DOSSIER_CODEIDE = ".codeide"

    /** Sous-dossier local, non synchronisé (gitignore des modèles). */
    const val DOSSIER_LOCAL = "local"

    /** Nom du fichier de classpath LSP. */
    const val NOM_FICHIER = "lsp-classpath.json"

    /** Type MIME du fichier de classpath LSP. */
    const val MIME_JSON = "application/json"
}

/** Codec JSON du classpath LSP (tolérant aux champs inconnus). */
private val CODEC: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

private const val TAG = "ClasspathLsp"
