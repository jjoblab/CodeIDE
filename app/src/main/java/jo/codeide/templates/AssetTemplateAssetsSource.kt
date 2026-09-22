package jo.codeide.templates

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.templates.TemplateAssetsSource
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation Android du port d'assets de modèles (étape 8 — section 11).
 *
 * Lit `assets/templates/` et `assets/licenses/` via l'[android.content.res.AssetManager]
 * sur le dispatcher d'E/S : le moteur reste du Kotlin pur au-dessus du port.
 *
 * Sécurité : aucun chemin ne sort des répertoires de référence — les
 * identifiants et chemins relatifs contenant `..` ou un séparateur absolu
 * sont refusés avant la moindre lecture, comme le faux des tests.
 *
 * Les erreurs sont typées comme celles du [jo.codeide.core.domain.FileSystem]
 * (Storage/Io pour une panne d'assets, Storage/NotFound pour un fichier
 * absent — un répertoire `templates/` vide est légitime : aucun modèle).
 */
@Singleton
public class AssetTemplateAssetsSource
    @Inject
    constructor(
        @param:ApplicationContext private val contexte: Context,
        private val dispatchers: DispatcherProvider,
    ) : TemplateAssetsSource {
        public override suspend fun listTemplateDirectories(): AppResult<List<String>> =
            lire { assets -> assets.list(REPERTOIRE_MODELES)?.toList() ?: emptyList() }

        public override suspend fun readTemplateFile(
            templateId: String,
            cheminRelatif: String,
        ): AppResult<ByteArray> {
            if (!idModeleSur(templateId) || !cheminInterne(cheminRelatif)) {
                return AppResult.Failure(
                    AppError.Storage(AppError.StorageReason.NotFound, "$templateId/$cheminRelatif"),
                )
            }
            return lire { assets ->
                assets.open("$REPERTOIRE_MODELES/$templateId/$cheminRelatif").use { it.readBytes() }
            }
        }

        public override suspend fun readLicenseFile(nomFichier: String): AppResult<ByteArray> {
            if (!nomFichierSimple(nomFichier)) {
                return AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, nomFichier))
            }
            return lire { assets -> assets.open("$REPERTOIRE_LICENCES/$nomFichier").use { it.readBytes() } }
        }

        /** Exécute [bloc] sur le dispatcher d'E/S et type les pannes. */
        private suspend fun <T> lire(bloc: (android.content.res.AssetManager) -> T): AppResult<T> =
            try {
                AppResult.Success(
                    kotlinx.coroutines.withContext(dispatchers.io) { bloc(contexte.assets) },
                )
            } catch (absent: java.io.FileNotFoundException) {
                AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, absent.message ?: ""))
            } catch (io: IOException) {
                AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, io.message ?: ""))
            }

        /** Un identifiant de modèle est un simple nom de répertoire. */
        private fun idModeleSur(id: String): Boolean = id.isNotEmpty() && "/" !in id && "\\" !in id && ".." !in id

        /** Un chemin interne ne monte jamais et n'est jamais absolu. */
        private fun cheminInterne(chemin: String): Boolean =
            !chemin.startsWith("/") && "\\" !in chemin && chemin.split("/").none { it == ".." || it.isEmpty() }

        /** Un nom de fichier de licence est plat, sans traversal. */
        private fun nomFichierSimple(nom: String): Boolean =
            nom.isNotEmpty() && "/" !in nom && "\\" !in nom && ".." !in nom

        private companion object {
            /** Racine des modèles embarqués. */
            const val REPERTOIRE_MODELES = "templates"

            /** Racine des licences de référence. */
            const val REPERTOIRE_LICENCES = "licenses"
        }
    }
