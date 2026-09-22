package jo.codeide.core.domain.templates

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.getOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import javax.inject.Inject

/**
 * Fournisseur embarqué : lit les modèles déclaratifs sous `assets/templates/`
 * (étape 8 — section 11, ADR 0005).
 *
 * Ce fournisseur est du Kotlin pur par-dessus le port [TemplateAssetsSource]
 * — testable avec un faux, sans Android. Les répertoires sans
 * `template.json` (`.gitkeep`, futurs fichiers d'outillage) sont ignorés ;
 * en revanche un manifeste **présent mais invalide** échoue explicitement :
 * un catalogue amputé en silence serait un piège pour l'utilisateur.
 *
 * Contexte d'exécution attendu : suspendante, sûre sur tout dispatcher
 * (le port effectue ses lectures sur son dispatcher d'E/S).
 */
public class EmbeddedTemplatesProvider
    @Inject
    constructor(
        private val assets: TemplateAssetsSource,
    ) : ProjectTemplateProvider {
        @Suppress("ReturnCount") // Un échec par contrôle du catalogue (règle 16).
        public override suspend fun provide(): AppResult<List<LoadedTemplate>> {
            val repertoires =
                assets.listTemplateDirectories().getOrNull()
                    ?: return AppResult.Failure(
                        AppError.Template("impossible de lister assets/templates/"),
                    )

            val charges = mutableListOf<LoadedTemplate>()
            val idsVus = mutableSetOf<String>()
            for (repertoire in repertoires.sorted()) {
                val manifesteOctets =
                    assets.readTemplateFile(repertoire, FICHIER_MANIFESTE).getOrNull()
                        // Pas de template.json : ce n'est pas un modèle (fichier
                        // d'outillage, .gitkeep) — ignoré sans erreur.
                        ?: continue

                val template =
                    try {
                        TemplateManifestParser.analyser(manifesteOctets, repertoire)
                    } catch (erreur: TemplateManifestException) {
                        return AppResult.Failure(AppError.Template(erreur.message ?: "manifeste invalide"))
                    }

                val dictionnaires =
                    try {
                        chargerDictionnaires(repertoire)
                    } catch (erreur: TemplateManifestException) {
                        return AppResult.Failure(AppError.Template(erreur.message ?: "dictionnaire i18n invalide"))
                    }
                if (dictionnaires == null) {
                    return AppResult.Failure(
                        AppError.Template(
                            "dictionnaire i18n anglais requis manquant pour « $repertoire » (i18n/en.json)",
                        ),
                    )
                }

                if (!idsVus.add(template.id.value)) {
                    return AppResult.Failure(
                        AppError.Template("modèle en double dans les fournisseurs : « ${template.id.value} »"),
                    )
                }
                charges += LoadedTemplate(template, dictionnaires)
            }
            return AppResult.Success(charges.toList())
        }

        /**
         * Charge `i18n/en.json` (requis) et `i18n/fr.json` (optionnel).
         *
         * @return les dictionnaires, ou `null` si l'anglais est **absent**
         * (repli impossible) ; un dictionnaire présent mais corrompu lève
         * [TemplateManifestException] — il ne doit pas passer inaperçu.
         */
        @Suppress("SwallowedException") // JSON cassé -> erreur explicite.
        private suspend fun chargerDictionnaires(repertoire: String): Map<String, Map<String, String>>? {
            val dictionnaires = mutableMapOf<String, Map<String, String>>()
            for (langue in listOf("en", "fr")) {
                val octets =
                    assets.readTemplateFile(repertoire, "$CHEMIN_I18N/$langue.json").getOrNull()
                        ?: continue
                val dictionnaire =
                    try {
                        JsonManifestes.decodeFromString(
                            MapSerializer(String.serializer(), String.serializer()),
                            decoderUtf8(octets),
                        )
                    } catch (erreur: IllegalArgumentException) {
                        throw TemplateManifestException(
                            "dictionnaire i18n/$langue.json illisible pour « $repertoire » : ${erreur.message}",
                        )
                    }
                dictionnaires[langue] = dictionnaire
            }
            if ("en" !in dictionnaires) return null
            return dictionnaires
        }

        /** Décode les octets d'un dictionnaire (UTF-8, remplacement toléré). */
        private fun decoderUtf8(octets: ByteArray): String = String(octets, Charsets.UTF_8)

        private companion object {
            /** Nom du manifeste dans le répertoire du modèle. */
            const val FICHIER_MANIFESTE = "template.json"

            /** Répertoire des dictionnaires dans le répertoire du modèle. */
            const val CHEMIN_I18N = "i18n"
        }
    }
