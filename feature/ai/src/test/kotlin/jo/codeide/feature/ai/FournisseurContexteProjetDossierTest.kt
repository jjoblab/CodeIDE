package jo.codeide.feature.ai

import jo.codeide.core.domain.RegistreDossierProjetIa
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests des NOUVELLES méthodes du lecteur de projet réel (rapport IA,
 * chantier IA-CODEIDE, lot 2, A5) : `rechercherTexte` (recherche
 * littérale bornée, dossiers lourds écartés) et `lireLignes` (intervalle
 * 1-based inclus, numéroté, replié au plafond, plage hors fichier
 * refusée honnêtement).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FournisseurContexteProjetDossierTest {
    @get:Rule
    val dossier: TemporaryFolder = TemporaryFolder()

    /** Lecteur adossé à un dossier temporaire publié au registre (même horloge virtuelle que le test). */
    private fun fournisseur(racine: File, horloge: TestCoroutineScheduler): FournisseurContexteProjetDossier {
        val registre = RegistreDossierProjetIa()
        registre.publier(racine.absolutePath)
        return FournisseurContexteProjetDossier(
            registreDossier = registre,
            repartiteurs = TestDispatcherProvider(StandardTestDispatcher(horloge)),
        )
    }

    @Test
    fun `rechercherTexte trouve les occurrences avec chemin et ligne, hors build`() = runTest {
        val racine = dossier.newFolder("projet")
        File(racine, "src").mkdirs()
        File(racine, "src/App.kt").writeText("val a = 1\nval resultat = calculer()\n")
        File(racine, "src/Core.kt").writeText("fun calculer() = 2\n")
        File(racine, "build").mkdirs()
        File(racine, "build/Genere.kt").writeText("val resultat = buildGenere()\n")

        val resultat = fournisseur(racine, testScheduler).rechercherTexte("resultat", limite = 10)

        assertTrue(resultat is jo.codeide.core.model.AppResult.Success)
        val occurrences = (resultat as jo.codeide.core.model.AppResult.Success).value
        assertEquals(1, occurrences.size)
        assertEquals("src/App.kt", occurrences.single().cheminRelatif)
        assertEquals(2, occurrences.single().ligne)
        assertTrue(occurrences.single().extrait.contains("calculer()"))
    }

    @Test
    fun `rechercherTexte borne les occurrences a la limite demandee`() = runTest {
        val racine = dossier.newFolder("projet")
        File(racine, "Gros.kt").writeText((1..50).joinToString("\n") { "ligne $it porteuse" })

        val resultat = fournisseur(racine, testScheduler).rechercherTexte("porteuse", limite = 5)

        val occurrences = (resultat as jo.codeide.core.model.AppResult.Success).value
        assertEquals(5, occurrences.size)
    }

    @Test
    fun `lireLignes rend l'intervalle numerote et replie la fin hors fichier`() = runTest {
        val racine = dossier.newFolder("projet")
        File(racine, "src").mkdirs()
        File(racine, "src/App.kt").writeText("un\ndeux\ntrois\nquatre\n")

        val lecteur = fournisseur(racine, testScheduler)

        val tranche =
            (lecteur.lireLignes("src/App.kt", debut = 2, fin = 3) as jo.codeide.core.model.AppResult.Success).value
        assertEquals("2| deux\n3| trois", tranche)

        // La fin au-delà du fichier est REPLIÉE à la dernière ligne.
        val repliee =
            (lecteur.lireLignes("src/App.kt", debut = 3, fin = 99) as jo.codeide.core.model.AppResult.Success).value
        assertEquals("3| trois\n4| quatre", repliee)

        // Un début au-delà de la fin est refusé honnêtement.
        assertTrue(lecteur.lireLignes("src/App.kt", debut = 9, fin = 10) is jo.codeide.core.model.AppResult.Failure)
    }

    @Test
    fun `lireLignes refuse le fichier absent et le chemin hors projet`() = runTest {
        val racine = dossier.newFolder("projet")
        val lecteur = fournisseur(racine, testScheduler)

        assertTrue(lecteur.lireLignes("absent.kt", 1, 5) is jo.codeide.core.model.AppResult.Failure)
        assertTrue(lecteur.lireLignes("../hors.kt", 1, 5) is jo.codeide.core.model.AppResult.Failure)
    }

    @Test
    fun `sans projet ouvert les deux methodes echouent honnetement`() = runTest {
        val lecteur =
            FournisseurContexteProjetDossier(
                registreDossier = RegistreDossierProjetIa(),
                repartiteurs = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            )
        assertTrue(lecteur.rechercherTexte("x", 5) is jo.codeide.core.model.AppResult.Failure)
        assertTrue(lecteur.lireLignes("a.kt", 1, 5) is jo.codeide.core.model.AppResult.Failure)
    }
}
