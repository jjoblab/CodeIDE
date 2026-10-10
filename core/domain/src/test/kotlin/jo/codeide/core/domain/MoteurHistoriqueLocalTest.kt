package jo.codeide.core.domain

import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du moteur de l'historique local (mission H1, ADR 0104/0105) :
 * blobs dédupliqués, index atomique, pierres tombales, règle
 * anti-bruit, purge (âge, quota, nombre), intégrité (index corrompu),
 * clés de projet distinctes.
 *
 * Le stockage est RÉEL (dossier temporaire) — l'atomicité et la purge
 * se prouvent sur le disque ; l'horloge est factice (purges par âge
 * déterministes).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MoteurHistoriqueLocalTest {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    /** Horloge factice avançable (purges par âge déterministes). */
    private class HorlogeFactice(
        var maintenant: Long = 1_000_000L,
    ) : TimeProvider {
        override fun nowMillis(): Long = maintenant
    }

    @get:Rule
    val repartiteur = MainDispatcherRule()

    private val horloge = HorlogeFactice()
    private var cleCourante: String? = "content://arbre/document/primary%3AProjets%2FAlpha"

    private fun moteur(
        racine: File = dossierTemp.newFolder(),
        politique: PolitiqueHistorique = PolitiqueHistorique(),
    ): MoteurHistoriqueLocal =
        MoteurHistoriqueLocal(
            racine = racine,
            cleProjetCourante = { cleCourante },
            horloge = horloge,
            repartiteurs = TestDispatcherProvider(repartiteur.dispatcher),
            politique = politique,
        )

    @Test
    fun `enregistrer puis relire le contenu - va et retour`() =
        runTest {
            val moteur = moteur()

            val entree = moteur.enregistrer("app/src/Main.kt", TypeEntreeHistorique.MODIFICATION, "valeur 1")

            assertNotNull(entree)
            assertEquals("valeur 1", moteur.lireContenu(entree!!.id))
        }

    @Test
    fun `les contenus identiques ne sont stockes qu une fois - deduplication`() =
        runTest {
            val racine = dossierTemp.newFolder()
            val moteur = moteur(racine)

            moteur.enregistrer("a.txt", TypeEntreeHistorique.MODIFICATION, "contenu")
            moteur.enregistrer("b.txt", TypeEntreeHistorique.MODIFICATION, "contenu")
            moteur.enregistrer("c.txt", TypeEntreeHistorique.MODIFICATION, "contenu")

            val nombreBlobs = racine.walkTopDown().filter { it.parentFile?.name == "blobs" && it.isFile }.count()
            assertEquals("trois entrées, UN blob (contenu identique)", 1, nombreBlobs)
        }

    @Test
    fun `aucune entree quand le contenu ne change pas`() =
        runTest {
            val moteur = moteur()

            val premiere = moteur.enregistrer("a.txt", TypeEntreeHistorique.MODIFICATION, "contenu")
            val seconde = moteur.enregistrer("a.txt", TypeEntreeHistorique.MODIFICATION, "contenu")

            assertNotNull(premiere)
            assertNull("règle anti-bruit (ADR 0105)", seconde)
            assertEquals(1, moteur.listerRevisions("a.txt").size)
        }

    @Test
    fun `une suppression laisse une pierre tombale lisible`() =
        runTest {
            val moteur = moteur()

            val tombe =
                moteur.enregistrer("a.txt", TypeEntreeHistorique.SUPPRESSION, "dernier contenu")

            assertEquals("dernier contenu", moteur.lireContenu(tombe!!.id))
            val revisions = moteur.listerRevisions("a.txt")
            assertEquals(TypeEntreeHistorique.SUPPRESSION, revisions.first().type)
        }

    @Test
    fun `les revisions sont listees les plus recentes d abord`() =
        runTest {
            val moteur = moteur()
            moteur.enregistrer("a.txt", TypeEntreeHistorique.MODIFICATION, "v1")
            horloge.maintenant += 1_000L
            moteur.enregistrer("a.txt", TypeEntreeHistorique.MODIFICATION, "v2")
            horloge.maintenant += 1_000L
            val derniere = moteur.enregistrer("a.txt", TypeEntreeHistorique.SUPPRESSION, "v3")

            val revisions = moteur.listerRevisions("a.txt")
            assertEquals(3, revisions.size)
            assertEquals(derniere!!.id, revisions.first().id)
        }

    @Test
    fun `les revisions d un dossier couvrent le prefixe strict - src ne couvre pas srcX`() =
        runTest {
            val moteur = moteur()
            moteur.enregistrer("src/Main.kt", TypeEntreeHistorique.MODIFICATION, "v1")
            horloge.maintenant += 1_000L
            moteur.enregistrer("srcX/Autre.kt", TypeEntreeHistorique.MODIFICATION, "v2")
            horloge.maintenant += 1_000L
            moteur.enregistrer("Main.kt", TypeEntreeHistorique.MODIFICATION, "v3")
            horloge.maintenant += 1_000L
            val dernier = moteur.enregistrer("src/com/Detail.kt", TypeEntreeHistorique.CREATION, null)

            val revisions = moteur.listerRevisionsSous("src")
            assertEquals(2, revisions.size)
            assertEquals(dernier!!.id, revisions.first().id)
            assertTrue(revisions.all { it.cheminRelatif.startsWith("src/") })
        }

    @Test
    fun `un chemin vide liste les modifications recentes du projet entier`() =
        runTest {
            val moteur = moteur()
            moteur.enregistrer("a.txt", TypeEntreeHistorique.MODIFICATION, "v1")
            horloge.maintenant += 1_000L
            moteur.enregistrer("src/Main.kt", TypeEntreeHistorique.MODIFICATION, "v2")
            horloge.maintenant += 1_000L
            val dernier = moteur.enregistrer("src/com/Detail.kt", TypeEntreeHistorique.CREATION, null)

            val revisions = moteur.listerRevisionsSous("")
            assertEquals(3, revisions.size)
            assertEquals(dernier!!.id, revisions.first().id)
        }

    @Test
    fun `les revisions d un dossier sont bornees a la limite demandee`() =
        runTest {
            val moteur = moteur()
            repeat(5) { index ->
                moteur.enregistrer("src/Fichier$index.kt", TypeEntreeHistorique.MODIFICATION, "v$index")
                horloge.maintenant += 1_000L
            }

            val limitees = moteur.listerRevisionsSous("src", limite = 2)
            assertEquals(2, limitees.size)
            assertEquals("les PLUS RÉCENTES d'abord (Fichier4 puis Fichier3)", 5L, limitees[0].id)
            assertEquals(4L, limitees[1].id)
        }

    @Test
    fun `hors projet les revisions d un dossier sont vides`() =
        runTest {
            val moteur = moteur()
            moteur.enregistrer("src/Main.kt", TypeEntreeHistorique.MODIFICATION, "v1")
            cleCourante = null

            assertTrue(moteur.listerRevisionsSous("src").isEmpty())
        }

    @Test
    fun `au dela de la taille maximale l entree existe sans contenu`() =
        runTest {
            val moteur = moteur(politique = PolitiqueHistorique(tailleMaxFichierOctets = 5))
            val gros = "x".repeat(50)

            val entree = moteur.enregistrer("gros.txt", TypeEntreeHistorique.MODIFICATION, gros)

            assertNotNull(entree)
            assertNull("contenu indisponible, jamais inventé (ADR 0105)", entree!!.empreinte)
            assertNull(moteur.lireContenu(entree.id))
        }

    @Test
    fun `la purge supprime les entrees plus vieilles que la retention`() =
        runTest {
            val moteur = moteur(politique = PolitiqueHistorique(joursRetention = 2))
            moteur.enregistrer("a.txt", TypeEntreeHistorique.MODIFICATION, "v1")
            horloge.maintenant += 3L * 24L * 60L * 60L * 1000L
            val recente = moteur.enregistrer("b.txt", TypeEntreeHistorique.MODIFICATION, "v2")!!

            moteur.purger()

            assertTrue("la vieille entrée est purgée", moteur.listerRevisions("a.txt").isEmpty())
            assertEquals(1, moteur.listerRevisions("b.txt").size)
            assertEquals(recente.id, moteur.listerRevisions("b.txt").first().id)
        }

    @Test
    fun `la purge respecte le quota en sacrifiant les plus anciennes`() =
        runTest {
            val moteur = moteur(politique = PolitiqueHistorique(quotaOctets = 45))
            moteur.enregistrer("a.txt", TypeEntreeHistorique.MODIFICATION, "x".repeat(20))
            moteur.enregistrer("b.txt", TypeEntreeHistorique.MODIFICATION, "y".repeat(20))
            moteur.enregistrer("c.txt", TypeEntreeHistorique.MODIFICATION, "z".repeat(20))

            moteur.purger()

            // 60 octets de blobs pour un quota de 45 : la plus ancienne
            // (a.txt) est sacrifiée — les deux récentes restent.
            assertTrue("la plus ancienne part d'abord", moteur.listerRevisions("a.txt").isEmpty())
            assertEquals(1, moteur.listerRevisions("b.txt").size)
            assertEquals(1, moteur.listerRevisions("c.txt").size)
        }

    @Test
    fun `la purge borne le nombre d entrees`() =
        runTest {
            val moteur = moteur(politique = PolitiqueHistorique(nbMaxEntrees = 2))
            repeat(5) { indice ->
                horloge.maintenant += 1_000L
                moteur.enregistrer("f$indice.txt", TypeEntreeHistorique.MODIFICATION, "v$indice")
            }

            moteur.purger()

            val total =
                listOf("f0", "f1", "f2", "f3", "f4").sumOf { moteur.listerRevisions("$it.txt").size }
            assertEquals(2, total)
        }

    @Test
    fun `la purge supprime les blobs orphelins`() =
        runTest {
            val racine = dossierTemp.newFolder()
            val moteur =
                MoteurHistoriqueLocal(
                    racine = racine,
                    cleProjetCourante = { cleCourante },
                    horloge = horloge,
                    repartiteurs = TestDispatcherProvider(repartiteur.dispatcher),
                    politique = PolitiqueHistorique(joursRetention = 1),
                )
            moteur.enregistrer("a.txt", TypeEntreeHistorique.MODIFICATION, "v1")
            horloge.maintenant += 2L * 24L * 60L * 60L * 1000L

            moteur.purger()

            val nombreFichiers =
                racine
                    .walkTopDown()
                    .filter { it.isFile }
                    .filter { it.name != "index.json" }
                    .count()
            assertEquals("blob orphelin supprimé", 0, nombreFichiers)
        }

    @Test
    fun `un index corrompu repart d un historique vide - jamais de fausses donnees`() =
        runTest {
            val racine = dossierTemp.newFolder()
            val moteur =
                MoteurHistoriqueLocal(
                    racine = racine,
                    cleProjetCourante = { cleCourante },
                    horloge = horloge,
                    repartiteurs = TestDispatcherProvider(repartiteur.dispatcher),
                )
            moteur.enregistrer("a.txt", TypeEntreeHistorique.MODIFICATION, "v1")
            // Corrompt l'index sous le moteur (simule une écriture
            // interrompue : le rename atomique a protégé la V1, ici on
            // sabote volontairement).
            racine.walkTopDown().filter { it.name == "index.json" }.forEach { it.writeText("{{{ pas du json") }

            val relu =
                MoteurHistoriqueLocal(
                    racine = racine,
                    cleProjetCourante = { cleCourante },
                    horloge = horloge,
                    repartiteurs = TestDispatcherProvider(repartiteur.dispatcher),
                )

            assertTrue("index corrompu → historique vide (ADR 0104)", relu.listerRevisions("a.txt").isEmpty())
            val nouvelle = relu.enregistrer("a.txt", TypeEntreeHistorique.MODIFICATION, "v2")
            assertNotNull("le moteur repart et re-enregistre", nouvelle)
        }

    @Test
    fun `deux projets possedent des historiques separes`() =
        runTest {
            val racine = dossierTemp.newFolder()
            val moteurA =
                MoteurHistoriqueLocal(
                    racine = racine,
                    cleProjetCourante = { "content://arbre/document/primary%3AA" },
                    horloge = horloge,
                    repartiteurs = TestDispatcherProvider(repartiteur.dispatcher),
                )
            val moteurB =
                MoteurHistoriqueLocal(
                    racine = racine,
                    cleProjetCourante = { "content://arbre/document/primary%3AB" },
                    horloge = horloge,
                    repartiteurs = TestDispatcherProvider(repartiteur.dispatcher),
                )

            moteurA.enregistrer("Main.kt", TypeEntreeHistorique.MODIFICATION, "projet A")
            moteurB.enregistrer("Main.kt", TypeEntreeHistorique.MODIFICATION, "projet B")

            assertEquals("projet A", moteurA.lireContenu(moteurA.listerRevisions("Main.kt").first().id))
            assertEquals("projet B", moteurB.lireContenu(moteurB.listerRevisions("Main.kt").first().id))
        }

    @Test
    fun `hors projet aucune capture ni lecture`() =
        runTest {
            cleCourante = null
            val moteur = moteur()

            assertNull(moteur.enregistrer("a.txt", TypeEntreeHistorique.MODIFICATION, "v1"))
            assertTrue(moteur.listerRevisions("a.txt").isEmpty())
            assertNull(moteur.derniereEmpreinte("a.txt"))
        }

    @Test
    fun `etiqueter pose une entree nommee sans contenu`() =
        runTest {
            val moteur = moteur()

            val etiquette = moteur.etiqueter("Avant compilation")

            assertEquals("Avant compilation", etiquette!!.libelle)
            assertEquals(TypeEntreeHistorique.ETIQUETTE, etiquette.type)
            assertNull(etiquette.empreinte)
        }
}
