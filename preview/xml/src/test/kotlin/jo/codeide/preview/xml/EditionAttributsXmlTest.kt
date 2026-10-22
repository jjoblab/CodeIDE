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

    // ── PE12c — retrait groupé (le bouton ↺ d'un groupe) ────────────────

    @Test
    fun `retirerAttributs retire plusieurs lignes entieres en une seule edition`() {
        // android:text et android:paddingHorizontal, seuls sur leurs
        // lignes : les DEUX lignes partent, UNE édition (un annuler).
        val edition =
            EditionAttributsXml.retirerAttributs(
                formate,
                spanTexte(),
                listOf("android:text", "android:paddingHorizontal"),
            )!!
        val resultat = appliquer(formate, edition)
        assertFalse(resultat.contains("android:text="))
        assertFalse(resultat.contains("android:paddingHorizontal"))
        assertFalse(resultat.contains("\n\n\n")) // pas de ligne vide résiduelle
        assertTrue(resultat.contains("android:id=\"@+id/titre\""))
        assertTrue(resultat.contains("<Button"))
    }

    @Test
    fun `retirerAttributs en ligne ne garde qu un separateur`() {
        // Deux attributs sur la MÊME ligne : le séparateur partagé ne part
        // qu'une fois (les plages fusionnent par chevauchement).
        val texte =
            "<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                "android:id=\"@+id/a\" android:text=\"X\" android:alpha=\"1\" />"
        val edition =
            EditionAttributsXml.retirerAttributs(texte, spanDe(texte, 0)!!, listOf("android:text", "android:alpha"))!!
        val resultat = appliquer(texte, edition)
        assertFalse(resultat.contains("android:text="))
        assertFalse(resultat.contains("android:alpha"))
        assertTrue(resultat.contains("android:id=\"@+id/a\" />"))
    }

    @Test
    fun `retirerAttributs ignore les absents et refuse null sans rien de defini`() {
        assertNull(EditionAttributsXml.retirerAttributs(formate, spanTexte(), listOf("android:alpha", "app:inconnu")))
        // Un groupe avec UN attribut défini part quand même en une édition.
        val edition =
            EditionAttributsXml.retirerAttributs(formate, spanTexte(), listOf("android:alpha", "android:text"))!!
        val resultat = appliquer(formate, edition)
        assertFalse(resultat.contains("android:text="))
        assertTrue(resultat.contains("android:paddingHorizontal"))
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

    // ── PE11g — correctif « Passer à 48 dp » (elargirCibleTactile) ──

    @Test
    fun `elargirCibleTactile elargit les deux dimensions fixes en une seule edition`() {
        // Miroir `v.w = Math.max(v.w, 48)` : 44 et 30 fixes → 48 et 48,
        // UNE édition atomique, le reste de la balise reste.
        val texte =
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <Button android:id="@+id/petit" android:layout_width="44dp" android:layout_height="30dp" />
</LinearLayout>
""".trimStart()
        val edition = EditionAttributsXml.elargirCibleTactile(texte, spanDe(texte, 1)!!)!!
        val resultat = appliquer(texte, edition)
        assertTrue("""android:layout_width="48dp"""" in resultat)
        assertTrue("""android:layout_height="48dp"""" in resultat)
        assertTrue("""android:id="@+id/petit"""" in resultat)
        assertFalse("la largeur 44 dp ne survit pas", """44dp""" in resultat)
        assertEquals("le document reste bien formé", 2, AligneurElementsXml.aligner(resultat, null).size)
    }

    @Test
    fun `elargirCibleTactile passe wrap_content aux minimums`() {
        // Miroir `setEx(minWidth, 48dp)` : wrap_content → minWidth ; la
        // hauteur FIXE déjà à 48 dp ne bouge pas (jamais de minHeight).
        val edition = EditionAttributsXml.elargirCibleTactile(formate, spanBouton())!!
        val resultat = appliquer(formate, edition)
        assertTrue("""android:minWidth="48dp"""" in resultat)
        assertFalse("pas de minHeight sur un axe déjà à 48 dp", """android:minHeight""" in resultat)
        assertTrue("""android:layout_height="48dp"""" in resultat)
        assertTrue("""android:layout_width="wrap_content"""" in resultat)
        assertEquals("le document reste bien formé", 3, AligneurElementsXml.aligner(resultat, null).size)
    }

    @Test
    fun `elargirCibleTactile garde le match constraint et pose les minimums`() {
        // `0dp` (match_constraint) n'est PAS une dimension fixe : jamais
        // réécrit en 48 dp — les DEUX minimums le portent.
        val texte =
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <Button android:layout_width="0dp" android:layout_height="wrap_content" />
</LinearLayout>
""".trimStart()
        val edition = EditionAttributsXml.elargirCibleTactile(texte, spanDe(texte, 1)!!)!!
        val resultat = appliquer(texte, edition)
        assertTrue("""android:layout_width="0dp"""" in resultat)
        assertTrue("""android:minWidth="48dp"""" in resultat)
        assertTrue("""android:minHeight="48dp"""" in resultat)
    }

    @Test
    fun `elargirCibleTactile sur cible deja large refuse null idempotence`() {
        // 96 × 48 dp : RIEN à élargir — le correctif n'écrit pas.
        val texte =
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <Button android:layout_width="96dp" android:layout_height="48dp" />
</LinearLayout>
""".trimStart()
        assertNull(
            "aucune édition attendue",
            EditionAttributsXml.elargirCibleTactile(texte, spanDe(texte, 1)!!),
        )
    }

    @Test
    fun `elargirCibleTactile n abaisse jamais un minimum pose`() {
        // wrap_content + minWidth=64dp : le minimum RESTE (jamais de
        // régression) ; seul l'axe sans minimum reçoit le sien.
        val texte =
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <Button android:layout_width="wrap_content" android:minWidth="64dp" android:layout_height="wrap_content" />
</LinearLayout>
""".trimStart()
        val edition = EditionAttributsXml.elargirCibleTactile(texte, spanDe(texte, 1)!!)!!
        val resultat = appliquer(texte, edition)
        assertTrue("""android:minWidth="64dp"""" in resultat)
        assertFalse("""android:minWidth="48dp"""" in resultat)
        assertTrue("""android:minHeight="48dp"""" in resultat)
    }

    @Test
    fun `elargirCibleTactile sur un span perime refuse null`() {
        assertNull(
            "span périmé : jamais d'exception (règle 6)",
            EditionAttributsXml.elargirCibleTactile(formate, InsertionXml.SpanParent(3, 7)),
        )
    }

    // ── PE11h — action rapide « Remplir l'espace restant » ───────────────

    @Test
    fun `remplirEspaceRestant pose poids et 0dp sur l axe en une seule edition`() {
        // Miroir le `items` du `quick` linéaire de la maquette :
        // `setEx(layout_weight, '1')` + `v.h = 'm'` sur un parent VERTICAL
        // (défaut) — la hauteur wrap_content devient 0dp, le poids absent
        // s'insère, UNE édition atomique, le reste de la balise reste.
        val edition =
            EditionAttributsXml.remplirEspaceRestant(
                formate,
                spanBouton(),
                EditionAttributsXml.Axe.HAUTEUR,
            )!!
        val resultat = appliquer(formate, edition)
        assertTrue("""android:layout_weight="1"""" in resultat)
        assertTrue("la hauteur calculée passe à 0dp", """android:layout_height="0dp"""" in resultat)
        assertFalse("la largeur ne bouge pas", """android:layout_width="0dp"""" in resultat)
        assertTrue("""android:id="@+id/button"""" in resultat)
        assertEquals("le document reste bien formé", 3, AligneurElementsXml.aligner(resultat, null).size)
    }

    @Test
    fun `remplirEspaceRestant sur l axe largeur remplace la valeur fixe existante`() {
        // Parent HORIZONTAL : c'est la LARGEUR qui devient 0dp (miroir
        // `if (par.orient === 'h') v.w = 'm'`), même fixe à 96 dp.
        val texte =
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="horizontal">
    <Button android:id="@+id/second" android:layout_width="96dp" android:layout_height="wrap_content" />
</LinearLayout>
""".trimStart()
        val edition =
            EditionAttributsXml.remplirEspaceRestant(
                texte,
                spanDe(texte, 1)!!,
                EditionAttributsXml.Axe.LARGEUR,
            )!!
        val resultat = appliquer(texte, edition)
        assertTrue("""android:layout_width="0dp"""" in resultat)
        assertFalse("""android:layout_width="96dp"""" in resultat)
        assertTrue("""android:layout_weight="1"""" in resultat)
        assertTrue("la hauteur ne bouge pas", """android:layout_height="wrap_content"""" in resultat)
    }

    @Test
    fun `remplirEspaceRestant deja rempli refuse null idempotence`() {
        // Poids 1 ET dimension 0dp déjà posés : le geste n'écrit rien —
        // le DRAPEAU décide, pas le delta.
        val texte =
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <Button android:layout_width="wrap_content" android:layout_height="0dp" android:layout_weight="1" />
</LinearLayout>
""".trimStart()
        assertNull(
            "aucune édition attendue",
            EditionAttributsXml.remplirEspaceRestant(texte, spanDe(texte, 1)!!, EditionAttributsXml.Axe.HAUTEUR),
        )
    }

    @Test
    fun `remplirEspaceRestant sur un span perime refuse null`() {
        assertNull(
            "span périmé : jamais d'exception (règle 6)",
            EditionAttributsXml.remplirEspaceRestant(
                formate,
                InsertionXml.SpanParent(3, 7),
                EditionAttributsXml.Axe.HAUTEUR,
            ),
        )
    }
}
