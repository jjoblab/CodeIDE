package jo.codeide.feature.editor

import jo.codeide.core.domain.InfoTache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du constructeur PUR des rangées de la feuille des tâches (v4, §3.3) :
 * le filtre de recherche en direct (nom affiché ou chemin, insensible à la
 * casse), les GROUPES dans l'ordre d'apparition, les clés stables.
 */
class FeuilleTachesRangeesTest {
    private fun tache(
        chemin: String,
        groupe: String? = null,
        nom: String = chemin.substringAfterLast(':'),
    ): InfoTache = InfoTache(chemin = chemin, groupe = groupe, nomAffiche = nom)

    // ---- Regroupement -----------------------------------------------------

    @Test
    fun `les taches se regroupent par groupe dans l ordre d apparition`() {
        val taches =
            listOf(
                tache(":app:assembleDebug", "build"),
                tache(":app:testDebugUnitTest", "verification"),
                tache(":app:assembleAndroidTest", "build"),
                tache("clean", null),
            )

        val rangees = construireRangeesTaches(taches, "")

        assertEquals(
            listOf(
                RangeeTache.EnTete("build"),
                RangeeTache.Tache(taches[0]),
                RangeeTache.Tache(taches[2]),
                RangeeTache.EnTete("verification"),
                RangeeTache.Tache(taches[1]),
                RangeeTache.EnTete(""),
                RangeeTache.Tache(taches[3]),
            ),
            rangees,
        )
    }

    @Test
    fun `une seule tache sans groupe donne un en tete vide`() {
        val taches = listOf(tache("clean", null))

        val rangees = construireRangeesTaches(taches, "")

        assertEquals(
            listOf(RangeeTache.EnTete(""), RangeeTache.Tache(taches[0])),
            rangees,
        )
    }

    // ---- Recherche (filtre en direct) --------------------------------------

    @Test
    fun `la recherche filtre sur le nom affiche et le chemin sans casse`() {
        val taches =
            listOf(
                tache(":app:assembleDebug", "build", "assembleDebug"),
                tache(":app:lintDebug", "verification", "lintDebug"),
                tache(":core:ui:build", "build", "build"),
            )

        val parNom = construireRangeesTaches(taches, "ASSEMBLE")
        assertEquals(listOf(RangeeTache.Tache(taches[0])), parNom.filterIsInstance<RangeeTache.Tache>())

        val parChemin = construireRangeesTaches(taches, ":core:ui")
        assertEquals(listOf(RangeeTache.Tache(taches[2])), parChemin.filterIsInstance<RangeeTache.Tache>())

        val sansCorrespondance = construireRangeesTaches(taches, "deploy")
        assertTrue(sansCorrespondance.isEmpty())
    }

    @Test
    fun `la recherche vide ou blanche garde tout`() {
        val taches = listOf(tache(":app:build", "build"))

        assertEquals(2, construireRangeesTaches(taches, "").size)
        assertEquals(2, construireRangeesTaches(taches, "   ").size)
    }

    // ---- Clés stables (mise à jour en place) -------------------------------

    @Test
    fun `les cles sont stables par groupe et par chemin de tache`() {
        val taches =
            listOf(
                tache(":app:build", "build"),
                tache(":app:test", "verification"),
            )

        val rangees = construireRangeesTaches(taches, "")

        assertEquals(
            listOf("groupe-build", "tache-:app:build", "groupe-verification", "tache-:app:test"),
            rangees.map { it.idCle },
        )
    }
}
