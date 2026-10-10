package jo.codeide.logcat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.EtatSessionJournal
import jo.codeide.core.domain.FinSessionJournal
import jo.codeide.core.domain.IdentiteSessionJournal
import jo.codeide.core.domain.LigneJournal
import jo.codeide.core.domain.NiveauJournal
import jo.codeide.core.domain.SessionJournal
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tests de l'archive fichier des sessions précédentes (mission « Exécuter »
 * R3, ADR 0103) : aller-retour JSON, borne de 6 entrées, ordre du plus
 * récent, ré-archive dédupliquée, fichier corrompu lu comme vide (c'est un
 * bonus de consultation, jamais une donnée critique).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArchiveSessionsJournalFichiersTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()

    private fun archive() =
        ArchiveSessionsJournalFichiers(
            contexte,
            TestDispatcherProvider(UnconfinedTestDispatcher()),
        )

    private fun session(
        pid: Int,
        raison: String,
        horodatage: Long,
    ): SessionJournal =
        SessionJournal(
            id = "com.exemple.app/$pid",
            identite = IdentiteSessionJournal("com.exemple.app", pid, "com.exemple.app"),
            etat = EtatSessionJournal.TERMINEE,
            fin = FinSessionJournal(raison, horodatage),
            nombreLignes = 12,
            lignesPerdues = 3,
            sortiePrecedente = null,
        )

    private fun derniereLigne(message: String): LigneJournal =
        LigneJournal(
            horodatageMs = 4_500L,
            pid = 4321,
            tid = 4400,
            niveau = NiveauJournal.AVERTISSEMENT,
            etiquette = "Etq",
            message = message,
        )

    @Test
    fun `archiver puis charger conserve identite fin et derniere ligne`() =
        runTest {
            val archive = archive()

            archive.archiver(session(4321, "le processus s'est arrêté", 1_000L), derniereLigne("dernier souffle"))

            val chargees = archive.charger()
            assertEquals(1, chargees.size)
            val archivee = chargees[0]
            assertEquals("com.exemple.app/4321", archivee.session.id)
            assertEquals(4321, archivee.session.identite.pid)
            assertEquals("le processus s'est arrêté", archivee.session.fin?.raison)
            assertEquals(1_000L, archivee.session.fin?.horodatageMs)
            assertEquals(12, archivee.session.nombreLignes)
            assertEquals(3L, archivee.session.lignesPerdues)
            assertEquals("dernier souffle", archivee.derniereLigne?.message)
            assertEquals(NiveauJournal.AVERTISSEMENT, archivee.derniereLigne?.niveau)
        }

    @Test
    fun `archiver sans derniere ligne se relit nullable`() =
        runTest {
            val archive = archive()

            archive.archiver(session(4321, "mort silencieuse", 1_000L), null)

            val chargee = archive.charger().single()
            assertNull(chargee.derniereLigne)
        }

    @Test
    fun `la plus recente d abord et la borne sacrifie les plus anciennes`() =
        runTest {
            val archive = archive()

            for (pid in 1..8) {
                archive.archiver(session(pid, "fin $pid", pid.toLong() * 1_000L), null)
            }

            val chargees = archive.charger()
            assertEquals(6, chargees.size)
            assertEquals("com.exemple.app/8", chargees[0].session.id)
            assertEquals("com.exemple.app/3", chargees[5].session.id)
        }

    @Test
    fun `re archiver le meme identifiant remplace au lieu de dupliquer`() =
        runTest {
            val archive = archive()

            archive.archiver(session(4321, "première fin", 1_000L), null)
            archive.archiver(session(4321, "seconde fin", 5_000L), derniereLigne("après relance"))

            val chargees = archive.charger()
            assertEquals(1, chargees.size)
            assertEquals("seconde fin", chargees[0].session.fin?.raison)
            assertEquals("après relance", chargees[0].derniereLigne?.message)
        }

    @Test
    fun `un fichier corrompu se lit comme vide`() =
        runTest {
            val dossier = File(contexte.filesDir, "logcat")
            dossier.mkdirs()
            File(dossier, "sessions-precedentes.json").writeText("{pas du tout du JSON")

            assertTrue(archive().charger().isEmpty())
        }

    @Test
    fun `sans fichier l archive est vide`() =
        runTest {
            assertTrue(archive().charger().isEmpty())
        }

    @Test
    fun `l ecriture est atomique - aucun residu tmp`() =
        runTest {
            val archive = archive()

            archive.archiver(session(4321, "fin", 1_000L), null)

            val dossier = File(contexte.filesDir, "logcat")
            val residus = dossier.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
            assertTrue(residus.isEmpty())
            assertTrue(File(dossier, "sessions-precedentes.json").isFile)
        }

    @Test
    fun `un niveau inconnu du JSON retombe sur info sans echec`() =
        runTest {
            val archive = archive()
            archive.archiver(session(4321, "fin", 1_000L), derniereLigne("message"))

            // Corrompt SEULEMENT le niveau de la ligne conservée.
            val fichier = File(File(contexte.filesDir, "logcat"), "sessions-precedentes.json")
            fichier.writeText(fichier.readText().replace("\"AVERTISSEMENT\"", "\"NIVEAU_FANTOME\""))

            val chargee = archive.charger().single()
            assertEquals(NiveauJournal.INFO, chargee.derniereLigne?.niveau)
        }
}
