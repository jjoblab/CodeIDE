package jo.codeide.core.crash

import jo.codeide.core.model.FlattenedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du stockage des rapports (section 5.8 : critères obligatoires —
 * écriture **synchrone et atomique**, limite de 256 Ko par rapport,
 * rétention de 20 rapports maximum).
 */
class CrashReportFileStoreTest {
    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    private fun store(): CrashReportFileStore =
        CrashReportFileStore(OutilsTestCrash.repertoireCrashes(dossierTemporaire.root))

    @Test
    fun `un rapport enregistré se relit par son identifiant`() {
        val store = store()
        val rapport = OutilsTestCrash.rapport(id = "premier", horodatage = 10_000L)

        store.save(rapport)

        assertEquals(rapport, store.get("premier"))
        assertNull(store.get("introuvable"))
    }

    @Test
    fun `l'écriture laisse le fichier final sans reste temporaire`() {
        val store = store()
        val repertoire = OutilsTestCrash.repertoireCrashes(dossierTemporaire.root)

        store.save(OutilsTestCrash.rapport(id = "atomique", horodatage = 10_000L))

        val noms = repertoire.list().orEmpty().toList()
        assertEquals(listOf("10000-atomique.json"), noms)
    }

    @Test
    fun `les résumés sont triés du plus récent au plus ancien`() {
        val store = store()
        store.save(OutilsTestCrash.rapport(id = "ancien", horodatage = 10_000L))
        store.save(OutilsTestCrash.rapport(id = "recent", horodatage = 90_000L))
        store.save(OutilsTestCrash.rapport(id = "moyen", horodatage = 50_000L))

        val resumes = store.listSummaries()

        assertEquals(listOf("recent", "moyen", "ancien"), resumes.map { it.id })
    }

    @Test
    fun `la rétention garde les vingt rapports les plus récents`() {
        val store = store()

        // Horodatages réalistes (13 chiffres) : le tri lexicographique
        // des noms n'est chronologique qu'à longueur constante (KDoc du
        // stockage) — condition toujours vraie en production.
        repeat(25) { index ->
            store.save(OutilsTestCrash.rapport(id = "rapport-$index", horodatage = 1_700_000_000_000L + 1_000L * index))
        }

        val resumes = store.listSummaries()
        assertEquals(CrashLimits.MAX_REPORTS, resumes.size)
        // Les 20 plus récents : rapport-24 en tête, rapport-5 le plus vieux.
        assertEquals("rapport-24", resumes.first().id)
        assertEquals("rapport-5", resumes.last().id)
    }

    @Test
    fun `la rétention emporte le témoin du rapport supprimé`() {
        val store = store()
        val repertoire = OutilsTestCrash.repertoireCrashes(dossierTemporaire.root)
        store.save(OutilsTestCrash.rapport(id = "ancien", horodatage = 1_700_000_000_000L))
        store.markReviewed("ancien")

        repeat(CrashLimits.MAX_REPORTS) { index ->
            store.save(OutilsTestCrash.rapport(id = "nouveau-$index", horodatage = 1_700_001_000_000L + 1_000L * index))
        }

        assertFalse(File(repertoire, "1700000000000-ancien.reviewed").isFile)
        assertFalse(File(repertoire, "1700000000000-ancien.json").isFile)
    }

    @Test
    fun `un rapport dépasse rarement la limite de taille grâce à la réduction`() {
        val store = store()
        val repertoire = OutilsTestCrash.repertoireCrashes(dossierTemporaire.root)
        // Rapport volontairement énorme : 60 filons de 60 Ko et 100 tranches
        // de 10 Ko — bien au-delà de la limite de 256 Ko.
        val enormesFilons =
            List(60) { index ->
                OutilsTestCrash.filon(index, message = "x".repeat(60_000))
            }
        val tranches = List(100) { "tranche très longue ".repeat(500) + it }
        val rapport =
            OutilsTestCrash
                .rapport(
                    id = "enorme",
                    horodatage = 70_000L,
                    filons = enormesFilons,
                ).copy(
                    exception =
                        FlattenedException(
                            className = "java.lang.RuntimeException",
                            message = "x".repeat(50_000),
                            frames = tranches,
                            cause = null,
                        ),
                )

        store.save(rapport)

        val fichier = repertoire.listFiles().orEmpty().single { it.name.endsWith("enorme.json") }
        assertTrue("taille réelle : ${fichier.length()}", fichier.length() <= CrashLimits.MAX_REPORT_BYTES)
        // Le rapport réduit reste lisible et identifiable.
        assertEquals("enorme", store.get("enorme")?.id)
    }

