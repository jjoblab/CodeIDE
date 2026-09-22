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
 * fichier nommé comme un dossier).
 */
class ArborescenceTest {
    /** Un fichier planifié de texte (groupe sans importance ici). */
    private fun fichier(chemin: String): PlannedFile =
        PlannedFile(chemin = chemin, group = TemplateFileGroup.CORE, contenu = PlannedContent.Texte(""))

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
}
