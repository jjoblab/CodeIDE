package jo.codeide.core.domain.templates

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.EtatParametre
import jo.codeide.core.model.MessageRegle
import jo.codeide.core.model.RenduParametre
import jo.codeide.core.model.SeveriteRegle
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des règles déclaratives livrées en L1 du chantier générateur
 * (ADR 0153/0154/0158) : `enabledWhen` + état désactivé, contraintes de
 * projet (`ERREUR` bloquante), `autoDefinit`, variable automatique
 * `plateformeMajeure` (nombres + comparaisons ordonnées), libellés de choix
 * déclaratifs, groupes, zone « Avancé », dérivation `appNameFromNom`.
 */
class TemplateReglesTest {
    /** Moteur sur le fixture des règles. */
    private fun moteur(): TemplateEngine = TemplateEngine(FixtureRegles.source())

    /** Évaluation du formulaire des règles, paramétrable. */
    @Suppress("LongParameterList") // Aides de test : chaque paramètre fixe une entrée distincte.
    private suspend fun evaluer(
        nom: String = "Mon super carnet",
        valeurs: Map<String, String> = emptyMap(),
        manuelles: Set<String> = emptySet(),
        langue: String = "fr",
        plateformeMajeure: Int = 36,
    ) = moteur().evaluerFormulaire(
        FixtureRegles.charge(),
        nom,
        "Jeanne",
        valeurs,
        manuelles,
        langue,
        plateformeMajeure,
    )

    private fun parId(evaluation: jo.codeide.core.model.TemplateFormEvaluation) =
        evaluation.parameters.associateBy { it.parameterId }

    // ------------------------------------------------- libellés de choix (0154)

    @Test
    fun `les choix portent leurs textes déclarés`() = runTest {
        val langage = parId(evaluer()).getValue("langage")

        assertEquals(2, langage.choix.size)
        val kotlin = langage.choix.first { it.valeur == "kotlin" }
        assertEquals("Kotlin", kotlin.libelle)
        assertEquals("Par défaut", kotlin.sousTitre)
        assertEquals("Concis et sûr face au null", kotlin.description)
        assertEquals("kt", kotlin.icone)
        // Les textes vides du manifeste restent vides : pas de repli.
        val java = langage.choix.first { it.valeur == "java" }
        assertEquals("Java", java.libelle)
        assertEquals("", java.sousTitre)
        assertEquals("", java.description)
        assertEquals("", java.icone)
        // La liste brute est inchangée (contrat des étapes précédentes).
        assertEquals(listOf("kotlin", "java"), langage.choices)
    }

    @Test
    fun `un choix sans clé retombe sur la valeur brute`() = runTest {
        // Seul « 36 » porte un sous-titre : les autres valeurs restent brutes.
        val cible = parId(evaluer()).getValue("targetSdk")
        val trenteQuatre = cible.choix.first { it.valeur == "34" }
        assertEquals("34", trenteQuatre.libelle)
        assertEquals("", trenteQuatre.sousTitre)
        assertEquals("Android 15", cible.choix.first { it.valeur == "36" }.sousTitre)
    }

    @Test
    fun `un paramètre hors CHOICE n expose aucun choix`() = runTest {
        assertTrue(parId(evaluer()).getValue("avecRoom").choix.isEmpty())
    }

    @Test
    fun `les libellés de choix suivent la langue demandée`() = runTest {
        val anglais = parId(evaluer(langue = "en")).getValue("langage")
        assertEquals("Default", anglais.choix.first { it.valeur == "kotlin" }.sousTitre)
    }

    // ------------------------------------------------------- groupes et avance

    @Test
    fun `groupe, titre résolu et zone avancée sont exposés`() = runTest {
        val evaluation = evaluer()
        val langage = parId(evaluation).getValue("langage")
        assertEquals("langageSdk", langage.groupe)
        assertEquals("Langage et SDK", langage.groupeTitre)
        assertFalse(langage.avance)

        val targetSdk = parId(evaluation).getValue("targetSdk")
        assertEquals("langageSdk", targetSdk.groupe)
        assertTrue(targetSdk.avance)
    }

    @Test
    fun `un titre de groupe absent retombe sur l identifiant et un hors groupe reste vide`() = runTest {
        val evaluation = evaluer()
        // « qualite » n'a pas de clé groupe.qualite.titre : repli sur l'id.
        val room = parId(evaluation).getValue("avecRoom")
        assertEquals("qualite", room.groupe)
        assertEquals("qualite", room.groupeTitre)

        // Hors groupe : identifiant null, titre vide.
        val navigation = parId(evaluation).getValue("includeNavigation")
        assertNull(navigation.groupe)
        assertEquals("", navigation.groupeTitre)
    }

    @Test
    fun `le rendu déclaré est exposé, l absence garde le repli`() = runTest {
        val evaluation = evaluer()
        assertEquals(RenduParametre.TUILES_SEGMENTEES, parId(evaluation).getValue("langage").rendu)
        assertNull(parId(evaluation).getValue("targetSdk").rendu)
    }

    // ------------------------------------------------------- enabledWhen (0153)

