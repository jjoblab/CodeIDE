package jo.codeide.core.logging

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du stockage JSONL (critère d'acceptation de l'étape 2 : fichiers
 * créés et rotés). Répertoire temporaire, sans Android : pure JVM.
 */
class JsonlLogStoreTest {
    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun store(): JsonlLogStore = JsonlLogStore(dossierTemporaire.root, json)

    /** Une ligne JSONL sérialisée d'entrée, d'environ [poids] octets. */
    private fun ligne(
        index: Int,
        poids: Int = 3,
    ): String {
        val bourrage = "x".repeat(poids * 20)
        return """{"timestampMillis":$index,"sessionId":"s","level":"INFO",""" +
            """"tag":"Test","threadName":"main","message":"entrée $index $bourrage"}"""
    }

    @Test
    fun `écrit les lignes dans le fichier courant et les relit`() {
        val store = store()

        store.appendLines(listOf(ligne(1), ligne(2), ligne(3)), maxFileSizeBytes = 1_048_576L, maxArchiveFiles = 5)

        val lues = store.readAll()
        assertEquals(3, lues.size)
        assertEquals("s", lues.first().sessionId)
        assertEquals(1, store.diskStats().fileCount)
        assertTrue(store.diskStats().bytes > 0)
    }

    @Test
    fun `fait tourner le fichier courant quand la taille maximale est atteinte`() {
        val store = store()
        val maxOctets = 150L

        // Chaque ligne fait ~90 octets : trois lignes suffisent à faire
        // tourner une fois, et la rotation est vérifiable sur les noms.
        store.appendLines(listOf(ligne(1), ligne(2), ligne(3)), maxFileSizeBytes = maxOctets, maxArchiveFiles = 5)

        val fichiers =
            dossierTemporaire.root
                .listFiles()
                .orEmpty()
                .map { it.name }
        assertTrue("archive-1.jsonl attendu parmi $fichiers", fichiers.contains("archive-1.jsonl"))
        // Les entrées restent lisibles, dans l'ordre chronologique.
        val lues = store.readAll()
        assertEquals(3, lues.size)
        assertTrue(lues.first().timestampMillis < lues.last().timestampMillis)
    }

    @Test
    fun `plafonne le nombre d'archives lors des rotations répétées`() {
        val store = store()

        repeat(10) { salve ->
            store.appendLines(listOf(ligne(salve)), maxFileSizeBytes = 1L, maxArchiveFiles = 2)
        }

        val indices =
            dossierTemporaire.root
                .listFiles()
                .orEmpty()
                .map { it.name }
                .filter { it.startsWith("archive-") }
        // maxArchiveFiles = 2 : au plus archive-1 et archive-2, plus le
        // fichier courant.
        assertTrue(indices.size <= 2)
        assertTrue(indices.all { it == "archive-1.jsonl" || it == "archive-2.jsonl" })
    }

    @Test
    fun `ignore et compte les lignes corrompues (écriture interrompue)`() {
        val store = store()
        store.appendLines(listOf(ligne(1)), maxFileSizeBytes = 1_048_576L, maxArchiveFiles = 5)

        // Simule une écriture interrompue d'un précédent lancement (sans
        // retour à la ligne : la ligne suivante s'y agglutine).
        File(dossierTemporaire.root, "current.jsonl").appendText("{\"timestampMillis\":2,\"tr\n")
        store.appendLines(listOf(ligne(3)), maxFileSizeBytes = 1_048_576L, maxArchiveFiles = 5)

        val lues = store.readAll()

        assertEquals(2, lues.size)
        assertEquals(1, store.skippedLines)
    }

    @Test
    fun `efface tous les fichiers de journal`() {
        val store = store()
        store.appendLines(listOf(ligne(1)), maxFileSizeBytes = 1L, maxArchiveFiles = 5)

        store.clear()

        assertTrue(store.readAll().isEmpty())
        assertEquals(0, store.diskStats().fileCount)
    }

    @Test
    fun `la rétention supprime les archives trop vieilles, jamais le courant`() {
        val store = store()
        val maintenant = System.currentTimeMillis()
        val ilYa30Jours = maintenant - 30 * LoggingLimits.MILLIS_PER_DAY

        store.appendLines(listOf(ligne(1)), maxFileSizeBytes = 1_048_576L, maxArchiveFiles = 5)
        // archive-1 : fraîche (rotation immédiate par taille minuscule).
        store.appendLines(listOf(ligne(2)), maxFileSizeBytes = 1L, maxArchiveFiles = 5)
        File(dossierTemporaire.root, "archive-1.jsonl").setLastModified(ilYa30Jours)

        store.sweep(nowMillis = maintenant, retentionDays = 7, maxArchiveFiles = 5)

        val restants =
            dossierTemporaire.root
                .listFiles()
                .orEmpty()
                .map { it.name }
        assertFalse(restants.contains("archive-1.jsonl"))
        assertTrue(restants.contains("current.jsonl"))
    }

    @Test
    fun `la rétention supprime les archives au-delà du plafond d'indice`() {
        val maintenant = System.currentTimeMillis()
        listOf(1, 2, 3, 6, 7).forEach { indice ->
            File(dossierTemporaire.root, "archive-$indice.jsonl").writeText(ligne(indice))
        }

        JsonlLogStore(dossierTemporaire.root, json)
            .sweep(nowMillis = maintenant, retentionDays = 7, maxArchiveFiles = 5)

        val restants =
            dossierTemporaire.root
                .listFiles()
                .orEmpty()
                .map { it.name }
                .filter { it.startsWith("archive-") }
        assertEquals(listOf("archive-1.jsonl", "archive-2.jsonl", "archive-3.jsonl"), restants.sorted())
    }
}
