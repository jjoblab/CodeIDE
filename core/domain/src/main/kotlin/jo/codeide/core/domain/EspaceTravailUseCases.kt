package jo.codeide.core.domain

import jo.codeide.core.domain.templates.TemplateValidators
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.RaisonValidation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Onglet ouvert persisté dans l'état de l'espace de travail (étape 17).
 *
 * @property uri URI de document SAF du fichier ouvert.
 * @property chemin chemin relatif sous la racine du projet (affichage).
 */
@Serializable
public data class OngletEspace(
    public val uri: String,
    public val chemin: String,
)

/**
 * État de l'espace de travail d'un projet, persisté dans
 * `.codeide/local/workspace-state.json` (étape 17, prompt compagnon 5.2) :
 * onglets ouverts et index actif, pour **rouvrir le projet comme il a
 * été quitté** — fichier **local, non synchronisé** avec le projet (les
 * `.gitignore` générés excluent déjà `.codeide/local/`).
 *
 * @property schema version du schéma (tolérance ascendante à la lecture).
 * @property onglets onglets ouverts, dans l'ordre.
 * @property indexActif position de l'onglet actif, −1 si aucun.
 */
@Serializable
public data class EtatEspace(
    public val schema: Int = SCHEMA_COURANT,
    public val onglets: List<OngletEspace> = emptyList(),
    public val indexActif: Int = -1,
) {
    public companion object {
        /** Schéma courant de `workspace-state.json`. */
        public const val SCHEMA_COURANT: Int = 1
    }
}

/**
 * Cas d'usage « évaluer un nom de fichier ou de dossier » (étape 17) :
 * **mêmes règles que le nom de projet du wizard** (section 12.3 —
 * longueur 1 à 64 après trim, caractères interdits, « . »/« .. », fin
 * interdite, noms réservés Windows), via le validateur partagé
 * `file-name` — une seule source de vérité pour la validation des noms.
 */
public class EvaluerNomFichierUseCase
    @Inject
    constructor() {
        /**
         * Évalue un nom de fichier ou de dossier.
         *
         * @param nom valeur saisie (un simple segment, jamais un chemin).
         * @return `null` si le nom est valide, sinon la raison typée —
         *   l'interface la traduit en ressource localisée.
         */
        public operator fun invoke(nom: String): RaisonValidation? =
            TemplateValidators.evaluer("file-name", nom)?.raison
    }

/**
 * Cas d'usage « enregistrer l'état de l'espace de travail » (étape 17) :
 * écrit `.codeide/local/workspace-state.json` sous la racine du projet,
 * en créant les dossiers intermédiaires au besoin (un dossier importé
 * peut ne pas avoir de `.codeide`).
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — chaque clause de
 * garde est une **issue** du parcours (dossier injoignable, fichier
 * incréable, succès) ; les imbriquer en expressions ferait perdre
 * l'évidence du contrat de tolérance.
 */