    @Test
    fun `enabledWhen faux désactive avec sa raison résolue`() = runTest {
        val evaluation = evaluer(valeurs = mapOf("langage" to "java"))
        val navigation = parId(evaluation).getValue("includeNavigation")
        assertTrue(navigation.visible)
        val desactive = navigation.etat as? EtatParametre.Desactive
            ?: error("l'état doit être Desactive : ${navigation.etat}")
        assertEquals("La navigation exige un écran d'activité", desactive.raison)
        // Les autres paramètres restent actifs.
        assertTrue(parId(evaluation).getValue("avecRoom").etat is EtatParametre.Visible)
    }

    @Test
    fun `la raison suit la langue demandée`() = runTest {
        val evaluation = evaluer(valeurs = mapOf("langage" to "java"), langue = "en")
        val navigation = parId(evaluation).getValue("includeNavigation")
        assertEquals(
            "Navigation requires an activity screen",
            (navigation.etat as EtatParametre.Desactive).raison,
        )
    }

    @Test
    fun `un paramètre désactivé retombe sur son défaut et n est pas validé`() = runTest {
        // Saisie parasite + désactivation : ni validée, ni conservée côté
        // génération — le champ vaut son défaut (ADR 0153 §2).
        val evaluation = evaluer(
            valeurs = mapOf("langage" to "java", "includeNavigation" to "nimporte"),
        )
        val navigation = parId(evaluation).getValue("includeNavigation")
        assertTrue(navigation.etat is EtatParametre.Desactive)
        assertEquals("false", navigation.effectiveValue)
        assertNull(navigation.error)
        assertTrue(evaluation.isValid)
    }

    @Test
    fun `un paramètre actif garde l état Visible`() = runTest {
        assertTrue(parId(evaluer()).getValue("avecKsp").etat is EtatParametre.Visible)
    }

    // ------------------------------------------------------- autoDefinit (0153)

    @Test
    fun `autoDefinit positionne sa cible et l annonce en INFO`() = runTest {
        val evaluation = evaluer(valeurs = mapOf("avecRoom" to "true"))
        assertEquals("true", parId(evaluation).getValue("avecKsp").effectiveValue)
        val annonce = evaluation.messages.first { it.cle == "regle.ksp.auto" }
        assertEquals(SeveriteRegle.INFO, annonce.severite)
        assertEquals("KSP sera ajouté automatiquement", annonce.texte)
    }

    @Test
    fun `autoDefinit n écrase pas un champ figé à la main`() = runTest {
        val evaluation = evaluer(
            valeurs = mapOf("avecRoom" to "true", "avecKsp" to "false"),
            manuelles = setOf("avecKsp"),
        )
        assertEquals("false", parId(evaluation).getValue("avecKsp").effectiveValue)
        // L'annonce INFO reste émise quand la clé est fournie (ADR 0153 §4).
        assertTrue(evaluation.messages.any { it.cle == "regle.ksp.auto" })
    }

    @Test
    fun `autoDefinit muette quand sa condition est fausse`() = runTest {
        val evaluation = evaluer()
        assertEquals("false", parId(evaluation).getValue("avecKsp").effectiveValue)
        assertTrue(evaluation.messages.none { it.cle == "regle.ksp.auto" })
    }

    // ------------------------------------- plateformeMajeure et contraintes (0153)

    @Test
    fun `la variable plateformeMajeure est injectée comme un nombre`() = runTest {
        // targetSdk = 36 : aucune règle ERREUR avec la plateforme 36…
        val aligne = evaluer()
        assertTrue(aligne.messages.none { it.severite == SeveriteRegle.ERREUR })
        assertTrue(aligne.isValid)
        // …et la même combinaison bloque avec une plateforme inférieure —
        // la comparaison est bien numérique (36 > 34), jamais lexicographique.
        val bloque = evaluer(plateformeMajeure = 34)
        assertTrue(bloque.messages.any { it.severite == SeveriteRegle.ERREUR })
        assertFalse(bloque.isValid)
    }

    @Test
    fun `une règle ERREUR rend la combinaison invalide avec son message résolu`() = runTest {
        val evaluation = evaluer(valeurs = mapOf("targetSdk" to "37"))
        assertFalse(evaluation.isValid)
        val regle = evaluation.messages.first { it.cle == "regle.target.haut" }
        assertEquals(SeveriteRegle.ERREUR, regle.severite)
        assertEquals("Le SDK cible dépasse la plateforme de compilation", regle.texte)
    }

    @Test
    fun `une règle ERREUR refuse la planification même si l UI est contournée`() = runTest {
        val resultat =
            moteur().planifier(
                FixtureRegles.charge(),
                RequeteGeneration(
                    nom = "Mon super carnet",
                    description = "Test",
                    valeursParametres = mapOf("targetSdk" to "37"),
                    modifiesManuellement = emptySet(),
                    options = TemplateOptions(),
                    auteur = "Jeanne",
                    annee = "2026",
                    versionGenerateur = "test",
                    plateformeMajeure = 36,
                ),
            )
        val erreur = (resultat as AppResult.Failure).error
        val details = (erreur as? AppError.Validation)?.details ?: erreur.toString()
        assertTrue(details.contains("règles de projet bloquantes"))
        assertTrue(details.contains("regle.target.haut"))
    }