    @Test
    fun `le témoin de consultation pilote les non consultés`() {
        val store = store()
        store.save(OutilsTestCrash.rapport(id = "vu", horodatage = 10_000L))
        store.save(OutilsTestCrash.rapport(id = "pas-vu", horodatage = 20_000L))

        assertTrue(store.hasUnreviewed())

        assertTrue(store.markReviewed("vu"))
        assertTrue(store.hasUnreviewed())

        assertTrue(store.markReviewed("pas-vu"))
        assertFalse(store.hasUnreviewed())
        assertFalse(store.markReviewed("introuvable"))
    }

    @Test
    fun `la consultation rejaillit sur les résumés`() {
        val store = store()
        store.save(OutilsTestCrash.rapport(id = "vu", horodatage = 10_000L))

        store.markReviewed("vu")

        assertTrue(store.listSummaries().single().isReviewed)
    }

    @Test
    fun `la suppression retire rapport et témoin`() {
        val store = store()
        val repertoire = OutilsTestCrash.repertoireCrashes(dossierTemporaire.root)
        store.save(OutilsTestCrash.rapport(id = "cible", horodatage = 10_000L))
        store.markReviewed("cible")

        assertTrue(store.delete("cible"))
        assertFalse(store.delete("cible"))

        assertFalse(File(repertoire, "10000-cible.json").isFile)
        assertFalse(File(repertoire, "10000-cible.reviewed").isFile)
        assertNull(store.get("cible"))
    }

    @Test
    fun `la suppression totale nettoie rapports, témoins et restes`() {
        val store = store()
        val repertoire = OutilsTestCrash.repertoireCrashes(dossierTemporaire.root)
        store.save(OutilsTestCrash.rapport(id = "a", horodatage = 10_000L))
        store.save(OutilsTestCrash.rapport(id = "b", horodatage = 20_000L))
        store.markReviewed("a")
        // Reste temporaire d'un plantage interrompu.
        File(repertoire, "30000-c.json.tmp").writeText("{}")
        // Témoin orphelin d'un rapport disparu.
        File(repertoire, "40000-d.json.reviewed").writeText("")

        assertEquals(2, store.deleteAll())

        val restants =
            repertoire.list().orEmpty().filter {
                !it.startsWith("loop-history") &&
                    !it.startsWith("exit-info-marker")
            }
        assertTrue("restants : $restants", restants.isEmpty())
        assertFalse(store.hasUnreviewed())
    }

    @Test
    fun `un fichier corrompu est ignoré sans échouer`() {
        val store = store()
        val repertoire = OutilsTestCrash.repertoireCrashes(dossierTemporaire.root)
        store.save(OutilsTestCrash.rapport(id = "sain", horodatage = 10_000L))
        File(repertoire, "20000-corrompu.json").writeText("{contenu cassé")

        assertEquals(listOf("sain"), store.listSummaries().map { it.id })
        assertNull(store.get("corrompu"))
        assertEquals("sain", store.get("sain")?.id)
    }

    @Test
    fun `les témoins orphelins partent au prochain enregistrement`() {
        val store = store()
        val repertoire = OutilsTestCrash.repertoireCrashes(dossierTemporaire.root)
        File(repertoire, "1000-orphelin.json.reviewed").writeText("")

        store.save(OutilsTestCrash.rapport(id = "neuf", horodatage = 10_000L))

        assertFalse(File(repertoire, "1000-orphelin.json.reviewed").isFile)
        assertNotNull(store.get("neuf"))
    }
}
