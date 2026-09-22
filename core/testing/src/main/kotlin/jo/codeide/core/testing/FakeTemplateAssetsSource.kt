package jo.codeide.core.testing

import jo.codeide.core.domain.templates.TemplateAssetsSource
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import java.io.IOException

/**
 * [TemplateAssetsSource](jo.codeide.core.domain.templates.TemplateAssetsSource)
 * en mémoire : répertoires de modèles, fichiers et licences semés par le test.
 *
 * Les répertoires annoncés et les fichiers servis sont découplés — un test
 * peut annoncer un modèle sans fournir son manifeste (échec `NotFound`, le
 * fournisseur l'ignore) ou au contraire fournir un manifeste corrompu pour
 * éprouver la défaillance explicite. Les robinets `*Failure` font échouer
 * toute une famille d'appels, comme les autres fakes du module.
 */
public class FakeTemplateAssetsSource : TemplateAssetsSource {
    /** Répertoires annoncés sous `assets/templates/` (noms seuls). */
    public val repertoires: MutableSet<String> = mutableSetOf()

    /** Fichiers de modèles par `(idModèle, cheminRelatif)`. */
    public val fichiersTemplates: MutableMap<Pair<String, String>, ByteArray> = mutableMapOf()

    /** Licences de référence par nom de fichier (`mit.txt`…). */
    public val licences: MutableMap<String, ByteArray> = mutableMapOf()

    /** Quand non nulle, tout listage échoue. */
    public var listFailure: IOException? = null

    /** Quand non nulle, toute lecture de fichier de modèle échoue. */
    public var readTemplateFailure: IOException? = null

    /** Quand non nulle, toute lecture de licence échoue. */
    public var readLicenseFailure: IOException? = null

    /**
     * Sème un fichier de modèle.
     *
     * @param idModele identifiant (répertoire) du modèle.
     * @param cheminRelatif chemin relatif dans le répertoire du modèle.
     * @param octets contenu brut.
     */
    public fun semerFichierTemplate(
        idModele: String,
        cheminRelatif: String,
        octets: ByteArray,
    ) {
        fichiersTemplates[idModele to cheminRelatif] = octets
    }

    /**
     * Sème un fichier texte de modèle (raccourci encodage UTF-8).
     *
     * @param idModele identifiant du modèle.
     * @param cheminRelatif chemin relatif.
     * @param texte contenu texte.
     */
    public fun semerFichierTemplate(
        idModele: String,
        cheminRelatif: String,
        texte: String,
    ) {
        semerFichierTemplate(idModele, cheminRelatif, texte.toByteArray(Charsets.UTF_8))
    }

    /**
     * Sème une licence de référence.
     *
     * @param nomFichier nom du fichier (ex. `mit.txt`).
     * @param texte contenu texte.
     */
    public fun semerLicence(
        nomFichier: String,
        texte: String,
    ) {
        licences[nomFichier] = texte.toByteArray(Charsets.UTF_8)
    }

    public override suspend fun listTemplateDirectories(): AppResult<List<String>> {
        listFailure?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }
        return AppResult.Success(repertoires.sorted())
    }

    public override suspend fun readTemplateFile(
        templateId: String,
        cheminRelatif: String,
    ): AppResult<ByteArray> {
        readTemplateFailure?.let {
            return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: ""))
        }
        // Garde de sécurité identique à l'implémentation Android : aucun
        // chemin ne sort du répertoire du modèle.
        val octets =
            when {
                cheminRelatif.startsWith("/") || cheminRelatif.split("/").any { it == ".." } -> null
                else -> fichiersTemplates[templateId to cheminRelatif]
            }
        return octets?.let { AppResult.Success(it) }
            ?: AppResult.Failure(
                AppError.Storage(AppError.StorageReason.NotFound, "$templateId/$cheminRelatif"),
            )
    }

    public override suspend fun readLicenseFile(nomFichier: String): AppResult<ByteArray> {
        readLicenseFailure?.let {
            return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: ""))
        }
        val octets =
            when {
                nomFichier.contains('/') || nomFichier.contains('\\') || nomFichier.contains("..") -> null
                else -> licences[nomFichier]
            }
        return octets?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, nomFichier))
    }
}
