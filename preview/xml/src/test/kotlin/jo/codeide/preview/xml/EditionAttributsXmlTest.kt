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
 * Contrat d'ÉDITION D'ATTRIBUTS (PE7d/PE7e, maquette § « Attributs en
 * sections selon le type de vue ») : remplacement de valeur, ajout à la
 * position conventionnelle ([EditionAttributsXml.definir]), retrait
 * PROPRE, lecture ([EditionAttributsXml.lire]), côtés avec ÉCLATEMENT du
 * raccourci ([EditionAttributsXml.ecrireCote]), échappement, refus X4 —
 * chaque édition est VÉRIFIÉE PAR APPLICATION au texte (miroir
 * [InsertionXmlTest], le résultat doit rester bienformé).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class EditionAttributsXmlTest {
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
    private fun appliquer(
        texte: String,
        edition: EditionAttributsXml.EditionAttributsXmlResultat,
    ): String = texte.substring(0, edition.debut) + edition.remplacement + texte.substring(edition.fin)

    // ── Remplacement et idempotence ──────────────────────────────────────

    @Test
    fun `definir remplace la valeur seule`() {
        val edition =
            EditionAttributsXml.definir(formate, spanTexte(), "android:text", "Salut")!!
        val resultat = appliquer(formate, edition)
        assertTrue(resultat.contains("android:text=\"Salut\""))
        assertFalse(resultat.contains("android:text=\"Bonjour\""))
        // Le reste du document bouge le moins possible : le span édité est
        // la valeur SEULE (le nom et les guillemets restent en place).
        assertEquals(formate.length, resultat.length - "Salut".length + "Bonjour".length)
    }

    @Test
    fun `definir la meme valeur refuse null idempotence`() {
        assertNull(EditionAttributsXml.definir(formate, spanTexte(), "android:text", "Bonjour"))
        // Appliquer DEUX fois la même édition ne change pas le document.
        val edition = EditionAttributsXml.definir(formate, spanTexte(), "android:text", "Salut")!!
        val uneFois = appliquer(formate, edition)
        val spanUneFois = spanDe(uneFois, 1)!!
        val deuxieme = EditionAttributsXml.definir(uneFois, spanUneFois, "android:text", "Salut")
        assertNull(deuxieme)
        assertEquals(uneFois, uneFois)
    }

    // ── Ajout à la position conventionnelle ──────────────────────────────

    @Test
    fun `definir ajoute apres le dernier attribut du meme seau`() {
        // android:textSize (seau « reste ») : s'ajoute APRÈS le dernier
        // attribut du même seau (text, paddingHorizontal), pas avant les layout_*.
        val edition =
            EditionAttributsXml.definir(formate, spanTexte(), "android:textSize", "14sp")!!
        val resultat = appliquer(formate, edition)
        val lignes = resultat.lines()
        val indiceTexte = lignes.indexOfFirst { it.contains("android:text=") }
        val indiceTaille = lignes.indexOfFirst { it.contains("android:textSize=") }
        val indicePadding = lignes.indexOfFirst { it.contains("android:paddingHorizontal=") }
        val indiceId = lignes.indexOfFirst { it.contains("android:id=") }
        val indiceLargeur = lignes.indexOfFirst { it.contains("android:layout_width=") }
        assertTrue(indiceTaille > indiceTexte)
        assertTrue(indiceTaille > indicePadding)
        assertTrue(indiceTaille > indiceLargeur)
        assertTrue(indiceTaille > indiceId)
        // Sur sa PROPRE ligne, à l'indentation des voisins.
        assertTrue(lignes[indiceTaille].trimStart().startsWith("android:textSize="))
    }

    @Test
    fun `definir ajoute avant la fermeture auto-fermante`() {
        // Le Button n'a pas de textColor : l'ajout passe avant le « /> ».
        val edition =
            EditionAttributsXml.definir(formate, spanBouton(), "android:textColor", "#FF0000")!!
        val resultat = appliquer(formate, edition)
        val element =
            resultat
                .substringAfter(
                    "<Button",
                ).substringBefore("/>")
                .substringAfter("android:layout_height=\"48dp\"")
        assertTrue(element.contains("android:textColor=\"#FF0000\""))
    }

    @Test
    fun `definir respecte l'indentation par tabulations`() {
        val texte =
            "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                "\t<TextView\n" +
                "\t\tandroid:id=\"@+id/a\"\n" +
                "\t\tandroid:layout_width=\"wrap_content\" />\n" +
                "</LinearLayout>"
        val span = spanDe(texte, 1)!!
        val edition = EditionAttributsXml.definir(texte, span, "android:alpha", "0.5")!!
        val resultat = appliquer(texte, edition)
        assertTrue(resultat.contains("\n\t\tandroid:alpha=\"0.5\""))
    }

    @Test
    fun `definir remplace la valeur dans un document CRLF`() {
        val texte =
            "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\">\r\n" +
                "    <TextView android:text=\"Avant\" />\r\n" +
                "</LinearLayout>"
        val edition =
            EditionAttributsXml.definir(texte, spanDe(texte, 1)!!, "android:text", "Apres")!!
        val resultat = appliquer(texte, edition)
        assertTrue(resultat.contains("android:text=\"Apres\""))
        assertFalse(resultat.contains("Avant"))
    }

    // ── Retrait ──────────────────────────────────────────────────────────

    @Test
    fun `definir vide retire l'attribut avec sa ligne`() {
        val edition = EditionAttributsXml.definir(formate, spanTexte(), "android:text", "")!!
        val resultat = appliquer(formate, edition)
        assertFalse(resultat.contains("android:text="))
        assertFalse(resultat.contains("\n\n\n")) // pas de ligne vide résiduelle
        assertTrue(resultat.contains("android:id=\"@+id/titre\""))
    }

    @Test
    fun `definir vide retire l'attribut en ligne avec son espace`() {
        val texte =
            "<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                "android:id=\"@+id/a\" android:text=\"X\" android:alpha=\"1\" />"
        val edition = EditionAttributsXml.definir(texte, spanDe(texte, 0)!!, "android:text", "")!!
        val resultat = appliquer(texte, edition)
        assertFalse(resultat.contains("android:text="))
        // Un seul espace sépare les deux attributs restants.
        assertTrue(resultat.contains("android:id=\"@+id/a\" android:alpha=\"1\""))
    }

    @Test
    fun `definir vide un attribut absent refuse null`() {
        assertNull(EditionAttributsXml.definir(formate, spanTexte(), "android:alpha", ""))
    }

    // ── Lecture ──────────────────────────────────────────────────────────

    @Test
    fun `lire rend les attributs dans l'ordre du source avec plages`() {
        val attrs = EditionAttributsXml.lire(formate, spanTexte())!!
        assertEquals(
            listOf(
                "android:id",
                "android:layout_width",
                "android:layout_height",
                "android:text",
                "android:paddingHorizontal",
            ),
            attrs.map { it.nom },
        )
        assertEquals("@+id/titre", attrs.first().valeur)
        assertEquals("Bonjour", attrs[3].valeur)
        // Les plages sont ABSOLUES, coïncident avec le texte réel et
        // couvrent l'ATTRIBUT COMPLET (nom + « = » + valeur — PE8b : les
        // insertions après ancre exigent la fin réelle de l'attribut).
        for (attr in attrs) {
            assertEquals("""${attr.nom}="${attr.valeur}"""", formate.substring(attr.debut, attr.fin))
        }
    }

    @Test
    fun `lire un span perime rend null`() {
        assertNull(EditionAttributsXml.lire(formate, InsertionXml.SpanParent(3, 999)))
        assertNull(EditionAttributsXml.lire(formate, InsertionXml.SpanParent(0, 0)))
    }

    // ── xmlns et préfixes ────────────────────────────────────────────────

    @Test
    fun `definir un app_ sans xmlns ajoute la declaration dans la meme edition`() {
        // Document SANS xmlns:app : un app:cornerRadius doit l'ajouter.
        val sansApp = formate.replace("    xmlns:app=\"http://schemas.android.com/apk/res-auto\"\n", "")
        val edition =
            EditionAttributsXml.definir(sansApp, spanDe(sansApp, 1)!!, "app:cornerRadius", "12dp")!!
        val resultat = appliquer(sansApp, edition)
        assertTrue(resultat.contains("xmlns:app=\"http://schemas.android.com/apk/res-auto\""))
        assertTrue(resultat.contains("app:cornerRadius=\"12dp\""))
        // UNE édition : l'application UNIQUE produit les deux ajouts.
        assertNotNull(edition)
    }

    @Test
    fun `definir un app_ avec xmlns present ne touche pas la racine`() {
        val avant = EditionAttributsXml.definir(formate, spanTexte(), "app:cornerRadius", "12dp")!!
        val resultat = appliquer(formate, avant)
        assertEquals(1, Regex("xmlns:app=").findAll(resultat).count())
    }

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

    // ── Échappement ──────────────────────────────────────────────────────

    @Test
    fun `definir echappe le texte saisi`() {
        val edition =
            EditionAttributsXml.definir(formate, spanTexte(), "android:text", "A&B<C>\"D")!!
        val resultat = appliquer(formate, edition)
        assertTrue(resultat.contains("android:text=\"A&amp;B&lt;C&gt;&quot;D\""))
    }

    @Test
    fun `definir echappe le arobase initial et les retours`() {
        val edition =
            EditionAttributsXml.definir(formate, spanTexte(), "android:text", "@pasUneReference")!!
        val resultat = appliquer(formate, edition)
        assertTrue(resultat.contains("android:text=\"\\@pasUneReference\""))
        val editionRetour =
            EditionAttributsXml.definir(formate, spanTexte(), "android:contentDescription", "L1\nL2")!!
        val resultatRetour = appliquer(formate, editionRetour)
        assertTrue(resultatRetour.contains("android:contentDescription=\"L1\\nL2\""))
    }

    // ── Refus X4 ─────────────────────────────────────────────────────────

    @Test
    fun `definir sur un span perime refuse null`() {
        assertNull(EditionAttributsXml.definir(formate, InsertionXml.SpanParent(3, 999), "android:text", "X"))
        assertNull(EditionAttributsXml.definir(formate, InsertionXml.SpanParent(0, 0), "android:text", "X"))
    }

    @Test
    fun `definir sur du texte malforme refuse null`() {
        assertNull(
            EditionAttributsXml.definir(
                "<TextView android:text=\"sans fin",
                InsertionXml.SpanParent(0, 28),
                "android:text",
                "X",
            ),
        )
    }

    @Test
    fun `ecrireCote une valeur negative refuse null`() {
        assertNull(
            EditionAttributsXml.ecrireCote(
                formate,
                spanTexte(),
                "android:padding",
                EditionAttributsXml.Cote.DEBUT,
                -1,
            ),
        )
    }

    @Test
    fun `definir couvre les valeurs unicode`() {
        val edition =
            EditionAttributsXml.definir(formate, spanTexte(), "android:text", "Déjà « vu » → ünïcode")!!
        val resultat = appliquer(formate, edition)
        assertTrue(resultat.contains("Déjà « vu » → ünïcode"))
    }

    @Test
    fun `definir ajoute sans corrompre la balise ancre`() {
        // RÉGRESSION PE8b : l'insertion après ancre partait de la FIN DU
        // NOM de l'attribut ancre — la valeur de l'ancre se soudait à
        // l'attribut inséré (document corrompu, jamais re-marché par les
        // tests PE7e). La plage couvre désormais l'ATTRIBUT COMPLET.
        val edition =
            EditionAttributsXml.definir(formate, spanTexte(), "android:textSize", "14sp")!!
        val resultat = appliquer(formate, edition)
        // L'ancre garde SA valeur soudée à SON nom.
        assertTrue(resultat.contains("""android:paddingHorizontal="16dp""""))
        assertFalse(resultat.contains("android:paddingHorizontal\n"))
        // Le document reste bien formé (re-marche de l'aligneur).
        assertEquals(3, AligneurElementsXml.aligner(resultat, null).size)
    }

    @Test
    fun `eclater un raccourci ne laisse pas de valeur residuelle`() {
        // Même régression, voie [ecrireCote] : le raccourci éclaté était
        // remplacé par son NOM seul — son ="16dp" restait en trailing.
        val edition =
            EditionAttributsXml.ecrireCote(
                formate,
                spanTexte(),
                "android:padding",
                EditionAttributsXml.Cote.DEBUT,
                8,
            )!!
        val resultat = appliquer(formate, edition)
        assertFalse("valeur résiduelle sans nom", resultat.contains("=\"16dp\"\n"))
        assertTrue(resultat.contains("""android:paddingStart="8dp""""))
        assertEquals(3, AligneurElementsXml.aligner(resultat, null).size)
    }
}
