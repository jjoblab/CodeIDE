package jo.codeide.core.domain.templates

import jo.codeide.core.model.AppResult
import jo.codeide.core.model.getOrNull
import jo.codeide.core.testing.FakeTemplateAssetsSource
import java.io.File

/**
 * Chargeur du template *fixture* de test (section 11 : les tests du moteur
 * utilisent un modèle de test, pas les vrais modèles de l'étape 9).
 *
 * Sert les fichiers du répertoire `src/test/resources/templates/fixture`
 * via un [FakeTemplateAssetsSource] — mêmes chemins que les assets Android
 * (manifeste `template.json`, dictionnaires `i18n`, sources `files`).
 */
object FixtureModele {
    /** Identifiant du modèle de test. */
    const val ID = "fixture"

    private val racine: File =
        File(
            FixtureModele::class.java.classLoader
                .getResource("templates/fixture")!!
                .toURI(),
        )

    /** Sert le fixture dans un faux prêt à l'emploi. */
    fun source(): FakeTemplateAssetsSource {
        val source = FakeTemplateAssetsSource()
        source.repertoires += ID
        racine.walkTopDown().filter { it.isFile }.forEach { fichier ->
            val relatif = fichier.relativeTo(racine).invariantSeparatorsPath
            source.semerFichierTemplate(ID, relatif, fichier.readBytes())
        }
        return source
    }

    /** Charge le fixture validé via le fournisseur embarqué. */
    suspend fun charge(): LoadedTemplate {
        val resultat = EmbeddedTemplatesProvider(source()).provide()
        val charges =
            (resultat as? AppResult.Success)?.value
                ?: error("le fixture de test doit charger : ${(resultat as AppResult.Failure).error}")
        return charges.single()
    }

    /** Faux dépôt de paramètres avec un auteur donné. */
    fun parametres(auteur: String = ""): jo.codeide.core.testing.FakeSettingsRepository =
        jo.codeide.core.testing.FakeSettingsRepository(
            initial =
                jo.codeide.core.model
                    .AppSettings(authorName = auteur),
        )
}
