package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contrat d'EXTRACTION EN RESSOURCE (PE8c) : le littéral devient
 * `@string/nom` (remplacement de la VALEUR seule — le `@` initial reste
 * une référence, jamais `\@`), l'entrée rejoint le fichier de valeurs à
 * l'indentation détectée, échappée, SANS écraser un nom pris — chaque
 * édition est VÉRIFIÉE PAR APPLICATION, les deux documents restent bien
 * formés.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class ExtractionRessourceTest {
    /** Le layout à littéral (titre et bouton à texte francisé). */
    private val formate =
        """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:id="@+id/titre"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Bonjour le monde" />

    <Button
        android:id="@+id/button"
        android:layout_width="wrap_content"
        android:layout_height="48dp"
        android:text="@string/deja_ref" />

</LinearLayout>
""".trimStart()

    /** Le fichier de valeurs cible (indentation 4, entrées existantes). */
    private val strings =
        """<?xml version="1.0" encoding="utf-8"?>
<resources>

    <string name="app_name">CodeIDE</string>
    <string name="deja_ref">Déjà référencé</string>

</resources>
""".trimStart()

    /** Le span de l'élément i (spans du texte via l'aligneur). */
    @Suppress("ReturnCount") // Les gardes (absent, sans span) sont des issues DISTINCTES du test.
    private fun spanDe(
        texte: String,
        indice: Int,
    ): InsertionXml.SpanParent? {
        val element = AligneurElementsXml.aligner(texte, null).getOrNull(indice) ?: return null
        if (element.debut < 0) return null
        return InsertionXml.SpanParent(element.debut, element.fin)
    }

    /** L'application de l'édition (le contrat : le résultat est le nouveau texte). */
    private fun appliquer(
        texte: String,
        edition: EditionAttributsXml.EditionAttributsXmlResultat,
    ): String = texte.substring(0, edition.debut) + edition.remplacement + texte.substring(edition.fin)

    @Test
    fun `extraire remplace le literal par la reference`() {
        val span = spanDe(formate, 1)!!
        val edition = ExtractionRessource.extraire(formate, span, "android:text", "bonjour")!!
        val apres = appliquer(formate, edition)
        // La RÉFÉRENCE (jamais \@\string — l'échappement des littéraux ne
        // passe PAS par ici).
        assertTrue(apres.contains("""android:text="@string/bonjour""""))
        // Le document reste bien formé, même nombre d'éléments.
        assertEquals(3, AligneurElementsXml.aligner(apres, null).size)
    }

    @Test
    fun `extraire refuse reference, vide, nom invalide, span perime`() {
        // Déjà une référence : rien à extraire.
        assertNull(
            ExtractionRessource.extraire(formate, spanDe(formate, 2)!!, "android:text", "autre"),
        )
        // Nom invalide (convention minuscule).
        assertNull(
            ExtractionRessource.extraire(formate, spanDe(formate, 1)!!, "android:text", "Bonjour"),
        )
        assertNull(
            ExtractionRessource.extraire(formate, spanDe(formate, 1)!!, "android:text", "2rapide"),
        )
        // Span périmé.
        assertNull(
            ExtractionRessource.extraire(
                formate,
                InsertionXml.SpanParent(3, 999),
                "android:text",
                "bonjour",
            ),
        )
    }

    @Test
    fun `entreeStrings insere a l'indentation detectee et echappe`() {
        val edition =
            ExtractionRessource.entreeStrings(strings, "bonjour", "L'année & l'avenir <se profile>")!!
        val apres = appliquer(strings, edition)
        // L'entrée AVANT la fermeture, à l'indentation des voisines (4).
        val lignes = apres.lines()
        val indiceEntree = lignes.indexOfFirst { it.contains("name=\"bonjour\"") }
        val indiceFermeture = lignes.indexOfFirst { it.contains("</resources>") }
        assertTrue(indiceEntree in 0 until indiceFermeture)
        assertTrue(lignes[indiceEntree].startsWith("    <string name=\"bonjour\">"))
        // L'échappement Android/XML : apostrophe, esperluette, chevron.
        assertTrue(apres.contains("L\\'année &amp; l\\'avenir &lt;se profile&gt;"))
        // Le document reste bien formé : 3 entrées (2 existantes + la nouvelle).
        assertEquals(3, Regex("<string ").findAll(apres).count())
        assertTrue(apres.contains("""name="app_name">CodeIDE<"""))
    }

    @Test
    fun `entreeStrings refuse nom pris et fichier etranger`() {
        // Le nom est DÉJÀ PRIS dans le fichier de valeurs.
        assertNull(ExtractionRessource.entreeStrings(strings, "deja_ref", "x"))
        // Pas un fichier de valeurs.
        assertNull(ExtractionRessource.entreeStrings(formate, "bonjour", "x"))
        // Nom invalide.
        assertNull(ExtractionRessource.entreeStrings(strings, "Avec_Majuscule", "x"))
    }

    @Test
    fun `les deux editions composees laissent les deux documents bien formes`() {
        // Le chemin COMPLET du ViewModel : l'ENTRÉE d'abord, puis la RÉFÉRENCE.
        val entree = ExtractionRessource.entreeStrings(strings, "bonjour", "Bonjour le monde")!!
        val stringsApres = appliquer(strings, entree)
        val reference = ExtractionRessource.extraire(formate, spanDe(formate, 1)!!, "android:text", "bonjour")!!
        val layoutApres = appliquer(formate, reference)
        assertTrue(stringsApres.contains("""<string name="bonjour">Bonjour le monde</string>"""))
        assertTrue(layoutApres.contains("""android:text="@string/bonjour""""))
        assertEquals(3, AligneurElementsXml.aligner(layoutApres, null).size)
        assertTrue(stringsApres.contains("</resources>"))
    }

    @Test
    fun `nomPropose plie le littéral en nom acceptable`() {
        assertEquals("bonjour_le_monde", ExtractionRessource.nomPropose("Bonjour le monde !"))
        assertEquals("r123_test", ExtractionRessource.nomPropose("123 test"))
        assertEquals("texte", ExtractionRessource.nomPropose("   "))
        // Les accents deviennent des underscores pliés (convention ASCII).
        assertEquals("d_j_r_f_renc", ExtractionRessource.nomPropose("déjà référencé"))
        // Le nom proposé passe TOUJOURS sa propre validation.
        assertTrue(ExtractionRessource.nomValide(ExtractionRessource.nomPropose("« Été » — été...")))
        assertFalse(ExtractionRessource.nomValide("Bonjour"))
        assertTrue(ExtractionRessource.nomValide("bonjour_2"))
    }
}
