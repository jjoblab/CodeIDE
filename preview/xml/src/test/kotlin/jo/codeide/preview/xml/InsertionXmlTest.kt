package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contrat d'insertion de la palette (PE7a, maquette § « Catalogue et
 * insertion XML, en fonctions pures ») : l'édition `(debut, fin,
 * remplacement)` est vérifiée PAR APPLICATION au texte (le résultat doit
 * rester du XML bienformé et porter l'extrait à sa place) — plus les
 * refus X4 (span périmé, malforme, élément EXTERNE sans span) et les deux
 * façonnages ([renumeroter], [contraindre]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class InsertionXmlTest {
    private val formate =
        """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:id="@+id/titre"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content" />

</LinearLayout>
""".trimStart()

    private val bouton =
        "<Button\n" +
            "    android:id=\"@+id/button\"\n" +
            "    android:layout_width=\"wrap_content\"\n" +
            "    android:layout_height=\"48dp\" />"

    /** Le span de l'élément i (spans du texte via l'aligneur — miroir InfoVueRendu). */
    @Suppress("ReturnCount") // Les gardes (absent, sans span) sont des issues DISTINCTES du test.
    private fun spanDe(
        texte: String,
        indice: Int,
    ): InsertionXml.SpanParent? {
        val element = AligneurElementsXml.aligner(texte, null).getOrNull(indice) ?: return null
        if (element.debut < 0) return null
        return InsertionXml.SpanParent(element.debut, element.fin)
    }

    /** Applique l'édition (miroir du port : remplacement de plage). */
    private fun appliquer(
        texte: String,
        edition: InsertionXml.EditionXml,
    ): String = texte.substring(0, edition.debut) + edition.remplacement + texte.substring(edition.fin)

    @Test
    fun `insertion en fin de conteneur — append avant la fermante, indentation des enfants`() {
        val span = spanDe(formate, 0)!!
        val edition = InsertionXml.inserer(formate, span, 1, bouton)!!
        val resultat = appliquer(formate, edition)
        assertTrue(resultat.contains("\n    <Button"))
        assertTrue(resultat.contains("android:id=\"@+id/button\""))
        // La fermante revient à l'indentation du parent, le TextView reste.
        assertTrue(resultat.contains("</LinearLayout>"))
        assertTrue(resultat.contains("<TextView"))
        // Le XML reste bienformé (re-marche de l'aligneur : racine + 2 vues).
        assertEquals(3, AligneurElementsXml.aligner(resultat, null).size)
    }

    @Test
    fun `insertion avant le premier frère — l'indentation du frère est conservée`() {
        val span = spanDe(formate, 0)!!
        val edition = InsertionXml.inserer(formate, span, 0, bouton)!!
        val resultat = appliquer(formate, edition)
        // Le Button passe AVANT le TextView, tous deux indentés au même niveau.
        assertTrue(resultat.indexOf("<Button") < resultat.indexOf("<TextView"))
        assertTrue(resultat.contains("\n    <Button"))
        assertTrue(resultat.contains("\n    <TextView"))
        assertEquals(3, AligneurElementsXml.aligner(resultat, null).size)
    }

    @Test
    fun `insertion entre deux frères — milieu des écarts du document`() {
        val document =
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <TextView android:id="@+id/a" android:layout_width="wrap_content" android:layout_height="wrap_content" />
    <TextView android:id="@+id/b" android:layout_width="wrap_content" android:layout_height="wrap_content" />
</LinearLayout>
""".trimStart()
        val span = spanDe(document, 0)!!
        val edition = InsertionXml.inserer(document, span, 1, bouton)!!
        val resultat = appliquer(document, edition)
        assertTrue(resultat.indexOf("@+id/a") < resultat.indexOf("@+id/button"))
        assertTrue(resultat.indexOf("@+id/button") < resultat.indexOf("@+id/b\""))
    }

    @Test
    fun `conteneur auto-fermant converti en paire ouvrante et fermante`() {
        val document =
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
""".trimStart()
        val span = spanDe(document, 0)!!
        val edition = InsertionXml.inserer(document, span, 0, bouton)!!
        val resultat = appliquer(document, edition)
        // Le « / » de l'auto-fermante devient « > » — l'espace résiduel reste.
        assertTrue(resultat.contains("android:layout_height=\"match_parent\" >"))
        assertTrue(resultat.contains("\n    <Button"))
        assertTrue(resultat.contains("</LinearLayout>"))
        assertTrue(resultat.trimEnd().endsWith("</LinearLayout>"))
        assertEquals(2, AligneurElementsXml.aligner(resultat, null).size)
    }

    @Test
    fun `extrait avec app — xmlns ajouté à la racine dans la MÊME édition`() {
        val document =
            """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <TextView android:id="@+id/a" android:layout_width="wrap_content" android:layout_height="wrap_content" />
</androidx.constraintlayout.widget.ConstraintLayout>
""".trimStart()
        val span = spanDe(document, 0)!!
        val extrait = """<Button android:layout_width="wrap_content" app:layout_constraintTop_toTopOf="parent" />"""
        val edition = InsertionXml.inserer(document, span, 1, extrait)!!
        val resultat = appliquer(document, edition)
        assertTrue(resultat.contains("xmlns:app=\"http://schemas.android.com/apk/res-auto\""))
        assertTrue(resultat.contains("app:layout_constraintTop_toTopOf=\"parent\""))
        // Une SEULE édition : un seul annuler (le xmlns et le widget sont dans la même plage).
        assertEquals(3, AligneurElementsXml.aligner(resultat, null).size)
    }

    @Test
    fun `xmlns app déjà présent — l'édition ne le touche pas`() {
        val document =
            """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <TextView android:id="@+id/a" android:layout_width="wrap_content" android:layout_height="wrap_content" />
</androidx.constraintlayout.widget.ConstraintLayout>
""".trimStart()
        val span = spanDe(document, 0)!!
        val extrait = """<Button android:layout_width="wrap_content" app:layout_constraintTop_toTopOf="parent" />"""
        val edition = InsertionXml.inserer(document, span, 1, extrait)!!
        assertEquals(0, edition.remplacement.split("xmlns:app").size - 1)
        val resultat = appliquer(document, edition)
        assertEquals(1, resultat.split("xmlns:app").size - 1)
    }

    @Test
    fun `span périmé — l'édition refuse plutôt que de deviner`() {
        val span = spanDe(formate, 0)!!
        // Le texte a muté depuis le rendu (frappe AVANT l'élément) : les
        // offsets décalés ne correspondent plus. Une frappe APRÈS le span
        // (fin du document) laisse l'édition valide — les offsets tiennent.
        assertNull(InsertionXml.inserer(" " + formate, span, 1, bouton))
        assertNotNull(InsertionXml.inserer(formate + " ", span, 1, bouton))
        assertNull(InsertionXml.inserer("texte sans xml", span, 1, bouton))
    }

    @Test
    fun `XML malforme — refus X4`() {
        val malforme = "<LinearLayout><TextView id=</LinearLayout>"
        assertNull(InsertionXml.inserer(malforme, InsertionXml.SpanParent(0, 10), 0, bouton))
        assertNull(InsertionXml.inserer("", InsertionXml.SpanParent(0, 10), 0, bouton))
        assertNull(InsertionXml.inserer(formate, InsertionXml.SpanParent(-1, 10), 0, bouton))
        assertNull(InsertionXml.inserer(formate, InsertionXml.SpanParent(0, 5), 0, ""))
    }

    @Test
    fun `include non résolu — le frère suivant garde son span, append accepté`() {
        val document =
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <include layout="@layout/absent" />
    <TextView android:id="@+id/b" android:layout_width="wrap_content" android:layout_height="wrap_content" />
</LinearLayout>
""".trimStart()
        val elements = AligneurElementsXml.aligner(document, null)
        // v0.173.0 (S8) : le TextView APRÈS l'include non résolu GARDE son
        // span — le span est RÉEL (le texte est bien dans CE document) et
        // l'appariement par COOKIE le rendra (jamais l'ordre, comptes
        // divergents) ; le span externe (fichier inclus RÉSOLU) reste la
        // seule forme désarmée.
        assertTrue(elements.drop(1).all { it.debut >= 0 })
        val span = InsertionXml.SpanParent(elements.first().debut, elements.first().fin)
        // Append (index borné à la fin) : la fermante reste fiable.
        val append = InsertionXml.inserer(document, span, 5, bouton)!!
        val resultat = appliquer(document, append)
        assertTrue(resultat.contains("</LinearLayout>\n"))
        assertTrue(resultat.contains("<Button"))
    }

    @Test
    fun `renumeroter — premier identifiant libre, miroir nextId`() {
        val document =
            "<LinearLayout><TextView android:id=\"@+id/button\" />" +
                "<TextView android:id=\"@+id/button1\" /></LinearLayout>"
        assertEquals(
            "button2",
            InsertionXml.renumeroter("<Button android:id=\"@+id/button\" />", document).let {
                it.substringAfter("@+id/").substringBefore("\"")
            },
        )
        // La base libre reste telle quelle.
        val extrait = "<Button android:id=\"@+id/carte\" />"
        assertEquals(extrait, InsertionXml.renumeroter(extrait, document))
        // Les références @id/ bloquent aussi.
        val avecReference = "<LinearLayout><ImageView android:src=\"@id/logo\" /></LinearLayout>"
        assertEquals(
            "logo1",
            InsertionXml.renumeroter("<Button android:id=\"@+id/logo\" />", avecReference).let {
                it.substringAfter("@+id/").substringBefore("\"")
            },
        )
    }

    @Test
    fun `renumeroter — extrait sans identifiant inchangé`() {
        assertEquals("<Button />", InsertionXml.renumeroter("<Button />", formate))
    }

    @Test
    fun `contraindre — contraintes parent + marges conditionnelles (balise compacte)`() {
        val faconne = InsertionXml.contraindre("""<Button android:id="@+id/button" />""", 24, 16)
        assertTrue(faconne.contains("""app:layout_constraintTop_toTopOf="parent""""))
        assertTrue(faconne.contains("""app:layout_constraintStart_toStartOf="parent""""))
        assertTrue(faconne.contains("""android:layout_marginTop="24dp""""))
        assertTrue(faconne.contains("""android:layout_marginStart="16dp""""))
        // Zéro marge → pas d'attribut.
        val sansMarge = InsertionXml.contraindre("""<Button />""", 0, 0)
        assertTrue(!sansMarge.contains("layout_margin"))
        // La balise reste auto-fermante.
        assertTrue(sansMarge.endsWith("/>"))
    }

    @Test
    fun `contraindre — balise multi-lignes, un attribut par ligne à l'indentation existante`() {
        val extrait =
            "<Button\n" +
                "    android:id=\"@+id/button\"\n" +
                "    android:layout_width=\"wrap_content\" />"
        val faconne = InsertionXml.contraindre(extrait, 12, 0)
        assertTrue(faconne.contains("\n    app:layout_constraintTop_toTopOf=\"parent\""))
        assertTrue(faconne.contains("\n    app:layout_constraintStart_toStartOf=\"parent\""))
        assertTrue(faconne.contains("""android:layout_marginTop="12dp""""))
        // L'attribut d'origine reste.
        assertTrue(faconne.contains("android:id=\"@+id/button\""))
    }

    @Test
    fun `contraindre — extrait malforme rendu tel quel`() {
        assertEquals("pas un extrait", InsertionXml.contraindre("pas un extrait", 8, 8))
    }

    // ── PE12d : ajouterAttributs (généralisation, miroir applyPlan) ────

    @Test
    fun `ajouterAttributs — marges puis regles d un plan RelativeLayout (S1)`() {
        // Le plan S1 du fichier d'or : marginTop 16 + below + alignStart,
        // DANS L'ORDRE de la liste (marges d'abord, convention d'or).
        val extrait = "<Button\n    android:id=\"@+id/button1\"\n    android:layout_width=\"wrap_content\" />"
        val faconne =
            InsertionXml.ajouterAttributs(
                extrait,
                listOf(
                    "android:layout_marginTop" to "16dp",
                    "android:layout_below" to "@id/bio",
                    "android:layout_alignStart" to "@id/bio",
                ),
            )
        assertTrue(faconne.contains("\n    android:layout_marginTop=\"16dp\""))
        assertTrue(faconne.contains("\n    android:layout_below=\"@id/bio\""))
        assertTrue(faconne.contains("\n    android:layout_alignStart=\"@id/bio\""))
        assertTrue("l'attribut d'origine reste", faconne.contains("android:id=\"@+id/button1\""))
        assertTrue("la balise reste auto-fermante", faconne.trimEnd().endsWith("/>"))
    }

    @Test
    fun `ajouterAttributs — balise compacte, l injection suit la convention de contraindre`() {
        // La balise compacte existante portait déjà son espace avant le « / »
        // (le guillemet fermant le précède) : l'injection se colle au
        // dernier attribut, la fermeture reste collée — MÊME convention que
        // [contraindre] depuis PE7 (les extraits du catalogue sont
        // multi-lignes : un espace précède la fermeture).
        val faconne =
            InsertionXml.ajouterAttributs(
                """<TextView android:id="@+id/t" />""",
                listOf("android:layout_gravity" to "end|bottom"),
            )
        assertEquals("""<TextView android:id="@+id/t" android:layout_gravity="end|bottom"/>""", faconne)
    }

    @Test
    fun `ajouterAttributs — un attribut deja pose est REMPLACE en place, jamais duplique`() {
        val faconne =
            InsertionXml.ajouterAttributs(
                """<TextView android:layout_gravity="center" />""",
                listOf("android:layout_gravity" to "end|bottom"),
            )
        assertEquals("""<TextView android:layout_gravity="end|bottom" />""", faconne)
    }

    @Test
    fun `ajouterAttributs — liste vide et extrait malforme rendus tels quels`() {
        assertEquals("<TextView />", InsertionXml.ajouterAttributs("<TextView />", emptyList()))
        assertEquals("pas un extrait", InsertionXml.ajouterAttributs("pas un extrait", listOf("a:b" to "c")))
    }
}
