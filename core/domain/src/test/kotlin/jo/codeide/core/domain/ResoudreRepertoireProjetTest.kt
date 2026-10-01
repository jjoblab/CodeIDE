package jo.codeide.core.domain

import jo.codeide.core.testing.FakeArborescencesSaf
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
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
}
