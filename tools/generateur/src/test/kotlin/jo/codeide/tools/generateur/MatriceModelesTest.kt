package jo.codeide.tools.generateur

import jo.codeide.core.domain.templates.EvaluateTemplateFormUseCase
import jo.codeide.core.model.TemplateId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests du générateur de matrice (lot L2, ADR 0157 §1) sur le modèle
 * *fixture-matrice* : couverture des paires (propriété), respect des
 * masquages (`visibleWhen`/`enabledWhen` — un paramètre masqué ou désactivé
 * ne multiplie pas les cas), exclusion des règles `ERREUR`, stratégie
 * `bornes`, dépendances automatiques et déterminisme de la dérivation.
 */
class MatriceModelesTest {
    private val racineAssets = File(javaClass.classLoader.getResource("assets")!!.file)

    private fun generateur(): GenerateurMatrice = GenerateurMatrice(
        EvaluateTemplateFormUseCase(
            MontageOutil(
                repertoireAssets = racineAssets,
                auteur = "CodeIDE",
                instantFixeMillis = INSTANT_FIXE,
                versionGenerateur = "0.210.0-test",
            ).planificateur,
        ),
    )

    private val optionsPaires = OptionsMatrice(strategie = StrategieMatrice.PAIRES)

    // ------------------------------------------------------------ exhaustif

    @Test
    fun `l espace exhaustif est dédupliqué par les masquages`() = runBlocking {
        val resultat = generateur().deriver(
            listOf(TemplateId("fixture-matrice")),
            OptionsMatrice(strategie = StrategieMatrice.EXHAUSTIF),
        )

        // 16 candidats énumérés ; les cas ne différant que par un paramètre
        // masqué (`extras` en mode simple) ou désactivé (`limite` sans
        // extension) sont équivalents ; les 4 candidats `extras == b` en
        // mode avancé sont exclus par la règle ERREUR — 5 cas valides restent.
        val bilan = resultat.bilans.single()
        assertEquals(16, bilan.candidats)
        assertEquals(12, bilan.valides)
        assertEquals(5, bilan.retenues)
        assertEquals(4, bilan.exclues)
        assertTrue(bilan.pairesCouvertesEntierement)
    }

    @Test
    fun `les combinaisons émises ne portent que les paramètres actifs`() = runBlocking {
        val resultat = generateur().deriver(
            listOf(TemplateId("fixture-matrice")),
            OptionsMatrice(strategie = StrategieMatrice.EXHAUSTIF),
        )

        // Cas « tout par défaut » : `extras` (masqué) et `limite` (désactivé)
        // sont absents ; ils valent leur défaut côté moteur.
        val defauts = resultat.combinaisons.single { it.parametres.size == 2 }
        assertEquals("fixture-matrice", defauts.templateId)
        assertEquals(mapOf("mode" to "simple", "avecExtension" to "false"), defauts.parametres)
        assertEquals(setOf("mode", "avecExtension"), defauts.modifiesManuellement)
        assertEquals("exhaustif · mode=simple, avecExtension=false", defauts.etiquette)
    }

    @Test
    fun `la dépendance automatique fait partie des valeurs émises`() = runBlocking {
        val resultat = generateur().deriver(
            listOf(TemplateId("fixture-matrice")),
            OptionsMatrice(strategie = StrategieMatrice.EXHAUSTIF),
        )

        // En mode avancé, `avecExtension` est forcé à vrai par `autoDefinit`
        // — la combinaison porte la valeur EFFECTIVE, jamais la choisie.
        val avance = resultat.combinaisons.first { it.parametres["mode"] == "avance" }
        assertEquals("true", avance.parametres["avecExtension"])
        assertTrue("avecExtension" in avance.modifiesManuellement)
    }

