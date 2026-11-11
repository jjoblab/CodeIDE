package jo.codeide.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du modèle de l'étape 8 : options communes du moteur, code de licence
 * pour le mini-langage, égalité par valeur des contenus planifiés et
 * progression de création (section 11 du prompt maître).
 */
class TemplateModelTest {
    @Test
    fun `les options par défaut sont actives sans licence en anglais`() {
        val options = TemplateOptions()

        assertTrue(options.includeReadme)
        assertTrue(options.includeGitignore)
        assertTrue(options.includeEditorconfig)
        assertEquals(License.NONE, options.license)
        assertEquals(TemplateOptions.LANGUE_DEFAUT, options.contentLanguage)
    }

    @Test
    fun `les langues de contenu acceptées sont exactement fr et en`() {
        assertEquals(setOf("fr", "en"), TemplateOptions.LANGUES_CONTENU)
        assertTrue(TemplateOptions.langueValide("fr"))
        assertTrue(TemplateOptions.langueValide("EN"))
        assertFalse(TemplateOptions.langueValide("de"))
        assertFalse(TemplateOptions.langueValide(""))
    }

    @Test
    fun `le code de licence correspond au nom de fichier assets`() {
        assertEquals("none", License.NONE.codeTemplate())
        assertEquals("mit", License.MIT.codeTemplate())
        assertEquals("apache-2.0", License.APACHE_2_0.codeTemplate())
        assertEquals("gpl-3.0", License.GPL_3_0.codeTemplate())
        assertEquals("bsd-3-clause", License.BSD_3_CLAUSE.codeTemplate())
    }

    @Test
    fun `un contenu binaire est égal par octets et non par référence`() {
        val a = PlannedContent.Binaire(byteArrayOf(1, 2, 3))
        val b = PlannedContent.Binaire(byteArrayOf(1, 2, 3))
        val c = PlannedContent.Binaire(byteArrayOf(1, 2, 4))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertNotEquals(a, null)
        assertNotEquals(a, PlannedContent.Texte("x"))
        assertEquals("Binaire(3 octets)", a.toString())
    }

    @Test
    fun `un contenu texte porte sa chaîne rendue`() {
        assertEquals("texte", (PlannedContent.Texte("texte") as PlannedContent.Texte).texte)
        assertEquals(PlannedContent.Texte("a"), PlannedContent.Texte("a"))
    }

    @Test
    fun `un fichier planifié expose chemin groupe et contenu`() {
        val fichier =
            PlannedFile(
                chemin = "src/Main.txt",
                group = TemplateFileGroup.CORE,
                contenu = PlannedContent.Texte("Bonjour"),
            )

        assertEquals("src/Main.txt", fichier.chemin)
        assertEquals(TemplateFileGroup.CORE, fichier.group)
        assertEquals(PlannedContent.Texte("Bonjour"), fichier.contenu)
    }

    @Test
    fun `les événements de progression portent des données d'affichage`() {
        val generation =
            CreationProgress.GenerationFichier(
                index = 2,
                total = 5,
                cheminRelatif = "src/Main.txt",
            )

        assertEquals(2, generation.index)
        assertEquals(5, generation.total)
        assertEquals("src/Main.txt", generation.cheminRelatif)
        assertEquals(CreationProgress.Preparation, CreationProgress.Preparation)
        assertEquals(
            CreationProgress.CreationDossierRacine("Demo"),
            CreationProgress.CreationDossierRacine("Demo"),
        )
        assertEquals(CreationProgress.Enregistrement, CreationProgress.Enregistrement)
    }

    @Test
    fun `l'événement terminal porte résultat rollback et résidus`() {
        val terminal =
            CreationProgress.Termine(
                result = AppResult.Failure(AppError.Template("boom")),
                rolledBack = true,
                residues = listOf("a.txt"),
            )

        assertTrue(terminal.rolledBack)
        assertEquals(listOf("a.txt"), terminal.residues)
        assertTrue((terminal.result as AppResult.Failure).error is AppError.Template)
    }

    @Test
    fun `une demande de création porte modèles saisies et options`() {
        val demande =
            CreateProjectRequest(
                templateId = TemplateId("fixture"),
                name = "Demo",
                description = "Une démo",
                parentLocation = StorageLocation(grantUri = "x:", documentUri = "x:demo", displayPath = "/demo"),
                parameterValues = mapOf("version" to "1.0.0"),
                manuallySetParameters = setOf("version"),
                options = TemplateOptions(includeReadme = false),
            )

        assertEquals(TemplateId("fixture"), demande.templateId)
        assertFalse(demande.options.includeReadme)
        assertEquals(setOf("version"), demande.manuallySetParameters)
        assertEquals("1.0.0", demande.parameterValues["version"])
    }

