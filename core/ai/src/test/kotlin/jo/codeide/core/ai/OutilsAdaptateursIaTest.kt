package jo.codeide.core.ai

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Adaptateurs d'outils V1 (IA-7b) : les méthodes de lecture/écriture rendues appelables par le modèle. */
class OutilsAdaptateursIaTest {
    @get:Rule
    val dossier: TemporaryFolder = TemporaryFolder()

    private class FauxPort : FournisseurContexteProjet {
        var chemins: List<String> = emptyList()
        var contenu: String? = null
        var echec: Boolean = false

        override suspend fun lireFichier(cheminRelatif: String): AppResult<String> = if (echec || contenu == null) {
            AppResult.Failure(AppError.Unknown("test"))
        } else {
            AppResult.Success(contenu!!)
        }

        override suspend fun listerChemins(): AppResult<List<String>> = if (echec) {
            AppResult.Failure(AppError.Unknown("test"))
        } else {
            AppResult.Success(chemins)
        }

        override suspend fun lireIaIgnore(): List<String> = emptyList()
    }

    @Test
    fun `l'outil list_files liste les chemins du port`() = runTest {
        val port = FauxPort()
        port.chemins = listOf("src/App.kt", "README.md")
        val resultat = OutilListeFichiersIa(port).executer(emptyMap())
        assertEquals("src/App.kt\nREADME.md", (resultat as ResultatOutilIa.Succes).sortie)
    }

    @Test
    fun `l'outil list_files refuse proprement si le port échoue`() = runTest {
        val port = FauxPort()
        port.echec = true
        val resultat = OutilListeFichiersIa(port).executer(emptyMap())
        assertTrue(resultat is ResultatOutilIa.Refuse)
    }

    @Test
    fun `l'outil read_file lit le contenu borné du port`() = runTest {
        val port = FauxPort()
        port.contenu = "bonjour"
        val resultat = OutilLireFichierIa(port).executer(mapOf("chemin" to "src/App.kt"))
        assertEquals("bonjour", (resultat as ResultatOutilIa.Succes).sortie)
    }

    @Test
    fun `l'outil create_file pousse une proposition dans le ChangeSet sans écrire`() = runTest {
        dossier.newFile("nouveau.kt") // existe déjà : la création est refusée
        val resolveur = ResolveurCheminsProjetIa(dossier.root, ExclusionsIa())
        val changeSet = ChangeSetIaCourant()
        val outil = OutilProposerCreationIa(OutilsEcritureIa(resolveur, changeSet))
        val resultat =
            outil.executer(
                mapOf("chemin" to "nouveau.kt", "contenu" to "x", "raison" to "test"),
            )
        assertTrue(resultat is ResultatOutilIa.Refuse) // le fichier existe déjà
    }

    @Test
    fun `l'outil propose_edit pousse une modification empreinte dans le ChangeSet`() = runTest {
        val fichier: File = dossier.newFile("App.kt")
        fichier.writeText("avant")
        val resolveur = ResolveurCheminsProjetIa(dossier.root, ExclusionsIa())
        val changeSet = ChangeSetIaCourant()
        val outil = OutilProposerModificationIa(OutilsEcritureIa(resolveur, changeSet))
        val resultat =
            outil.executer(
                mapOf("chemin" to "App.kt", "contenu" to "après", "raison" to "test"),
            )
        assertTrue((resultat as ResultatOutilIa.Succes).sortie.contains("modification proposée"))
        assertEquals(1, changeSet.depot.changements.size)
        assertEquals(TypeChangementIa.MODIFIER, changeSet.depot.changements[0].type)
        // Le disque n'a PAS bougé (écriture mise en attente).
        assertEquals("avant", fichier.readText())
    }

    @Test
    fun `l'outil delete_file pousse une suppression avec empreinte`() = runTest {
        val fichier: File = dossier.newFile("victime.kt")
        fichier.writeText("x")
        val resolveur = ResolveurCheminsProjetIa(dossier.root, ExclusionsIa())
        val changeSet = ChangeSetIaCourant()
        val outil = OutilProposerSuppressionIa(OutilsEcritureIa(resolveur, changeSet))
        val resultat =
            outil.executer(mapOf("chemin" to "victime.kt", "raison" to "test"))
        assertTrue((resultat as ResultatOutilIa.Succes).sortie.contains("suppression proposée"))
        assertEquals(TypeChangementIa.SUPPRIMER, changeSet.depot.changements[0].type)
        assertTrue(fichier.exists()) // toujours là jusqu'à la revue
    }
}
