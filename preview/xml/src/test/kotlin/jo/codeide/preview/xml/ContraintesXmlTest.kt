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
 * Contrat d'ÉDITION DES CONTRAINTES (PE8b) : quatre ancres lues dans le
 * texte vivant, cible parent/frère + marge en UNE édition (xmlns:app
 * posé au besoin, cible+marge empilés, retrait propre) ; refus nets
 * (nom invalide, auto-contrainte, marge négative, no-op) — chaque
 * édition est VÉRIFIÉE PAR APPLICATION, le document reste bien formé.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class ContraintesXmlTest {
    /** Racine ConstraintLayout AVEC xmlns:app, deux enfants contraints. */
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
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintTop_margin="16dp" />

    <Button
        android:id="@+id/button"
        android:layout_width="wrap_content"
        android:layout_height="48dp"
        app:layout_constraintTop_toBottomOf="@id/titre"
        app:layout_constraintStart_toStartOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
""".trimStart()

    /** Le MÊME layout SANS xmlns:app (l'édition doit le poser). */
    private val sansApp =
        """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:id="@+id/titre"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content" />

</androidx.constraintlayout.widget.ConstraintLayout>
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
    private fun appliquer(texte: String, edition: EditionAttributsXml.EditionAttributsXmlResultat): String =
        texte.substring(0, edition.debut) + edition.remplacement + texte.substring(edition.fin)

    /** La RÉGION de l'élément i (les assertions d'absence sont LOCALES —
     * un frère peut porter le même attribut). */
    private fun regionDe(texte: String, indice: Int): String {
        val element = AligneurElementsXml.aligner(texte, null).getOrNull(indice)!!
        return texte.substring(element.debut, element.fin)
    }

    @Test
    fun `lit les quatre ancres dans l'ordre`() {
        val lues = EditionAttributsXml.lireContraintes(formate, spanDe(formate, 1)!!)!!
        assertEquals(4, lues.size)
        assertEquals(EditionAttributsXml.Cote.DEBUT, lues[0].cote)
        assertEquals("parent", lues[0].cible)
        assertNull(lues[0].margeDp)
        // FIN absente des deux côtés.
        assertNull(lues[1].cible)
        assertNull(lues[1].margeDp)
        // HAUT : cible + marge lues.
        assertEquals("parent", lues[2].cible)
        assertEquals(16, lues[2].margeDp)
        assertNull(lues[3].cible)
    }

    @Test
    fun `lit une cible de frere`() {
        val lues = EditionAttributsXml.lireContraintes(formate, spanDe(formate, 2)!!)!!
        assertEquals("@id/titre", lues[2].cible)
        assertEquals("parent", lues[0].cible)
    }

    @Test
    fun `ajoute une ancre et pose xmlns dans la meme edition`() {
        val span = spanDe(sansApp, 1)!!
        val edition =
            EditionAttributsXml.ecrireContrainte(
                sansApp,
                span,
                EditionAttributsXml.Cote.HAUT,
                EditionAttributsXml.CibleContrainte.Parent,
                null,
            )
        assertNotNull(edition)
        val apres = appliquer(sansApp, edition!!)
        // La déclaration xmlns:app ET la contrainte sont arrivées PAR UNE
        // SEULE application (une seule entrée d'historique).
        assertTrue(apres.contains("xmlns:app="))
        assertTrue(apres.contains("""app:layout_constraintTop_toTopOf="parent""""))
        // Le document reste bien formé, même nombre d'éléments.
        assertEquals(2, AligneurElementsXml.aligner(apres, null).size)
    }

    @Test
    fun `cible un frere par son identifiant`() {
        val span = spanDe(formate, 2)!!
        val edition =
            EditionAttributsXml.ecrireContrainte(
                formate,
                span,
                EditionAttributsXml.Cote.BAS,
                EditionAttributsXml.CibleContrainte.Vue("titre"),
                null,
            )
        assertNotNull(edition)
        val apres = appliquer(formate, edition!!)
        assertTrue(apres.contains("""app:layout_constraintBottom_toBottomOf="@id/titre""""))
        assertEquals(3, AligneurElementsXml.aligner(apres, null).size)
    }

    @Test
    fun `remplace la valeur seule d'une cible existante`() {
        val span = spanDe(formate, 2)!!
        val edition =
            EditionAttributsXml.ecrireContrainte(
                formate,
                span,
                EditionAttributsXml.Cote.HAUT,
                EditionAttributsXml.CibleContrainte.Parent,
                null,
            )
        assertNotNull(edition)
        val apres = appliquer(formate, edition!!)
        // La CIBLE change, le CÔTÉ visé reste (topologie minimale) :
        // Top_toBottomOf="parent" — jamais une réécriture hasardeuse.
        assertTrue(apres.contains("""app:layout_constraintTop_toBottomOf="parent""""))
        assertFalse(apres.contains("""app:layout_constraintTop_toBottomOf="@id/titre""""))
        // Le RESTE de l'élément est intact (valeurs d'origine en place).
        assertTrue(apres.contains("""android:id="@+id/button""""))
        assertTrue(apres.contains("""android:layout_height="48dp""""))
        // Bien formé (le document re-marché).
        assertEquals(3, AligneurElementsXml.aligner(apres, null).size)
    }

    @Test
    fun `ecrit la marge puis la retire a zero`() {
        val span = spanDe(formate, 2)!!
        val ecrite =
            EditionAttributsXml.ecrireContrainte(formate, span, EditionAttributsXml.Cote.HAUT, null, 12)
        assertNotNull(ecrite)
        val apres = appliquer(formate, ecrite!!)
        assertTrue(apres.contains("""app:layout_constraintTop_margin="12dp""""))
        // Marge 0 = RETRAIT (défaut plateforme) — sur le texte déjà édité.
        val spanApres = spanDe(apres, 2)!!
        val retiree =
            EditionAttributsXml.ecrireContrainte(apres, spanApres, EditionAttributsXml.Cote.HAUT, null, 0)
        assertNotNull(retiree)
        val final = appliquer(apres, retiree!!)
        // La marge du BOUTON part (le titre garde la SIENNE, 16 dp).
        assertFalse(regionDe(final, 2).contains("layout_constraintTop_margin"))
        assertTrue(regionDe(final, 1).contains("""app:layout_constraintTop_margin="16dp""""))
        assertEquals(3, AligneurElementsXml.aligner(final, null).size)
    }

    @Test
    fun `cible et marge arrivent en une seule edition`() {
        val span = spanDe(formate, 2)!!
        val edition =
            EditionAttributsXml.ecrireContrainte(
                formate,
                span,
                EditionAttributsXml.Cote.BAS,
                EditionAttributsXml.CibleContrainte.Parent,
                8,
            )
        assertNotNull(edition)
        val apres = appliquer(formate, edition!!)
        assertTrue(apres.contains("""app:layout_constraintBottom_toBottomOf="parent""""))
        assertTrue(apres.contains("""app:layout_constraintBottom_margin="8dp""""))
        // L'ordre conventionnel : la CIBLE avant la MARGE.
        assertTrue(
            apres.indexOf("layout_constraintBottom_toBottomOf") <
                apres.indexOf("layout_constraintBottom_margin"),
        )
    }

    @Test
    fun `retire l'ancre proprement`() {
        val span = spanDe(formate, 2)!!
        val edition =
            EditionAttributsXml.ecrireContrainte(
                formate,
                span,
                EditionAttributsXml.Cote.DEBUT,
                EditionAttributsXml.CibleContrainte.Aucune,
                null,
            )
        assertNotNull(edition)
        val apres = appliquer(formate, edition!!)
        // Le BOUTON perd son Start_toStartOf (le frère garde le sien).
        assertFalse(regionDe(apres, 2).contains("app:layout_constraintStart_toStartOf"))
        assertTrue(regionDe(apres, 1).contains("""app:layout_constraintStart_toStartOf="parent""""))
        assertEquals(3, AligneurElementsXml.aligner(apres, null).size)
    }

    @Test
    fun `refuse nom invalide et auto-contrainte`() {
        val spanTitre = spanDe(formate, 1)!!
        val spanBouton = spanDe(formate, 2)!!
        assertNull(
            EditionAttributsXml.ecrireContrainte(
                formate,
                spanBouton,
                EditionAttributsXml.Cote.DEBUT,
                EditionAttributsXml.CibleContrainte.Vue("2invalide"),
                null,
            ),
        )
        // Se contraindre SOI-MÊME n'a aucun sens.
        assertNull(
            EditionAttributsXml.ecrireContrainte(
                formate,
                spanTitre,
                EditionAttributsXml.Cote.FIN,
                EditionAttributsXml.CibleContrainte.Vue("titre"),
                null,
            ),
        )
    }

    @Test
    fun `refuse marge negative et no-op`() {
        val span = spanDe(formate, 2)!!
        // Marge négative.
        assertNull(
            EditionAttributsXml.ecrireContrainte(formate, span, EditionAttributsXml.Cote.HAUT, null, -1),
        )
        // Ni cible ni marge : rien à faire.
        assertNull(EditionAttributsXml.ecrireContrainte(formate, span, EditionAttributsXml.Cote.HAUT, null, null))
        // Retirer une ancre DÉJÀ absente : no-op.
        assertNull(
            EditionAttributsXml.ecrireContrainte(
                formate,
                span,
                EditionAttributsXml.Cote.BAS,
                EditionAttributsXml.CibleContrainte.Aucune,
                null,
            ),
        )
        // La marge identique : no-op (idempotence).
        assertNull(
            EditionAttributsXml.ecrireContrainte(formate, spanTitre(), EditionAttributsXml.Cote.HAUT, null, 16),
        )
    }

    private fun spanTitre(): InsertionXml.SpanParent = spanDe(formate, 1)!!
}
