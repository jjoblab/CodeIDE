package jo.codeide.core.ai

import jo.codeide.core.domain.DiagnosticEditeur
import jo.codeide.core.domain.GraviteDiagnostic
import jo.codeide.core.domain.SelectionEditeur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'assembleur de contexte (mission IA, lot IA-4, ADR 0124 §4) :
 * critères d'acceptation — mention `.env`/`*.jks` refusée avec message,
 * clé factice → alerte, AUCUN chemin absolu dans le texte, les puces
 * reflètent EXACTEMENT ce qui part.
 */
class AssemblerContexteTest {
    private val assembleur = AssemblerContexte()

    @Test
    fun `une mention de fichier env est refusée avec raison — jamais son contenu`() {
        val contexte =
            assembleur.assembler(
                DonneesContexte(
                    mentions =
                        listOf(
                            MentionFichier(".env", "CLE_SUPER_SECRETE=sk-aaaaaaaaaaaaaaaaaaaaaa"),
                            MentionFichier("app/src/main/MainActivity.kt", "class MainActivity"),
                        ),
                ),
                ExclusionsIa(),
            )

        assertEquals(listOf(CheminRefuse(".env", AssemblerContexte.RAISON_EXCLUSION)), contexte.refuses)
        assertFalse(contexte.texte.contains("CLE_SUPER_SECRETE"))
        assertFalse(contexte.texte.contains(".env"))
        assertTrue(contexte.texte.contains("<fichier chemin=\"app/src/main/MainActivity.kt\">"))
    }

    @Test
    fun `une mention de keystore jks est refusée aussi`() {
        val contexte =
            assembleur.assembler(
                DonneesContexte(mentions = listOf(MentionFichier("app/release.jks", "binaire"))),
                ExclusionsIa(),
            )

        assertEquals(listOf(CheminRefuse("app/release.jks", AssemblerContexte.RAISON_EXCLUSION)), contexte.refuses)
        assertEquals(emptyList<PieceContexte>(), contexte.pieces)
    }

    @Test
    fun `ia-ignore étend la liste d'exclusion`() {
        val contexte =
            assembleur.assembler(
                DonneesContexte(mentions = listOf(MentionFichier("docs/interne.md", "notes"))),
                ExclusionsIa(listOf("docs/")),
            )

        assertEquals(listOf(CheminRefuse("docs/interne.md", AssemblerContexte.RAISON_EXCLUSION)), contexte.refuses)
    }

    @Test
    fun `un contexte avec une clé factice déclenche l'alerte — étiquette sans valeur`() {
        val factice = "sk-factice1234567890abcdef"
        val contexte =
            assembleur.assembler(
                DonneesContexte(
                    cheminFichierActif = "app/src/Config.kt",
                    contenuFichierActif = "val cle = \"$factice\"",
                ),
                ExclusionsIa(),
            )

        assertEquals(listOf(AlerteSecret("clé sk-", "app/src/Config.kt")), contexte.alertes)
        assertFalse(contexte.alertes.any { alerte -> alerte.motif.contains(factice) })
    }

    @Test
    fun `aucun chemin absolu de l'appareil ne peut entrer — un absolu est refusé`() {
        val contexte =
            assembleur.assembler(
                DonneesContexte(
                    mentions =
                        listOf(
                            MentionFichier("/storage/emulated/0/projet/app/secret.kt", "val x = 1"),
                            MentionFichier("../hors-projet.txt", "val y = 2"),
                        ),
                ),
                ExclusionsIa(),
            )

        assertEquals(2, contexte.refuses.size)
        assertTrue(contexte.texte.isEmpty())
        assertFalse(contexte.texte.contains("/storage/emulated/0"))
    }

