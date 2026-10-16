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
 * Contrat de RENOMMAGE D'IDENTIFIANT (PE8a) : la déclaration `@+id/x` ET
 * toutes les références `@id/x` du fichier deviennent le nouveau nom en
 * UNE édition ; refus nets (nom invalide, identique, déjà pris, sans id,
 * plateforme) — chaque édition est VÉRIFIÉE PAR APPLICATION au texte, le
 * résultat doit rester bien formé (re-marche de l'aligneur).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class RenommageIdentifiantTest {
    /** Layout ConstraintLayout : déclarations @+id et références @id mêlées. */
    private val formate =
        """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:id="@+id/titre"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Bonjour" />

    <Button
        android:id="@+id/button"
        android:layout_width="wrap_content"
        android:layout_height="48dp"
        app:layout_constraintTop_toBottomOf="@id/titre"
        app:layout_constraintStart_toStartOf="parent" />

    <TextView
        android:id="@+id/titre2"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        app:layout_constraintTop_toBottomOf="@id/titre2" />

</androidx.constraintlayout.widget.ConstraintLayout>
""".trimStart()

    /** Un document à identifiant de PLATEFORME (non renommable). */
    private val plateforme =
        """<?xml version="1.0" encoding="utf-8"?>
<ListView
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@android:id/list"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
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
    fun `renomme la declaration et les references en une edition`() {
        val span = spanDe(formate, 1)!!
        val edition = RenommageIdentifiant.renommer(formate, span, "entete")
        assertNotNull(edition)
        val apres = appliquer(formate, edition!!)
        // La déclaration ET la référence de contrainte sont passées au nom
        // nouveau, PAR UNE SEULE application (une seule entrée d'historique).
        assertTrue(apres.contains("""android:id="@+id/entete""""))
        assertTrue(apres.contains("""app:layout_constraintTop_toBottomOf="@id/entete""""))
        assertFalse(apres.contains("""android:id="@+id/titre""""))
        assertFalse(apres.contains("""app:layout_constraintTop_toBottomOf="@id/titre""""))
        // Le voisin proche (@+id/titre2) n'a PAS été emporté.
        assertTrue(apres.contains("""android:id="@+id/titre2""""))
        assertTrue(apres.contains("""app:layout_constraintTop_toBottomOf="@id/titre2""""))
        // Le document reste bien formé, MÊME NOMBRE d'éléments.
        assertEquals(4, AligneurElementsXml.aligner(apres, null).size)
    }

    @Test
    fun `le prefixe porte survit - declaration garde son plus`() {
        val span = spanDe(formate, 2)!!
        val apres = appliquer(formate, RenommageIdentifiant.renommer(formate, span, "valider")!!)
        // La déclaration du bouton garde @+id/ ; les références @id/ le leur.
        assertTrue(apres.contains("""android:id="@+id/valider""""))
        assertFalse(apres.contains("""android:id="@+id/button""""))
        // Aucune référence n'a été transformée en @+id/ (les 3 déclarations
        // du document portent le « + », les 2 références @id/ le leur).
        assertEquals(3, Regex("""@\+id/""").findAll(apres).count())
    }

    @Test
    fun `refuse un nom invalide`() {
        val span = spanDe(formate, 1)!!
        assertNull(RenommageIdentifiant.renommer(formate, span, "2debut"))
        assertNull(RenommageIdentifiant.renommer(formate, span, "avec-tiret"))
        assertNull(RenommageIdentifiant.renommer(formate, span, ""))
        assertNull(RenommageIdentifiant.renommer(formate, span, "a.b"))
        assertFalse(RenommageIdentifiant.nomValide("1a"))
        assertTrue(RenommageIdentifiant.nomValide("a_1"))
    }

    @Test
    fun `refuse le nom identique`() {
        val span = spanDe(formate, 1)!!
        assertNull(RenommageIdentifiant.renommer(formate, span, "titre"))
    }

    @Test
    fun `refuse une collision avec un identifiant existant`() {
        val span = spanDe(formate, 1)!!
        // « button » est DÉJÀ la déclaration du bouton : renommer dessus
        // créerait deux vues au même identifiant.
        assertNull(RenommageIdentifiant.renommer(formate, span, "button"))
    }

    @Test
    fun `refuse sans identifiant`() {
        // La RACINE du document formaté n'a pas d'android:id.
        val spanRacine = spanDe(formate, 0)!!
        assertNull(RenommageIdentifiant.identifiantActuel(formate, spanRacine))
        assertNull(RenommageIdentifiant.renommer(formate, spanRacine, "racine"))
    }

    @Test
    fun `refuse l'identifiant de plateforme`() {
        val span = spanDe(plateforme, 0)!!
        assertNull(RenommageIdentifiant.identifiantActuel(plateforme, span))
        assertNull(RenommageIdentifiant.renommer(plateforme, span, "maListe"))
    }

    @Test
    fun `identifiantActuel lit les deux prefixes`() {
        assertEquals("titre", RenommageIdentifiant.identifiantActuel(formate, spanDe(formate, 1)!!))
        assertEquals("button", RenommageIdentifiant.identifiantActuel(formate, spanDe(formate, 2)!!))
        assertEquals("titre2", RenommageIdentifiant.identifiantActuel(formate, spanDe(formate, 3)!!))
    }

    @Test
    fun `refuse un span perime`() {
        val span = spanDe(formate, 1)!!
        // Un span DÉCALÉ (texte réécrit depuis le rendu) ne localise plus
        // l'élément : le renommage se refuse, jamais d'écriture hasardeuse.
        val perime = InsertionXml.SpanParent(span.debut + 1, span.fin + 1)
        assertNull(RenommageIdentifiant.renommer(formate, perime, "entete"))
    }
}
