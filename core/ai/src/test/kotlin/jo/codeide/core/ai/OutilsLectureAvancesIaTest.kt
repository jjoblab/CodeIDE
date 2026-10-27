package jo.codeide.core.ai

import jo.codeide.core.domain.DiagnosticEditeur
import jo.codeide.core.domain.GraviteDiagnostic
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des NOUVEAUX OUTILS DE LECTURE (rapport IA, chantier IA-CODEIDE,
 * lot 2, A5) : `search_text` (recherche littérale bornée), `read_lines`
 * (intervalle de lignes) et `diagnostics_lsp` (problèmes du fichier
 * ACTIF) — LECTURE, aucune approbation, confinement inchangé.
 */
class OutilsLectureAvancesIaTest {
    /** Port pilotable : occurrences et lignes semées par le test. */
    private class PortPilote : FournisseurContexteProjet {
        var occurrences: List<OccurrenceRechercheIa> = emptyList()
        var lignes: String = ""
        var echec = false

        override suspend fun lireFichier(cheminRelatif: String): AppResult<String> =
            AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "test"))

        override suspend fun listerChemins(): AppResult<List<String>> = AppResult.Success(emptyList())

        override suspend fun lireIaIgnore(): List<String> = emptyList()

        override suspend fun rechercherTexte(motif: String, limite: Int): AppResult<List<OccurrenceRechercheIa>> =
            if (echec) {
                AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "test"))
            } else {
                AppResult.Success(occurrences.take(limite))
            }

        override suspend fun lireLignes(cheminRelatif: String, debut: Int, fin: Int): AppResult<String> = if (echec) {
            AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, "test"))
        } else {
            AppResult.Success(lignes)
        }
    }

    // ------------------------------------------------------------------
    // search_text : recherche littérale bornée.
    // ------------------------------------------------------------------

    @Test
    fun `search_text rend les occurrences avec chemin, ligne et extrait`() = runTest {
        val port = PortPilote()
        port.occurrences =
            listOf(
                OccurrenceRechercheIa("src/App.kt", 12, "val resultat = calculer()"),
                OccurrenceRechercheIa("src/Core.kt", 3, "fun calculer() = 1"),
            )
        val resultat = OutilRechercherTexteIa(port).executer(mapOf("motif" to "calculer"))
        val succes = resultat as ResultatOutilIa.Succes
        assertTrue(succes.sortie.contains("src/App.kt:12: val resultat = calculer()"))
        assertTrue(succes.sortie.contains("src/Core.kt:3: fun calculer() = 1"))
        assertEquals(false, succes.compteEchec)
    }

    @Test
    fun `search_text sans occurrence le dit honnetement`() = runTest {
        val resultat = OutilRechercherTexteIa(PortPilote()).executer(mapOf("motif" to "introuvable"))
        assertEquals("aucune occurrence de « introuvable »", (resultat as ResultatOutilIa.Succes).sortie)
    }

    @Test
    fun `search_text refuse le motif vide et le port en echec`() = runTest {
        val vide = OutilRechercherTexteIa(PortPilote()).executer(mapOf("motif" to "  "))
        assertTrue(vide is ResultatOutilIa.Refuse)

        val port = PortPilote()
        port.echec = true
        val echoue = OutilRechercherTexteIa(port).executer(mapOf("motif" to "x"))
        assertTrue(echoue is ResultatOutilIa.Refuse)
    }

    // ------------------------------------------------------------------
    // read_lines : intervalle de lignes 1-based inclus.
    // ------------------------------------------------------------------

    @Test
    fun `read_lines rend l'intervalle numerote du port`() = runTest {
        val port = PortPilote()
        port.lignes = "10| val a = 1\n11| val b = 2"
        val resultat = OutilLireLignesIa(port).executer(mapOf("chemin" to "a.kt", "debut" to "10", "fin" to "11"))
        assertEquals(port.lignes, (resultat as ResultatOutilIa.Succes).sortie)
    }

    @Test
    fun `read_lines refuse les bornes invalides et le port en echec`() = runTest {
        val outil = OutilLireLignesIa(PortPilote())
        assertTrue(outil.executer(mapOf("chemin" to "a.kt", "debut" to "0", "fin" to "5")) is ResultatOutilIa.Refuse)
        assertTrue(outil.executer(mapOf("chemin" to "a.kt", "debut" to "5", "fin" to "2")) is ResultatOutilIa.Refuse)
        assertTrue(outil.executer(mapOf("chemin" to "a.kt", "debut" to "x", "fin" to "5")) is ResultatOutilIa.Refuse)
        assertTrue(outil.executer(mapOf("debut" to "1", "fin" to "5")) is ResultatOutilIa.Refuse)

        val port = PortPilote()
        port.echec = true
        assertTrue(
            OutilLireLignesIa(port).executer(mapOf("chemin" to "a.kt", "debut" to "1", "fin" to "5"))
                is ResultatOutilIa.Refuse,
        )
    }

    // ------------------------------------------------------------------
    // diagnostics_lsp : problèmes du fichier ACTIF.
    // ------------------------------------------------------------------

    @Test
    fun `diagnostics_lsp liste les diagnostics du fichier actif`() = runTest {
        val source =
            SourceDiagnosticsFichierIa {
                DiagnosticsFichierIa(
                    cheminActif = "src/App.kt",
                    diagnostics =
                    listOf(
                        DiagnosticEditeur(
                            identifiant = "e1",
                            gravite = GraviteDiagnostic.ERREUR,
                            cheminRelatif = "src/App.kt",
                            ligne = 4,
                            colonne = 2,
                            message = "unresolved reference: foo",
                        ),
                    ),
                )
            }
        val resultat = OutilDiagnosticsFichierIa(source).executer(emptyMap())
        val succes = resultat as ResultatOutilIa.Succes
        assertTrue(succes.sortie.contains("ERREUR src/App.kt:4:2"))
        assertTrue(succes.sortie.contains("unresolved reference: foo"))
    }

    @Test
    fun `diagnostics_lsp sans diagnostics le dit, sans editeur il refuse`() = runTest {
        val vide =
            OutilDiagnosticsFichierIa { DiagnosticsFichierIa(cheminActif = "a.kt") }.executer(emptyMap())
        assertEquals("aucun diagnostic pour a.kt", (vide as ResultatOutilIa.Succes).sortie)

        val sansEditeur = OutilDiagnosticsFichierIa { DiagnosticsFichierIa(cheminActif = null) }
        assertTrue(sansEditeur.executer(emptyMap()) is ResultatOutilIa.Refuse)
    }

    @Test
    fun `diagnostics_lsp sur un fichier NON actif refuse avec le message honnete`() = runTest {
        val source = SourceDiagnosticsFichierIa { DiagnosticsFichierIa(cheminActif = "src/App.kt") }
        val resultat = OutilDiagnosticsFichierIa(source).executer(mapOf("chemin" to "src/Autre.kt"))
        val refuse = resultat as ResultatOutilIa.Refuse
        assertTrue(refuse.motif.contains("uniquement pour le fichier ouvert : src/App.kt"))
        assertTrue(refuse.motif.contains("src/Autre.kt"))
    }
}