@Suppress("ReturnCount")
public class EnregistrerEtatEspaceUseCase
    @Inject
    constructor(
        private val fichiers: FileSystem,
    ) {
        /**
         * Enregistre l'état des onglets du projet.
         *
         * @param uriRacine URI de document du dossier racine du projet.
         * @param onglets onglets ouverts (uri, chemin relatif).
         * @param indexActif position de l'onglet actif, −1 si aucun.
         * @return le succès, ou l'échec typé (journalisé par l'appelant,
         *   jamais bloquant pour l'édition).
         */
        public suspend operator fun invoke(
            uriRacine: String,
            onglets: List<OngletEspace>,
            indexActif: Int,
        ): AppResult<Unit> {
            val dossier =
                resoudreDossierLocal(uriRacine)
                    ?: return AppResult.Failure(
                        AppError.Storage(AppError.StorageReason.Io, "dossier .codeide/local injoignable"),
                    )
            val cible =
                (fichiers.list(dossier) as? AppResult.Success)
                    ?.value
                    ?.firstOrNull { !it.isDirectory && it.name == CheminsEtatEspace.NOM_FICHIER }
                    ?.uri
                    ?: (
                        fichiers.createFile(
                            dossier,
                            CheminsEtatEspace.NOM_FICHIER,
                            CheminsEtatEspace.MIME_JSON,
                        ) as? AppResult.Success
                    )?.value
                    ?: return AppResult.Failure(
                        AppError.Storage(AppError.StorageReason.Io, "workspace-state.json incréable"),
                    )
            return fichiers.writeText(
                cible,
                CODEC.encodeToString(EtatEspace.serializer(), EtatEspace(onglets = onglets, indexActif = indexActif)),
            )
        }

        /** Résout (crée au besoin) `.codeide/local` sous la racine. */
        private suspend fun resoudreDossierLocal(uriRacine: String): String? {
            val codeide =
                (fichiers.list(uriRacine) as? AppResult.Success)
                    ?.value
                    ?.firstOrNull { it.isDirectory && it.name == CheminsEtatEspace.DOSSIER_CODEIDE }
                    ?.uri
                    ?: (
                        fichiers.createDirectory(
                            uriRacine,
                            CheminsEtatEspace.DOSSIER_CODEIDE,
                        ) as? AppResult.Success
                    )?.value
                    ?: return null
            return (fichiers.list(codeide) as? AppResult.Success)
                ?.value
                ?.firstOrNull { it.isDirectory && it.name == CheminsEtatEspace.DOSSIER_LOCAL }
                ?.uri
                ?: (fichiers.createDirectory(codeide, CheminsEtatEspace.DOSSIER_LOCAL) as? AppResult.Success)?.value
        }
    }

/**
 * Cas d'usage « lire l'état de l'espace de travail » (étape 17) : lit
 * `.codeide/local/workspace-state.json` sous la racine du projet.
 *
 * Tolérant par construction : un projet sans état (première ouverture,
 * dossier importé), un fichier absent, illisible ou corrompu retournent
 * `null` — jamais d'erreur ni de blocage, la reprise est un confort.
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — la lecture tolérante
 * retourne null à chaque étape absente (pas de .codeide, pas de local,
 * pas de fichier, illisible, corrompu) : c'est le contrat même du cas
 * d'usage, pas un enchevêtrement de conditions.
 */
@Suppress("ReturnCount")
public class LireEtatEspaceUseCase
    @Inject
    constructor(
        private val fichiers: FileSystem,
    ) {
        /**
         * Lit l'état persisté du projet.
         *
         * @param uriRacine URI de document du dossier racine du projet.
         * @return l'état enregistré, ou `null` si absent/illisible/corrompu.
         */
        public suspend operator fun invoke(uriRacine: String): EtatEspace? {
            val dossierCodeide = dossierEnfant(uriRacine, CheminsEtatEspace.DOSSIER_CODEIDE) ?: return null
            val dossierLocal = dossierEnfant(dossierCodeide, CheminsEtatEspace.DOSSIER_LOCAL) ?: return null
            val fichier =
                (fichiers.list(dossierLocal) as? AppResult.Success)
                    ?.value
                    ?.firstOrNull { !it.isDirectory && it.name == CheminsEtatEspace.NOM_FICHIER }
                    ?: return null
            val contenu = (fichiers.readText(fichier.uri) as? AppResult.Success)?.value ?: return null
            return runCatching { CODEC.decodeFromString(EtatEspace.serializer(), contenu) }.getOrNull()
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

/** Emplacements internes du fichier d'état (partagés lecture/écriture). */
private object CheminsEtatEspace {
    /** Dossier des métadonnées du projet. */
    const val DOSSIER_CODEIDE = ".codeide"

    /** Sous-dossier local, non synchronisé (gitignore des modèles). */
    const val DOSSIER_LOCAL = "local"

    /** Nom du fichier d'état. */
    const val NOM_FICHIER = "workspace-state.json"

    /** Type MIME du fichier d'état. */
    const val MIME_JSON = "application/json"
}

/** Codec JSON de l'état d'espace (tolérant aux champs inconnus). */
private val CODEC: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
