package jo.codeide.tools.generateur

import jo.codeide.core.domain.templates.TemplateAssetsSource
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import java.io.File
import java.io.IOException

/**
 * Port d'assets de modèles au-dessus d'un répertoire du disque (étape 9,
 * ADR 0019) : mêmes sémantiques et mêmes garanties de sécurité que
 * [jo.codeide.templates.AssetTemplateAssetsSource] (l'implémentation Android
 * de l'application), pour piloter le moteur avec les **vrais** assets du
 * dépôt (`app/src/main/assets`) depuis la ligne de commande.
 *
 * Sécurité : aucun chemin ne sort des répertoires de référence — identifiants
 * et chemins relatifs contenant `..` ou un séparateur absolu refusés avant
 * la moindre lecture. Les erreurs sont typées comme celles du port Android
 * (Storage/Io pour une panne, Storage/NotFound pour un fichier absent).
 *
 * @property racineAssets répertoire racine des assets (celui qui contient
 * `templates/` et `licenses/`).
 */
public class FichierTemplateAssetsSource(
    private val racineAssets: File,
) : TemplateAssetsSource {
    public override suspend fun listTemplateDirectories(): AppResult<List<String>> {
        val repertoire = File(racineAssets, REPERTOIRE_MODELES)
        val repertoires =
            try {
                repertoire
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory }
                    .map { it.name }
            } catch (io: IOException) {
                return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, io.message ?: ""))
            }
        return AppResult.Success(repertoires)
    }

    public override suspend fun readTemplateFile(
        templateId: String,
        cheminRelatif: String,
    ): AppResult<ByteArray> {
        if (!idModeleSur(templateId) || !cheminInterne(cheminRelatif)) {
            return AppResult.Failure(
                AppError.Storage(AppError.StorageReason.NotFound, "$templateId/$cheminRelatif"),
            )
        }
        return lire(File(File(racineAssets, REPERTOIRE_MODELES), "$templateId/$cheminRelatif"))
    }

    public override suspend fun readLicenseFile(nomFichier: String): AppResult<ByteArray> {
        if (!nomFichierSimple(nomFichier)) {
            return AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, nomFichier))
        }
        return lire(File(File(racineAssets, REPERTOIRE_LICENCES), nomFichier))
    }

    /** Lit tout un fichier en octets, erreurs typées. */
    private fun lire(fichier: File): AppResult<ByteArray> {
        if (!fichier.isFile) {
            return AppResult.Failure(
                AppError.Storage(AppError.StorageReason.NotFound, fichier.toString()),
            )
        }
        return try {
            AppResult.Success(fichier.readBytes())
        } catch (io: IOException) {
            AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, io.message ?: ""))
        }
    }

    /** Un identifiant de modèle est un simple nom de répertoire. */
    private fun idModeleSur(id: String): Boolean = id.isNotEmpty() && "/" !in id && "\\" !in id && ".." !in id

    /** Un chemin interne ne monte jamais et n'est jamais absolu. */
    private fun cheminInterne(chemin: String): Boolean =
        !chemin.startsWith("/") && !chemin.contains(":") && "\\" !in chemin &&
            chemin.split("/").none { it == ".." || it.isEmpty() }

    /** Un nom de fichier de licence est plat, sans traversal. */
    private fun nomFichierSimple(nom: String): Boolean = nom.isNotEmpty() && "/" !in nom && "\\" !in nom && ".." !in nom

    private companion object {
        /** Racine des modèles embarqués. */
        const val REPERTOIRE_MODELES = "templates"

        /** Racine des licences de référence. */
        const val REPERTOIRE_LICENCES = "licenses"
    }
}
