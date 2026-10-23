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
 * Contrat de SUPPRESSION SÛRE (PE12g — spec § 10, miroir `delSel`) :
 * l'élément et ses descendants partent AVEC chaque attribut du fichier
 * qui pointe un identifiant du sous-arbre — règles (`layout_below`),
 * ancre (`app:layout_anchor`, sa gravité suit), `labelFor` — le tout en
 * UNE SEULE édition vérifiée PAR APPLICATION : le résultat doit être le
 * texte attendu ET rester lisible (re-marche de l'arbre). Les références
 * portées PAR le sous-arbre disparaissent avec lui, jamais comptées.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class SuppressionSureTest {
    /** RelativeLayout : sous-arbre (carte + nom + saisie), règles et labelFor mêlés. */
    private val formate =
        """<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:id="@+id/titre"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Profil" />

    <LinearLayout
        android:id="@+id/carte"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_below="@id/titre"
        android:orientation="vertical">

        <TextView
            android:id="@+id/nom"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:labelFor="@+id/saisie" />

        <EditText
            android:id="@+id/saisie"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />
    </LinearLayout>

    <Button
        android:id="@+id/valider"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_below="@id/carte"
        android:layout_alignStart="@id/titre"
        android:text="Valider" />

    <TextView
        android:id="@+id/legende"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:labelFor="@+id/nom"
        android:text="Légende" />
</RelativeLayout>
""".trimStart()

    /** Le texte ATTENDU après la suppression de `carte` (une seule édition). */
    private val formateSansCarte =
        """<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:id="@+id/titre"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Profil" />


    <Button
        android:id="@+id/valider"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_alignStart="@id/titre"
        android:text="Valider" />

    <TextView
        android:id="@+id/legende"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Légende" />
</RelativeLayout>
""".trimStart()

    /** CoordinatorLayout : l'ancre et sa gravité sur le bouton flottant. */
    private val coordinateur =
        """<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <RecyclerView
        android:id="@+id/liste"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fab"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        app:layout_anchor="@id/liste"
        app:layout_anchorGravity="bottom|end" />
</androidx.coordinatorlayout.widget.CoordinatorLayout>
""".trimStart()

    /** Document COMPACT (une ligne) : bloc seul, retrait en ligne. */
    private val compact: String =
        "<RelativeLayout" +
            " xmlns:android=\"http://schemas.android.com/apk/res/android\"" +
            " xmlns:app=\"http://schemas.android.com/apk/res-auto\"" +
            " android:layout_width=\"match_parent\"" +
            " android:layout_height=\"match_parent\">" +
            "<TextView android:id=\"@+id/a\" android:layout_width=\"wrap_content\"" +
            " android:layout_height=\"wrap_content\" />" +
            "<Button android:layout_width=\"wrap_content\"" +
            " android:layout_height=\"wrap_content\" android:layout_below=\"@id/a\" />" +
            "</RelativeLayout>"

    /** Le span de l'élément [indice] (spans du texte via l'aligneur). */
    private fun spanDe(texte: String, indice: Int): InsertionXml.SpanParent? {
        val element = AligneurElementsXml.aligner(texte, null).getOrNull(indice) ?: return null
        return if (element.debut < 0) null else InsertionXml.SpanParent(element.debut, element.fin)
    }

    /** L'application de l'édition (le contrat : le résultat est le nouveau texte). */
    private fun appliquer(texte: String, edition: EditionAttributsXml.EditionAttributsXmlResultat): String =
        texte.substring(0, edition.debut) + edition.remplacement + texte.substring(edition.fin)

    @Test
    fun `supprime l element et ses references mortes en une seule edition`() {
        val resultat = SuppressionSure.composer(formate, spanDe(formate, 2)!!)
        assertNotNull(resultat)
        // Deux références retirées : le layout_below du Button, le labelFor
        // de la légende — PAS le labelFor interne au sous-arbre (il part
        // avec lui), PAS l'alignStart vers `titre` (il survit).
        assertEquals(2, resultat!!.referencesRetirees)
        assertEquals(formateSansCarte, appliquer(formate, resultat.edition))
        // Le résultat reste lisible : la racine et les trois survivants.
        assertEquals(4, ArbreAttributsXml.lire(formateSansCarte).size)
    }

    @Test
    fun `l ancre perdue emporte sa gravite sans la compter`() {
        val resultat = SuppressionSure.composer(coordinateur, spanDe(coordinateur, 1)!!)
        assertNotNull(resultat)
        // L'ancre EST une référence ; sa gravité la suit SANS compter.
        assertEquals(1, resultat!!.referencesRetirees)
        val texte = appliquer(coordinateur, resultat.edition)
        assertFalse(texte.contains("RecyclerView"))
        assertFalse(texte.contains("layout_anchor"))
        assertFalse(texte.contains("layout_anchorGravity"))
        assertTrue(texte.contains("""android:id="@+id/fab""""))
        assertTrue(texte.contains("<androidx.coordinatorlayout.widget.CoordinatorLayout"))
    }

    @Test
    fun `une suppression sans reference compte zero et ne touche rien d autre`() {
        val resultat = SuppressionSure.composer(coordinateur, spanDe(coordinateur, 2)!!)
        assertNotNull(resultat)
        assertEquals(0, resultat!!.referencesRetirees)
        // Le bouton flottant part seul : son ancre vers `liste` meurt avec
        // lui (jamais comptée), la liste reste INTACTE.
        val texte = appliquer(coordinateur, resultat.edition)
        assertFalse(texte.contains("FloatingActionButton"))
        assertTrue(texte.contains("""android:id="@+id/liste""""))
    }

    @Test
    fun `document compact - le bloc et la reference partent en ligne`() {
        val resultat = SuppressionSure.composer(compact, spanDe(compact, 1)!!)
        assertNotNull(resultat)
        assertEquals(1, resultat!!.referencesRetirees)
        // La racine et le bouton restent, SANS le TextView ni sa référence.
        val attendu: String =
            "<RelativeLayout" +
                " xmlns:android=\"http://schemas.android.com/apk/res/android\"" +
                " xmlns:app=\"http://schemas.android.com/apk/res-auto\"" +
                " android:layout_width=\"match_parent\"" +
                " android:layout_height=\"match_parent\">" +
                "<Button android:layout_width=\"wrap_content\"" +
                " android:layout_height=\"wrap_content\" />" +
                "</RelativeLayout>"
        assertEquals(attendu, appliquer(compact, resultat.edition))
    }

    @Test
    fun `refuse un span perime`() {
        assertNull(SuppressionSure.composer(formate, InsertionXml.SpanParent(3, 8)))
    }
}
