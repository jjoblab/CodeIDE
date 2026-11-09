package jo.codeide.core.domain.templates

import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.FakeTemplateAssetsSource
import java.io.File

/**
 * Chargeur du template *fixture* des règles déclaratives (lot L1 du
 * chantier générateur) : `enabledWhen`, `constraints`, `autoDefinit`,
 * `plateformeMajeure`, choix résolus, groupes, `appNameFromNom`.
 *
 * Sert les fichiers du répertoire `src/test/resources/templates/fixture-regles`
 * via un [FakeTemplateAssetsSource] — mêmes chemins que les assets Android.
 */
object FixtureRegles {
    /** Identifiant du modèle de test des règles. */
    const val ID = "fixture-regles"

    private val racine: File =
        File(
            FixtureRegles::class.java.classLoader
                .getResource("templates/fixture-regles")!!
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
                ?: error("le fixture des règles doit charger : ${(resultat as AppResult.Failure).error}")
        return charges.single()
    }
}