    @Test
    fun `l exclusion signale la règle ERREUR qui bloque`() = runBlocking {
        val resultat = generateur().deriver(
            listOf(TemplateId("fixture-matrice")),
            OptionsMatrice(strategie = StrategieMatrice.EXHAUSTIF),
        )

        val exclusions = resultat.exclusions
        assertEquals(4, exclusions.size)
        assertTrue(exclusions.all { it.raison.contains("regle.extras.b.interdit") })
        assertTrue(
            exclusions.all { it.valeurs["extras"] == "b" && it.valeurs["mode"] == "avance" },
        )
    }

    @Test
    fun `l espace exhaustif au delà du seuil est refusé explicitement`() = runBlocking {
        val erreur =
            try {
                generateur().deriver(
                    listOf(TemplateId("fixture-matrice")),
                    OptionsMatrice(strategie = StrategieMatrice.EXHAUSTIF, seuilExhaustif = 8),
                )
                null
            } catch (erreur: IllegalArgumentException) {
                erreur
            }

        assertTrue("seuil attendu", erreur?.message.orEmpty().contains("seuil"))
    }

    // -------------------------------------------------------------- paires

    @Test
    fun `la stratégie paires couvre toutes les paires atteignables`() = runBlocking {
        val resultat = generateur().deriver(listOf(TemplateId("fixture-matrice")), optionsPaires)

        // Propriété (ADR 0157 §1) : toute paire atteignable est couverte —
        // le bilan compare aux paires calculées sur les candidats valides.
        val bilan = resultat.bilans.single()
        assertEquals(bilan.pairesAtteignables, bilan.pairesCouvertes)
        assertTrue(bilan.retenues <= bilan.valides)
        // Les exclusions de candidats restent signalées quelle que soit la stratégie.
        assertEquals(4, resultat.exclusions.size)
    }

    @Test
    fun `la stratégie paires est déterministe`() = runBlocking {
        val premiere = generateur().deriver(listOf(TemplateId("fixture-matrice")), optionsPaires)
        val seconde = generateur().deriver(listOf(TemplateId("fixture-matrice")), optionsPaires)

        assertEquals(premiere.combinaisons, seconde.combinaisons)
        assertEquals(premiere.bilans, seconde.bilans)
    }

    // -------------------------------------------------------------- bornes

    @Test
    fun `la stratégie bornes couvre chaque valeur atteignable`() = runBlocking {
        val resultat = generateur().deriver(
            listOf(TemplateId("fixture-matrice")),
            OptionsMatrice(strategie = StrategieMatrice.BORNES),
        )

        // Toutes les valeurs atteignables des quatre paramètres
        // énumérables, y compris l'extrême `limite=2` (visible extension
        // activée) — 3 cas suffisent contre 5 en exhaustif.
        val bilan = resultat.bilans.single()
        assertEquals(3, bilan.retenues)
        val valeurs = resultat.combinaisons.flatMap { c -> c.parametres.map { (k, v) -> "$k=$v" } }.toSet()
        for (attendue in listOf(
            "mode=simple",
            "mode=avance",
            "avecExtension=false",
            "avecExtension=true",
            "extras=a",
            "limite=1",
            "limite=2",
        )) {
            assertTrue("valeur couverte : $attendue", attendue in valeurs)
        }
    }

    // ------------------------------------------------------------ émission

    @Test
    fun `les identifiants de cas sont stables et uniques`() = runBlocking {
        val resultat = generateur().deriver(listOf(TemplateId("fixture-matrice")), optionsPaires)

        val ids = resultat.combinaisons.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.first().startsWith("mx-fixture-matrice-"))
    }

    @Test
    fun `un modèle inconnu échoue explicitement`() = runBlocking {
        val erreur =
            try {
                generateur().deriver(listOf(TemplateId("inconnu")), optionsPaires)
                null
            } catch (erreur: IllegalStateException) {
                erreur
            }

        assertTrue("modèle inconnu attendu", erreur?.message.orEmpty().contains("inconnu"))
    }

    private companion object {
        /** Instant figé de tous les tests du harnais (15 juin 2026, midi UTC). */
        const val INSTANT_FIXE = 1_781_481_600_000L
    }
}
