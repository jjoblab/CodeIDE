package jo.codeide.core.domain

import jo.codeide.core.model.getOrNull
import jo.codeide.core.testing.FakeArborescencesSaf
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du décorateur [HistoriqueFileSystem] (mission H1, ADR 0106) :
 * TOUTES les mutations du port FileSystem du projet ouvert produisent
 * des entrées — écritures (contenu AVANT), création, suppression
 * (PIERRE TOMBALE), renommage (ancien nom) — et SEULEMENT elles :
 * hors projet, secrets et artefacts de build ne sont jamais capturés,
 * un échec d'écriture n'enregistre rien, un contenu identique
 * n'enregistre rien.
 *
 * Intégration RÉELLE : `FakeFileSystem` + `FakeArborescencesSaf` + un
 * moteur vrai sur dossier temporaire — la capture se lit dans
 * l'historique PERSISTÉ, pas dans un faux.
 *
 * URI du fake : `content://arbre/document/<id encodé>` ; l'identifiant
 * de la racine est `primary:Projets/Alpha` — les enfants sont
 * `racine/…` (append du fake), l'id décodé suit la même hiérarchie.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoriqueFileSystemTest {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    @get:Rule
    val repartiteur = MainDispatcherRule()

    private val fichiers = FakeFileSystem()
    private val arborescences = FakeArborescencesSaf()
    private val source = SourceProjetHistorique()

    /** Racine du projet amorcée dans le faux. */
    private val racine = "content://arbre/document/primary%3AProjets%2FAlpha"

    private fun systeme(moteur: MoteurHistoriqueLocal): HistoriqueFileSystem {
        fichiers.seedDocument(racine, FakeFileSystem.Document(name = "Alpha", isDirectory = true))
        source.racineDocument = racine
        return HistoriqueFileSystem(
            delegue = fichiers,
            historique = moteur,
            source = source,
            arborescences = arborescences,
        )
    }

    private fun moteur(): MoteurHistoriqueLocal =
        MoteurHistoriqueLocal(
            racine = dossierTemp.newFolder(),
            cleProjetCourante = { source.racineDocument },
            horloge = { 1_000_000L },
            repartiteurs = TestDispatcherProvider(repartiteur.dispatcher),
        )

    @Test
    fun `ecrire un fichier existant enregistre le contenu AVANT`() =
        runTest {
            val moteur = moteur()
            val systeme = systeme(moteur)
            val uri = "$racine/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "v1".toByteArray()),
            )

            val resultat = systeme.writeText(uri, "v2")

            assertTrue(resultat.toString(), resultat is jo.codeide.core.model.AppResult.Success)
            val revisions = moteur.listerRevisions("Main.kt")
            assertEquals(1, revisions.size)
            assertEquals("v1", moteur.lireContenu(revisions.first().id))
            assertEquals(TypeEntreeHistorique.MODIFICATION, revisions.first().type)
        }

    @Test
    fun `creer un fichier puis l ecrire laisse une entree CREATION`() =
        runTest {
            val moteur = moteur()
            val systeme = systeme(moteur)

            val uri = systeme.createFile(racine, "Nouveau.kt", "text/kotlin").getOrNull()
            systeme.writeText(uri!!, "premier contenu")

            val revisions = moteur.listerRevisions("Nouveau.kt")
            assertEquals(TypeEntreeHistorique.CREATION, revisions.last().type)
        }

    @Test
    fun `re ecrire le meme contenu n enregistre RIEN`() =
        runTest {
            val moteur = moteur()
            val systeme = systeme(moteur)
            val uri = "$racine/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "v1".toByteArray()),
            )

            systeme.writeText(uri, "v1")

            assertTrue("contenu identique → aucune entrée (ADR 0105)", moteur.listerRevisions("Main.kt").isEmpty())
        }

    @Test
    fun `supprimer un fichier laisse une pierre tombale RESTAURABLE`() =
        runTest {
            val moteur = moteur()
            val systeme = systeme(moteur)
            val uri = "$racine/Disparu.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(
                    name = "Disparu.kt",
                    isDirectory = false,
                    bytes = "contenu précieux".toByteArray(),
                ),
            )

            assertTrue(systeme.delete(uri) is jo.codeide.core.model.AppResult.Success)

            val revisions = moteur.listerRevisions("Disparu.kt")
            assertEquals(TypeEntreeHistorique.SUPPRESSION, revisions.first().type)
            assertEquals("contenu précieux", moteur.lireContenu(revisions.first().id))
        }

    @Test
    fun `renommer enregistre l ancien nom et deduplique le contenu`() =
        runTest {
            val moteur = moteur()
            val systeme = systeme(moteur)
            val uri = "$racine/Ancien.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Ancien.kt", isDirectory = false, bytes = "contenu".toByteArray()),
            )

            val nouvelleUri = systeme.rename(uri, "Nouveau.kt").getOrNull()

            assertNotNull(nouvelleUri)
            val revisions = moteur.listerRevisions("Nouveau.kt")
            assertEquals(TypeEntreeHistorique.RENOMMAGE, revisions.first().type)
            assertEquals("Ancien.kt", revisions.first().libelle)
            assertEquals("contenu", moteur.lireContenu(revisions.first().id))
        }

    @Test
    fun `hors projet ouvert aucune capture`() =
        runTest {
            val moteur = moteur()
            val systeme = systeme(moteur)
            val uri = "content://arbre/document/primary%3AAutre%2FMain.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "v".toByteArray()),
            )

            systeme.writeText(uri, "v2")

            assertTrue(
                "l'écriture hors projet est déléguée, jamais capturée",
                moteur.listerRevisions("Main.kt").isEmpty(),
            )
        }

    @Test
    fun `les secrets ne sont JAMAIS historises`() =
        runTest {
            val moteur = moteur()
            val systeme = systeme(moteur)
            val secret = "$racine/local.properties"
            fichiers.seedDocument(
                secret,
                FakeFileSystem.Document(
                    name = "local.properties",
                    isDirectory = false,
                    bytes = "sdk.dir=x".toByteArray(),
                ),
            )

            systeme.writeText(secret, "sdk.dir=y")

            assertTrue("secret exclu (ADR 0105)", moteur.listerRevisions("local.properties").isEmpty())
        }

    @Test
    fun `les artefacts de build ne sont pas historises`() =
        runTest {
            val moteur = moteur()
            val systeme = systeme(moteur)
            val artefact = "$racine/app/build/generated/Fichier.kt"
            fichiers.seedDocument(
                artefact,
                FakeFileSystem.Document(name = "Fichier.kt", isDirectory = false, bytes = "gen".toByteArray()),
            )

            systeme.writeText(artefact, "gen2")

            assertTrue("build/ exclu (ADR 0105)", moteur.listerRevisions("app/build/generated/Fichier.kt").isEmpty())
        }

    @Test
    fun `un echec d ecriture n enregistre rien`() =
        runTest {
            val moteur = moteur()
            val systeme = systeme(moteur)
            val uri = "$racine/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "v1".toByteArray()),
            )
            fichiers.writeFailure = java.io.IOException("volume démonté")

            val resultat = systeme.writeText(uri, "v2")

            assertTrue(resultat is jo.codeide.core.model.AppResult.Failure)
            assertTrue("échec → aucune entrée (jamais de fausse révision)", moteur.listerRevisions("Main.kt").isEmpty())
        }
}