    @Test
    fun `l'évaluation de formulaire décrit chaque paramètre`() {
        val evaluation =
            TemplateFormEvaluation(
                parameters =
                listOf(
                    TemplateParameterEvaluation(
                        parameterId = "packageName",
                        visible = true,
                        effectiveValue = "app.demo",
                        error = null,
                        label = "Paquet",
                        help = "",
                    ),
                ),
                computedValues = mapOf("estApplication" to "true"),
                isValid = true,
            )

        assertTrue(evaluation.isValid)
        assertNull(evaluation.parameters.single().error)
        assertEquals("true", evaluation.computedValues["estApplication"])
    }

    // -------------------------------------- schéma wizard ADR 0155 §1 (L3)

    @Test
    fun `un modèle sans bloc wizard n'a ni étape ni préréglage`() {
        val modele = modeleMinimal()

        assertEquals(emptyList<TemplateEtapeWizard>(), modele.etapesWizard)
        assertEquals(emptyList<TemplatePreset>(), modele.presets)
    }

    @Test
    fun `les étapes wizard et préréglages déclarés portent leurs données`() {
        val etape =
            TemplateEtapeWizard(
                id = "modules",
                titreKey = "etape.modules.titre",
                iconeKey = "icone.modules",
                quand = "includeJpa || includeSecurity",
                parametres = listOf("includeJpa", "includeSecurity"),
            )
        val etapeMinimal =
            TemplateEtapeWizard(id = "identite", titreKey = "etape.identite.titre", parametres = listOf("packageName"))
        val preset =
            TemplatePreset(
                id = "api-rest-persistance",
                nomKey = "preset.api.titre",
                descriptionKey = "preset.api.description",
                valeurs = mapOf("includeJpa" to "true", "langage" to "kotlin"),
            )
        val presetMinimal = TemplatePreset(id = "vide", nomKey = "preset.vide.titre", valeurs = emptyMap())
        val modele =
            modeleMinimal().copy(
                etapesWizard = listOf(etape, etapeMinimal),
                presets = listOf(preset, presetMinimal),
            )

        assertEquals(listOf(etape, etapeMinimal), modele.etapesWizard)
        assertEquals(listOf(preset, presetMinimal), modele.presets)
        assertEquals("etape.modules.titre", modele.etapesWizard.first().titreKey)
        assertEquals(listOf("includeJpa", "includeSecurity"), etape.parametres)
        assertNull(etapeMinimal.iconeKey)
        assertNull(etapeMinimal.quand)
        assertEquals("preset.api.description", preset.descriptionKey)
        assertNull(presetMinimal.descriptionKey)
        assertEquals("kotlin", preset.valeurs["langage"])
        // Égalité par valeur et distinction par identité de champs.
        assertEquals(etape, etape.copy())
        assertEquals(presetMinimal, presetMinimal.copy())
    }

    @Test
    fun `un modèle avec bloc wizard reste égal par valeur`() {
        val etape =
            TemplateEtapeWizard(id = "modules", titreKey = "etape.modules.titre", parametres = listOf("includeJpa"))
        val preset = TemplatePreset(id = "api", nomKey = "preset.api.titre", valeurs = mapOf("includeJpa" to "true"))
        val avec = modeleMinimal().copy(etapesWizard = listOf(etape), presets = listOf(preset))
        val pareil = modeleMinimal().copy(etapesWizard = listOf(etape), presets = listOf(preset))

        assertEquals(avec, pareil)
        assertEquals(avec.hashCode(), pareil.hashCode())
        assertNotEquals(avec, modeleMinimal())
    }

    /** Modèle de fixture réduit, hors bloc wizard (défauts ADR 0155 §1). */
    private fun modeleMinimal(): ProjectTemplate = ProjectTemplate(
        id = TemplateId("fixture"),
        templateVersion = "1.0.0",
        nameKey = "modele.fixture.nom",
        descriptionKey = "modele.fixture.description",
        category = "jvm",
        iconKey = "icone.fixture",
        tags = listOf("fixture"),
        parameters =
        listOf(
            TemplateParameter(
                id = "includeJpa",
                type = TemplateParameterType.BOOLEAN,
                labelKey = "param.includeJpa.libelle",
                defaultValue = "false",
            ),
        ),
        computedVariables = listOf(TemplateComputedVariable(name = "estPersistence", expression = "includeJpa")),
        fichiers =
        listOf(
            TemplateFile(chemin = "README.md", source = "readme.tpl"),
        ),
    )
}
