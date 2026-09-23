package jo.codeide.core.domain

import jo.codeide.core.domain.templates.TemplateEngine
import jo.codeide.core.model.AppResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Type de projet reconnu à l'ouverture de l'espace de travail (étape 18) :
 * le contenu utile de `.codeide/project.json`, lu pour afficher **le vrai
 * modèle** d'un projet — y compris un dossier importé dont le registre ne
 * connaît que la sentinelle [jo.codeide.core.model.TemplateId.IMPORTED].
 *
 * @property templateId identifiant du modèle déclaré par le fichier
 *   (`kotlin-jvm`, `java`…), jamais blanc.
 * @property templateVersion version du modèle au moment de la création,
 *   ou `null` si le fichier ne la porte pas.
 * @property generateur version du générateur CodeIDE ayant créé le projet,
 *   ou `null` si le fichier ne la porte pas.
 */
public data class TypeProjetReconnu(
    public val templateId: String,
    public val templateVersion: String? = null,
    public val generateur: String? = null,
)

/**
 * Cas d'usage « reconnaître le type d'un projet » (étape 18) : lit
 * `.codeide/project.json` sous la racine du projet et en extrait le
 * modèle déclaré.
 *
 * Tolérant par construction, comme [LireEtatEspaceUseCase] : un dossier
 * importé sans `.codeide`, un fichier absent, illisible, corrompu, d'un
 * schéma plus récent que celui connu ou portant un identifiant blanc
 * retournent `null` — jamais d'erreur ni de blocage, la reconnaissance
 * est un confort d'affichage, pas une précondition de l'édition.
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — la lecture tolérante
 * retourne null à chaque étape absente ou invalide (pas de `.codeide`,
 * pas de fichier, illisible, corrompu, schéma inconnu, identifiant
 * vide) : c'est le contrat même du cas d'usage, pas un enchevêtrement
 * de conditions.
 */
@Suppress("ReturnCount")
public class ReconnaitreTypeProjetUseCase
    @Inject
    constructor(
        private val fichiers: FileSystem,
    ) {
        /**
         * Reconnaît le type du projet.
         *
         * @param uriRacine URI de document du dossier racine du projet.
         * @return le type déclaré, ou `null` si absent/illisible/invalide.
         */
        public suspend operator fun invoke(uriRacine: String): TypeProjetReconnu? {
            val dossierCodeide = dossierEnfant(uriRacine, CheminsProjet.DOSSIER_CODEIDE) ?: return null
            val fichier =
                (fichiers.list(dossierCodeide) as? AppResult.Success)
                    ?.value
                    ?.firstOrNull { !it.isDirectory && it.name == CheminsProjet.NOM_FICHIER }
                    ?: return null
            val contenu = (fichiers.readText(fichier.uri) as? AppResult.Success)?.value ?: return null
            val metadata =
                runCatching { CODEC.decodeFromString(MetadataLue.serializer(), contenu) }.getOrNull()
                    ?: return null
            // Schéma plus récent que celui connu : format inconnu, on s'abstient.
            if (metadata.schemaVersion > TemplateEngine.SCHEMA_METADATA) return null
            if (metadata.templateId.isBlank()) return null
            return TypeProjetReconnu(
                templateId = metadata.templateId,
                templateVersion = metadata.templateVersion?.takeIf(String::isNotBlank),
                generateur = metadata.generator?.takeIf(String::isNotBlank),
            )
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
 * Projection de lecture de `.codeide/project.json` — volontairement
 * **indépendante** du DTO d'écriture du moteur ([TemplateEngine]) : la
 * lecture ignore les clés inconnues (tolérance ascendante) et ne retient
 * que les champs utiles à la reconnaissance ; le schéma du fichier est
 * écrit par le moteur, lu ici, et gardé aligné par
 * [TemplateEngine.SCHEMA_METADATA].
 */
@Serializable
private data class MetadataLue(
    val schemaVersion: Int,
    val templateId: String,
    val templateVersion: String? = null,
    val generator: String? = null,
    @SerialName("parameters")
    val parameters: Map<String, String> = emptyMap(),
)

/** Emplacements internes du fichier de métadonnées du projet. */
private object CheminsProjet {
    /** Dossier des métadonnées du projet. */
    const val DOSSIER_CODEIDE = ".codeide"

    /** Nom du fichier de métadonnées. */
    const val NOM_FICHIER = "project.json"
}

/** Codec JSON tolérant : clés inconnues ignorées, champs absents permis. */
private val CODEC: Json =
    Json {
        ignoreUnknownKeys = true
    }