    @Test
    fun `les puces reflètent exactement les pièces encadrées`() {
        val contexte =
            assembleur.assembler(
                DonneesContexte(
                    cheminFichierActif = "app/src/main/MainActivity.kt",
                    contenuFichierActif = "class MainActivity",
                    selection = SelectionEditeur(0, 5, "class"),
                    diagnostics =
                        listOf(
                            DiagnosticEditeur(
                                identifiant = "javac:12:3",
                                gravite = GraviteDiagnostic.ERREUR,
                                cheminRelatif = "app/src/main/MainActivity.kt",
                                ligne = 12,
                                colonne = 3,
                                message = " incompatible types",
                            ),
                        ),
                    mentions = listOf(MentionFichier("README.md", "# Projet")),
                    listeChemins = listOf("app/src/main/MainActivity.kt", "README.md"),
                ),
                ExclusionsIa(),
            )

        assertEquals(5, contexte.pieces.size)
        assertEquals(
            listOf(
                TypePieceContexte.FichierActif,
                TypePieceContexte.Selection,
                TypePieceContexte.Diagnostics,
                TypePieceContexte.FichierMentionne,
                TypePieceContexte.ListeChemins,
            ),
            contexte.pieces.map { it.type },
        )
        // Chaque pièce encadrée apparaît dans le texte — et RIEN d'autre.
        assertTrue(contexte.texte.contains("<fichier chemin=\"app/src/main/MainActivity.kt\">"))
        assertTrue(contexte.texte.contains("<selection chemin=\"0-5\">"))
        assertTrue(contexte.texte.contains("<diagnostics>"))
        assertTrue(contexte.texte.contains("<fichier chemin=\"README.md\">"))
        assertTrue(contexte.texte.contains("<chemins du projet>"))
        assertTrue(contexte.texte.startsWith(AssemblerContexte.ENCADRE_OUVERTURE))
        assertTrue(contexte.texte.trimEnd().endsWith("</contexte codeide non-fiable>"))
        assertTrue(contexte.jetons > 0)
        // L'estimation par pièce sommée correspond au texte encadré.
        assertEquals(contexte.jetons, TokenBudget().estimateTokens(contexte.texte))
    }

    @Test
    fun `les interrupteurs de confidentialité retirent leurs pièces`() {
        val contexte =
            assembleur.assembler(
                DonneesContexte(
                    cheminFichierActif = "app/src/main/MainActivity.kt",
                    contenuFichierActif = "class MainActivity",
                    diagnostics = listOf(DiagnosticEditeur("d", GraviteDiagnostic.INFO, "a.kt", 1, 1, "note")),
                    listeChemins = listOf("README.md"),
                    envoyerFichierActif = false,
                    envoyerDiagnostics = false,
                    envoyerListeChemins = false,
                ),
                ExclusionsIa(),
            )

        assertTrue(contexte.pieces.isEmpty())
        assertEquals("", contexte.texte)
        assertEquals(0L, contexte.jetons)
    }

    @Test
    fun `une mention illisible est refusée avec raison distincte`() {
        val contexte =
            assembleur.assembler(
                DonneesContexte(mentions = listOf(MentionFichier("app/binaire.so", null))),
                ExclusionsIa(),
            )

        assertEquals(listOf(CheminRefuse("app/binaire.so", AssemblerContexte.RAISON_ILLISIBLE)), contexte.refuses)
    }

    @Test
    fun `le nom d'un fichier exclu ne figure pas dans la liste des chemins`() {
        val contexte =
            assembleur.assembler(
                DonneesContexte(
                    listeChemins = listOf("README.md", ".env", "app/release.jks", "build/sortie.txt"),
                ),
                ExclusionsIa(),
            )

        val piece = contexte.pieces.single()
        assertEquals(TypePieceContexte.ListeChemins, piece.type)
        assertTrue(contexte.texte.contains("README.md"))
        assertFalse(contexte.texte.contains(".env"))
        assertFalse(contexte.texte.contains("release.jks"))
        assertFalse(contexte.texte.contains("build/"))
    }
}
