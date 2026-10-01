package jo.codeide.feature.newproject

import jo.codeide.core.model.PlannedContent
import jo.codeide.core.model.PlannedFile
import jo.codeide.core.model.TemplateFileGroup
import jo.codeide.core.model.TemplatePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la transformation plan → arborescence du récapitulatif
 * (section 12.3, étape 11) : dossiers avant fichiers, tri stable,
 * comptage récursif, chemins imbriqués et cas limites (doublons,
 * fichier nommé comme un dossier) ; phase 4 (ADR 0077) : identités
 * originales des n0153uds renommés et validation locale des renommages.
 */
class ArborescenceTest {
    /** Un fichier planifié de texte (groupe sans importance ici). */
    private fun fichier(
        chemin: String,
        cheminOriginal: String? = null,
    ): PlannedFile =
        PlannedFile(
            chemin = chemin,
            group = TemplateFileGroup.CORE,
            contenu = PlannedContent.Texte(""),
            cheminOriginal = cheminOriginal,
        )

    @Test
    fun `les dossiers précèdent les fichiers et le tri est lexicographique`() {
        val arbre =
            Arborescence.depuisPlan(
                TemplatePlan(
                    listOf(
                        fichier("zz.txt"),
                        fichier("src/Main.kt"),
                        fichier("README.md"),
                        fichier("assets/logo.png"),
                    ),
                ),
            )

        assertEquals(4, arbre.nombreFichiers)
        assertEquals(
            listOf("assets", "src", "README.md", "zz.txt"),
            arbre.racine.enfantsTries.map { it.nom },
        )
        assertEquals(
            listOf("logo.png"),
            (arbre.racine.enfantsTries.first() as Noeud.Dossier).enfantsTries.map { it.nom },
        )
    }

    @Test
    fun `les chemins imbriqués créent la chaîne complète de dossiers`() {
        val arbre =
            Arborescence.depuisPlan(
                TemplatePlan(listOf(fichier("src/main/kotlin/com/exemple/Main.kt"))),
            )

        assertEquals(1, arbre.nombreFichiers)
        assertEquals(
            listOf("src", "main", "kotlin", "com", "exemple", "Main.kt"),
            chaineUnique(arbre.racine),
        )
    }

    /** Descend la colonne d'enfants uniques en collectant les noms. */
    private fun chaineUnique(dossier: Noeud.Dossier): List<String> {
        val enfant = dossier.enfantsTries.singleOrNull() ?: return emptyList()
        val descendant = enfant as? Noeud.Dossier
        return listOf(enfant.nom) + (descendant?.let(::chaineUnique) ?: emptyList())
    }

    @Test
    fun `un fichier ne masque jamais un dossier de même nom`() {
        val arbre =
            Arborescence.depuisPlan(
                TemplatePlan(
                    listOf(
                        fichier("build/out.jar"),
                        fichier("build"),
                    ),
                ),
            )

        // « build » reste un dossier (premier arrivant) ; la feuille est ignorée.
        val enfant = arbre.racine.enfantsTries.single()
        assertTrue(enfant is Noeud.Dossier)
        assertEquals(1, arbre.nombreFichiers)
    }

    @Test
    fun `un plan vide produit un arbre vide`() {
        val arbre = Arborescence.depuisPlan(TemplatePlan(emptyList()))

        assertEquals(0, arbre.nombreFichiers)
        assertTrue(arbre.racine.enfantsTries.isEmpty())
    }

    // -------------------------------------------- identités (ADR 0077)

    @Test
    fun `un n0153ud renommé garde son chemin original comme identité`() {
        val arbre =
            Arborescence.depuisPlan(
                TemplatePlan(
                    listOf(
                        fichier("source/jeanne/demo/Main.txt", "src/jeanne/demo/Main.txt"),
                        fichier("NOTES.md", "README.md"),
                    ),
                ),
            )

        assertEquals("README.md", arbre.noeud("README.md")?.cheminOriginal)
        assertEquals("NOTES.md", arbre.noeud("README.md")?.nom)
        assertEquals("src/jeanne/demo/Main.txt", arbre.noeud("src/jeanne/demo/Main.txt")?.cheminOriginal)
        // La racine a une identité vide, jamais renommée.
        assertEquals("", arbre.racine.cheminOriginal)
    }

    @Test
    fun `les identités suivent la structure originale dossier par dossier`() {
        val arbre =
            Arborescence.depuisPlan(
                TemplatePlan(
                    listOf(
                        fichier("nouveau/interieur/App.txt", "vieux/interieur/App.txt"),
                    ),
                ),
            )

        val dossier = arbre.noeud("vieux") as Noeud.Dossier
        assertEquals("nouveau", dossier.nom)
        assertEquals("vieux", dossier.cheminOriginal)
        assertEquals("vieux/interieur", (dossier.enfantsTries.single() as Noeud.Dossier).cheminOriginal)
        assertEquals("vieux/interieur/App.txt", arbre.noeud("vieux/interieur/App.txt")?.cheminOriginal)
    }

    // ---------------------------------------- validation des renommages

    @Test
    fun `un nom vide, séparé ou réservé est refusé localement`() {
        val arbre = Arborescence.depuisPlan(TemplatePlan(listOf(fichier("src/Main.kt"))))

        assertEquals(ErreurRenommage.VIDE, arbre.validerRenommage("src", "   "))
        assertEquals(ErreurRenommage.SEPARATEUR, arbre.validerRenommage("src", "a/b"))
        assertEquals(ErreurRenommage.SEPARATEUR, arbre.validerRenommage("src", "a\\b"))
        assertEquals(ErreurRenommage.PARENT, arbre.validerRenommage("src", ".."))
        assertEquals(null, arbre.validerRenommage("src", "source"))
    }

    @Test
    fun `un frère portant déjà le nom final est refusé`() {
        val arbre =
            Arborescence.depuisPlan(
                TemplatePlan(
                    listOf(
                        fichier("src/Main.kt"),
                        fichier("src/Utils.kt"),
                        fichier("README.md"),
                    ),
                ),
            )

        assertEquals(ErreurRenommage.EXISTE, arbre.validerRenommage("src/Main.kt", "Utils.kt"))
        // Renommer vers son propre nom actuel est un non-changement licite.
        assertEquals(null, arbre.validerRenommage("src/Main.kt", "Main.kt"))
        // Un dossier et un fichier frère entrent en collision aussi.
        assertEquals(ErreurRenommage.EXISTE, arbre.validerRenommage("README.md", "src"))
    }
}
