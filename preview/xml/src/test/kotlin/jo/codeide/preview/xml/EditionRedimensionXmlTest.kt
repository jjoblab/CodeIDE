package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L'édition groupée du REDIMENSIONNEMENT (PE11l + PE14-S2 —
 * [EditionAttributsXml.ecrireRedimension]) : les deux axes et les
 * AJUSTEMENTS PAR-LAYOUT partent en UNE SEULE édition atomique (un
 * geste = une entrée d'historique, miroir `mutate` de la maquette) —
 * extraits d'[EditionAttributsXmlTest] (LargeClass) sans autre
 * changement, mêmes valeurs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class EditionRedimensionXmlTest {
    /** Le document formaté de référence (miroir [EditionAttributsXmlTest]). */
    private val formate =
        """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:id="@+id/titre"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Bonjour"
        android:paddingHorizontal="16dp" />

    <Button
        android:id="@+id/button"
        android:layout_width="wrap_content"
        android:layout_height="48dp" />

</LinearLayout>
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

    /** Le span du TextView (élément 1 du document formaté). */
    private fun spanTexte(): InsertionXml.SpanParent = spanDe(formate, 1)!!

    /** Le span du Button (élément 2 du document formaté). */
    private fun spanBouton(): InsertionXml.SpanParent = spanDe(formate, 2)!!

    /** Applique l'édition (miroir du port : remplacement de plage). */
    private fun appliquer(texte: String, edition: EditionAttributsXml.EditionAttributsXmlResultat): String =
        texte.substring(0, edition.debut) + edition.remplacement + texte.substring(edition.fin)

    // ── Éclatement des raccourcis (côtés) ────────────────────────────────

    @Test
    fun `ecrireCote eclate paddingHorizontal en Start et End`() {
        val edition =
            EditionAttributsXml.ecrireCote(
                formate,
                spanTexte(),
                "android:padding",
                EditionAttributsXml.Cote.DEBUT,
                24,
            )!!
        val resultat = appliquer(formate, edition)
        assertFalse(resultat.contains("android:paddingHorizontal"))
        assertTrue(resultat.contains("android:paddingStart=\"24dp\""))
        assertTrue(resultat.contains("android:paddingEnd=\"16dp\""))
    }

    @Test
    fun `ecrireCote modifie le cote specifique present`() {
        val avecStart =
            formate.replace(
                "android:paddingHorizontal=\"16dp\"",
                "android:paddingStart=\"16dp\"\n        android:paddingEnd=\"16dp\"",
            )
        val edition =
            EditionAttributsXml.ecrireCote(
                avecStart,
                spanDe(avecStart, 1)!!,
                "android:padding",
                EditionAttributsXml.Cote.DEBUT,
                24,
            )!!
        val resultat = appliquer(avecStart, edition)
        assertTrue(resultat.contains("android:paddingStart=\"24dp\""))
        assertTrue(resultat.contains("android:paddingEnd=\"16dp\""))
    }

    @Test
    fun `valeurCote lit le raccourci puis le cote specifique`() {
        val attrs = EditionAttributsXml.lire(formate, spanTexte())!!
        assertEquals(
            "16dp",
            EditionAttributsXml.valeurCote(attrs, "android:padding", EditionAttributsXml.Cote.DEBUT),
        )
        assertNull(EditionAttributsXml.valeurCote(attrs, "android:padding", EditionAttributsXml.Cote.HAUT))
    }

    @Test
    fun `ecrireCote la meme valeur refuse null`() {
        // Le raccourci couvre déjà 16dp pour ce côté : no-op (idempotence).
        assertNull(
            EditionAttributsXml.ecrireCote(
                formate,
                spanTexte(),
                "android:padding",
                EditionAttributsXml.Cote.DEBUT,
                16,
            ),
        )
    }

    // ── Dimensions groupées (PE11l + PE14-S2 — poignée de l'aperçu) ─────

    @Test
    fun `ecrireRedimension remplace les deux axes en une seule edition`() {
        // Le geste de l'aperçu écrit largeur ET hauteur d'un coup : UNE
        // édition (une entrée d'historique), la balise remaniée porte
        // les DEUX valeurs nouvelles — les autres attributs restent.
        val edition =
            EditionAttributsXml.ecrireRedimension(
                formate,
                spanBouton(),
                largeurDp = 120,
                hauteurDp = 48,
            )!!
        val resultat = appliquer(formate, edition)
        assertTrue("""android:layout_width="120dp"""" in resultat)
        assertTrue("""android:layout_height="48dp"""" in resultat)
        assertTrue("""android:id="@+id/button"""" in resultat)
        assertEquals("le document reste bien formé", 3, AligneurElementsXml.aligner(resultat, null).size)
    }

    @Test
    fun `ecrireRedimension insere les attributs absents`() {
        // Une balise SANS dimensions reçoit les DEUX attributs dans la
        // même édition (l'insertion compose largeur PUIS hauteur).
        val texte =
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <Button android:id="@+id/seul" />
</LinearLayout>
""".trimStart()
        val span = spanDe(texte, 1)!!
        val edition = EditionAttributsXml.ecrireRedimension(texte, span, 80, 32)!!
        val resultat = appliquer(texte, edition)
        assertTrue("""android:layout_width="80dp"""" in resultat)
        assertTrue("""android:layout_height="32dp"""" in resultat)
        assertEquals(2, AligneurElementsXml.aligner(resultat, null).size)
    }

    @Test
    fun `ecrireRedimension sans changement refuse null idempotence`() {
        // Les deux valeurs SONT déjà posées : le geste n'écrit rien.
        val texte =
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <Button android:layout_width="96dp" android:layout_height="48dp" />
</LinearLayout>
""".trimStart()
        val span = spanDe(texte, 1)!!
        assertNull(
            "aucune édition attendue",
            EditionAttributsXml.ecrireRedimension(texte, span, 96, 48),
        )
    }

    @Test
    fun `ecrireRedimension sur un span perime refuse null`() {
        assertNull(
            "span périmé : jamais d'exception (règle 6)",
            EditionAttributsXml.ecrireRedimension(formate, InsertionXml.SpanParent(3, 7), 64, 32),
        )
    }

    // ── PE14-S2 (C3) — le PAR-LAYOUT au relâchement ─────────────────────

    @Test
    fun `ecrireRedimension retire le poids qui contredit la taille fixee`() {
        // Cas A de l'étude PE14 : dans un LinearLayout, un enfant à poids
        // non nul absorbait l'espace RESTANT PAR-DESSUS sa taille
        // déclarée — le geste ne changeait RIEN au rendu. Le relâchement
        // part le poids DANS LA MÊME édition.
        val texte =
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <Button android:layout_width="0dp" android:layout_height="wrap_content"
        android:layout_weight="1" />
</LinearLayout>
""".trimStart()
        val span = spanDe(texte, 1)!!
        val edition =
            EditionAttributsXml.ecrireRedimension(
                texte,
                span,
                largeurDp = 120,
                hauteurDp = 48,
                ajustements = EditionAttributsXml.AjustementsRedimension(retirerPoids = true),
            )!!
        val resultat = appliquer(texte, edition)
        assertTrue("""android:layout_width="120dp"""" in resultat)
        assertTrue("""android:layout_height="48dp"""" in resultat)
        assertFalse("le poids part avec la taille fixe", """android:layout_weight""" in resultat)
        assertEquals(2, AligneurElementsXml.aligner(resultat, null).size)
    }

    @Test
    fun `ecrireRedimension ecrit la marge du bord tenu`() {
        // Cas C de l'étude PE14 : enfant de RelativeLayout ancré par
        // SA GAUCHE — saisir la poignée HAUT-GAUCHE (ancre bas-droite)
        // doit réécrire la marge GAUCHE pour que le bord suive le
        // doigt, dans la MÊME édition que les dimensions.
        val texte =
            """<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <Button android:id="@+id/btn" android:layout_width="96dp"
        android:layout_height="48dp" android:layout_marginLeft="8dp" />
</RelativeLayout>
""".trimStart()
        val span = spanDe(texte, 1)!!
        val edition =
            EditionAttributsXml.ecrireRedimension(
                texte,
                span,
                largeurDp = 120,
                hauteurDp = 48,
                ajustements =
                EditionAttributsXml.AjustementsRedimension(
                    marges = listOf(EditionAttributsXml.Cote.DEBUT to 40),
                ),
            )!!
        val resultat = appliquer(texte, edition)
        assertTrue("""android:layout_width="120dp"""" in resultat)
        assertTrue("""android:layout_height="48dp"""" in resultat)
        assertTrue("""android:layout_marginLeft="40dp"""" in resultat)
        assertEquals(2, AligneurElementsXml.aligner(resultat, null).size)
    }

    @Test
    fun `ecrireRedimension pose les positions outils du dialecte tools existant`() {
        // Cas CL de l'étude PE14 (arbitrage D3) : les positions absolues
        // du dialecte `tools:` ne se posent QUE si le document déclare
        // déjà le dialecte — l'appelant (ViewModel) vérifie la
        // déclaration, l'éditeur AJOUTE les attributs (xmlns posé par
        // [EditionAttributsXml.definir] au besoin).
        val texte =
            """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <Button android:id="@+id/btn" android:layout_width="96dp"
        android:layout_height="48dp" />
</androidx.constraintlayout.widget.ConstraintLayout>
""".trimStart()
        val span = spanDe(texte, 1)!!
        val edition =
            EditionAttributsXml.ecrireRedimension(
                texte,
                span,
                largeurDp = 120,
                hauteurDp = 48,
                ajustements =
                EditionAttributsXml.AjustementsRedimension(
                    positionOutilsX = 16,
                    positionOutilsY = 24,
                ),
            )!!
        val resultat = appliquer(texte, edition)
        assertTrue("""tools:layout_editor_absoluteX="16dp"""" in resultat)
        assertTrue("""tools:layout_editor_absoluteY="24dp"""" in resultat)
        assertTrue("""android:layout_width="120dp"""" in resultat)
    }
}
