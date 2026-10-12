package jo.codeide.core.domain

import jo.codeide.core.testing.FakeArborescencesSaf
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Tests du pont « arborescence SAF → chemin FUSE » (Terminal T6, critère
 * d'acceptation : le tiroir propose le dossier **réel** du projet au
 * terminal sans jamais dépendre d'une API Android).
 *
 * Les heuristiques sont des fonctions pures rejouées en JVM — même
 * discipline que `core:bootstrap` (T1) : chaque cas de la cartographie
 * `ExternalStorageProvider` et du durcissement anti-traversée est
 * éprouvé isolément.
 *
 * V0.43.0 : le cas d'usage reçoit l'URI de **document** du projet
 * (`StorageLocation.documentUri`) — les tests du niveau cas d'usage
 * suivent, avec la régression centrale du bug : une URI d'ARBRE (sans
 * segment `document`) doit être rejetée, pas résolue vers la racine de
 * l'arbre (le dossier PARENT d'un projet créé dans le dossier de travail).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ResoudreRepertoireProjetTest {
    @get:Rule
    val repartiteur = MainDispatcherRule()

    private val arborescences = FakeArborescencesSaf()

    private val casUsage =
        ResoudreRepertoireProjet(
            arborescences = arborescences,
            repartiteurs = TestDispatcherProvider(repartiteur.dispatcher),
        )

    // ------------------------------------------------------------------
    // Fonction pure : cartographie des volumes.
    // ------------------------------------------------------------------

    @Test
    fun `volume principal avec chemin relatif`() {
        assertEquals(
            "/storage/emulated/0/CodeIDE/MonProjet",
            cheminFuseDepuisIdDocument("primary:CodeIDE/MonProjet", RACINES_FUSE_PAR_DEFAUT),
        )
    }

    @Test
    fun `volume principal à sa racine`() {
        assertEquals(
            "/storage/emulated/0",
            cheminFuseDepuisIdDocument("primary:", RACINES_FUSE_PAR_DEFAUT),
        )
    }

    @Test
    fun `identifiant sans séparateur rejeté`() {
        assertNull(cheminFuseDepuisIdDocument("primary", RACINES_FUSE_PAR_DEFAUT))
    }

    @Test
    fun `volume amovible par UUID`() {
        assertEquals(
            "/storage/1A2B-3C4D/Projets",
            cheminFuseDepuisIdDocument("1A2B-3C4D:Projets", RACINES_FUSE_PAR_DEFAUT),
        )
    }

    @Test
    fun `racines personnalisées priment sur la cartographie par défaut`() {
        assertEquals(
            "/mnt/essai/Projet",
            cheminFuseDepuisIdDocument("essai:Projet", mapOf("essai" to "/mnt/essai")),
        )
    }

    @Test
    fun `volume inconnu rejeté`() {
        assertNull(cheminFuseDepuisIdDocument("foo:bar", RACINES_FUSE_PAR_DEFAUT))
    }

    @Test
    fun `volume UUID malformé rejeté`() {
        assertNull(cheminFuseDepuisIdDocument("1A2B3C4D:Projets", RACINES_FUSE_PAR_DEFAUT))
        assertNull(cheminFuseDepuisIdDocument("1A2B-3C4:Projets", RACINES_FUSE_PAR_DEFAUT))
    }

    // ------------------------------------------------------------------
    // Fonction pure : durcissement anti-traversée.
    // ------------------------------------------------------------------

    @Test
    fun `segment point-point rejeté`() {
        assertNull(cheminFuseDepuisIdDocument("primary:CodeIDE/../../etc", RACINES_FUSE_PAR_DEFAUT))
    }

    @Test
    fun `segment point rejeté`() {
        assertNull(cheminFuseDepuisIdDocument("primary:./CodeIDE", RACINES_FUSE_PAR_DEFAUT))
    }

    @Test
    fun `segment vide rejeté`() {
        assertNull(cheminFuseDepuisIdDocument("primary:CodeIDE//MonProjet", RACINES_FUSE_PAR_DEFAUT))
    }

    @Test
    fun `volume vide rejeté`() {
        assertNull(cheminFuseDepuisIdDocument(":CodeIDE", RACINES_FUSE_PAR_DEFAUT))
    }

    @Test
    fun `espaces et accents passent tels quels`() {
        assertEquals(
            "/storage/emulated/0/Mes projets/Café",
            cheminFuseDepuisIdDocument("primary:Mes projets/Café", RACINES_FUSE_PAR_DEFAUT),
        )
    }

    // ------------------------------------------------------------------
    // Cas d'usage : branches illisibles et répertoire fantôme.
    // ------------------------------------------------------------------

    @Test
    fun `URI de document illisible donne null`() =
        runTest {
            assertNull(casUsage("content://provider.inconnu/autre/segment"))
        }

    @Test
    fun `regression v0 43 0 - une URI d arbre sans segment document est rejetee`() =
        runTest {
            // LE bug : l'ancien contrat résolvait l'URI d'ARBRE de la
            // permission — pour un projet créé dans le dossier de travail,
            // l'arbre est le PARENT, et le terminal/build s'ouvraient dans
            // `/storage/emulated/0/CodeIDEProjects` au lieu du projet.
            // Désormais : null (jamais de racine d'arbre substituée).
            assertNull(
                casUsage(
                    "content://com.android.externalstorage.documents/tree/" +
                        "primary%3ACodeIDEProjects",
                ),
            )
        }

    @Test
    fun `regression v0 43 0 - le faux refuse aussi l arbre pur`() {
        assertNull(
            arborescences.idDocumentDeUriDocument(
                "content://com.android.externalstorage.documents/tree/primary%3ACodeIDEProjects",
            ),
        )
    }

    @Test
    fun `une URI de document imbriquee rend l identifiant complet du projet`() {
        // Projet créé dans le dossier de travail : documentUri imbriqué
        // dans l'arbre parent — l'identifiant couvre TOUT le chemin.
        assertEquals(
            "primary:CodeIDEProjects/MonApp",
            arborescences.idDocumentDeUriDocument(
                "content://com.android.externalstorage.documents/tree/" +
                    "primary%3ACodeIDEProjects/document/primary%3ACodeIDEProjects%2FMonApp",
            ),
        )
    }

    @Test
    fun `l identifiant de document alimente la resolution complete`() {
        assertEquals(
            "/storage/emulated/0/CodeIDEProjects/MonApp",
            cheminFuseDepuisIdDocument(
                arborescences.idDocumentDeUriDocument(
                    "content://com.android.externalstorage.documents/tree/" +
                        "primary%3ACodeIDEProjects/document/primary%3ACodeIDEProjects%2FMonApp",
                )!!,
                RACINES_FUSE_PAR_DEFAUT,
            ),
        )
    }

    @Test
    fun `volume inconnu donne null meme via le cas d usage`() =
        runTest {
            assertNull(
                casUsage(
                    "content://com.android.externalstorage.documents/tree/foo%3Abar/document/foo%3Abar",
                ),
            )
        }

    @Test
    fun `chemin resolu mais absent du systeme de fichiers donne null`() =
        runTest {
            // Le garde « répertoire fantôme » : jamais de session ouverte
            // dans un dossier qui n'existe pas (volume démonté). En JVM,
            // /storage n'existe pas — le rejet est donc observable ici.
            assertNull(
                casUsage(
                    "content://com.android.externalstorage.documents/tree/" +
                        "primary%3ACodeIDE/document/primary%3ACodeIDE%2FDossierFantome",
                ),
            )
        }

    // ------------------------------------------------------------------
    // V0.100.3 : contrats fichier ([ResolveurCheminFuse.resoudreEntree])
    // vs répertoire ([ResolveurCheminFuse.invoke]) — régression du retour
    // d'appareil v0.100.2 (§16.2.3). Un dossier temporaire JVM joue le
    // volume monté ; la garde est une lambda, comme dans l'habillage du
    // cas d'usage.
    // ------------------------------------------------------------------

    @Test
    fun `regression v0 100 2 - un fichier existant est resolu par le contrat d entree`() {
        val volume = createTempDirectory("fuse-entree").toFile()
        val fichier =
            volume.resolve("MonApp/MainActivity.java").apply {
                parentFile.mkdirs()
                createNewFile()
            }
        assertEquals(
            fichier.absolutePath,
            cheminFuseVerifie(
                "essai:MonApp/MainActivity.java",
                mapOf("essai" to volume.absolutePath),
            ) { entree -> entree.exists() },
        )
    }

    @Test
    fun `regression v0 100 2 - le contrat repertoire rejette le fichier du branchement LSP`() {
        // LE bug : le branchement LspEditor passait l'URI du `.java` par
        // l'opérateur du pont (garde isDirectory) → `null` → renoncement
        // SILENCIEUX, jamais « LSP Java connecté » (journal d'appareil
        // « ouverte → nourrie », zéro complétion Java, zéro diagnostic).
        val volume = createTempDirectory("fuse-dossier").toFile()
        volume.resolve("MonApp/MainActivity.java").apply {
            parentFile.mkdirs()
            createNewFile()
        }
        assertNull(
            cheminFuseVerifie(
                "essai:MonApp/MainActivity.java",
                mapOf("essai" to volume.absolutePath),
            ) { entree -> entree.isDirectory },
        )
    }

    @Test
    fun `un dossier reste resolu par le contrat d entree`() {
        // La symétrie : resoudreEntree accepte AUSSI les dossiers (la
        // racine projet y passera) — l'existence, pas la nature.
        val volume = createTempDirectory("fuse-symetrie").toFile()
        volume.resolve("MonApp").mkdirs()
        assertEquals(
            volume.resolve("MonApp").absolutePath,
            cheminFuseVerifie(
                "essai:MonApp",
                mapOf("essai" to volume.absolutePath),
            ) { entree -> entree.exists() },
        )
    }

    @Test
    fun `une entree absente est rejetee par la garde d existence`() {
        val volume = createTempDirectory("fuse-fantome").toFile()
        assertNull(
            cheminFuseVerifie(
                "essai:MonApp/Fantome.java",
                mapOf("essai" to volume.absolutePath),
            ) { entree -> entree.exists() },
        )
    }
}