    @Test
    fun `AVERTISSEMENT et INFO s annoncent sans bloquer la planification`() = runTest {
        val evaluation = evaluer(valeurs = mapOf("avecRoom" to "true", "langage" to "java"))
        // langage = java : l'AVERTISSEMENT Room+Kotlin ne tire plus, l'INFO Java oui.
        assertEquals(
            listOf("regle.ksp.auto", "regle.java.info"),
            evaluation.messages.map { it.cle },
        )
        assertTrue(evaluation.messages.none { it.severite == SeveriteRegle.ERREUR })
        assertTrue(evaluation.isValid)

        val resultat =
            moteur().planifier(
                FixtureRegles.charge(),
                RequeteGeneration(
                    nom = "Mon super carnet",
                    description = "Test",
                    valeursParametres = mapOf("avecRoom" to "true", "langage" to "java"),
                    modifiesManuellement = emptySet(),
                    options = TemplateOptions(contentLanguage = "fr"),
                    auteur = "Jeanne",
                    annee = "2026",
                    versionGenerateur = "test",
                    plateformeMajeure = 36,
                ),
            )
        assertTrue(resultat.getOrNull() != null)
    }

    @Test
    fun `les messages suivent la langue demandée`() = runTest {
        val evaluation = evaluer(valeurs = mapOf("targetSdk" to "37"), langue = "en")
        assertEquals(
            "Target SDK is above the compilation platform",
            evaluation.messages.first { it.cle == "regle.target.haut" }.texte,
        )
    }

    @Test
    fun `l ordre des messages suit la déclaration des règles`() = runTest {
        // Passe 4 (autoDefinit) avant passe 9 (contraintes) : KSP puis Room.
        val evaluation = evaluer(valeurs = mapOf("avecRoom" to "true"))
        assertEquals(
            listOf("regle.ksp.auto", "regle.room.kotlin"),
            evaluation.messages.map { it.cle },
        )
        assertEquals(
            listOf(SeveriteRegle.INFO, SeveriteRegle.AVERTISSEMENT),
            evaluation.messages.map { it.severite },
        )
    }

    // ------------------------------------------------- persistance des désactivés

    @Test
    fun `un paramètre désactivé n est pas persisté dans les métadonnées`() = runTest {
        val plan =
            moteur().planifier(
                FixtureRegles.charge(),
                RequeteGeneration(
                    nom = "Mon super carnet",
                    description = "Test",
                    valeursParametres = mapOf("langage" to "java", "includeNavigation" to "true"),
                    modifiesManuellement = emptySet(),
                    options = TemplateOptions(),
                    auteur = "Jeanne",
                    annee = "2026",
                    versionGenerateur = "test",
                    plateformeMajeure = 36,
                ),
            ).getOrNull() ?: error("le plan doit réussir")
        val metadata = plan.fichiers.first { it.chemin == ".codeide/project.json" }
        val texte = (metadata.contenu as jo.codeide.core.model.PlannedContent.Texte).texte
        // langage (visible) persisté ; includeNavigation (désactivé) exclu.
        assertTrue(texte.contains("\"langage\""))
        assertFalse(texte.contains("includeNavigation"))
    }

    // ---------------------------------------------------- appNameFromNom (0158)

    @Test
    fun `appNameFromNom dérive le nom d application du nom du projet`() = runTest {
        val evaluation = evaluer(nom = "Mon super carnet")
        assertEquals("MonSuperCarnet", parId(evaluation).getValue("nomApplication").effectiveValue)
        assertTrue(evaluation.isValid)
    }

    @Test
    fun `appNameFromNom capitalise un nom bas de casse et vide sans mot`() = runTest {
        assertEquals("AtelierDeTest", parId(evaluer(nom = "atelier de test")).getValue("nomApplication").effectiveValue)
        // Un nom sans caractère alphanumérique : la chaîne vide (défaut TEXT),
        // jamais un nom inventé.
        assertEquals("", parId(evaluer(nom = "!!!")).getValue("nomApplication").effectiveValue)
    }

    @Test
    fun `le nom dérivé alimente le rendu des fichiers`() = runTest {
        val plan =
            moteur().planifier(
                FixtureRegles.charge(),
                RequeteGeneration(
                    nom = "Mon super carnet",
                    description = "Test",
                    valeursParametres = emptyMap(),
                    modifiesManuellement = emptySet(),
                    options = TemplateOptions(),
                    auteur = "Jeanne",
                    annee = "2026",
                    versionGenerateur = "test",
                    plateformeMajeure = 36,
                ),
            ).getOrNull() ?: error("le plan doit réussir")
        val texte = (
            plan.fichiers.first {
                it.chemin == "Main.txt"
            }.contenu as jo.codeide.core.model.PlannedContent.Texte
            ).texte
        assertEquals("Projet Mon super carnet — MonSuperCarnet (SDK cible 36)\n", texte)
    }
}
