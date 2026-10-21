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
 * Les CINQ genres de famille de [ProblemesFamilles] (PE12f —
 * `docs/PREVIEW_PARENTS.md` § 9, miroir de la moitié famille de
 * `computeProblems` de la maquette) : orphelin, référence introuvable,
 * ancre invalide, comportement manquant, dépendance circulaire —
 * sévérités, données du message et du CORRECTIF, texte d'erreur de la
 * boucle (§ 12).
 *
 * Les documents sont de vrais petits XML (namespaces déclarés, comme
 * l'éditeur les écrit), lus par [ArbreAttributsXml.lire] — l'évaluation
 * et le lecteur testés ENSEMBLE, bout en bout. Sous Robolectric, comme
 * [RelationsParentsTest] : la VRAIE implémentation d'`XmlPullParser`
 * en JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ProblemesFamillesTest {
    // ── Documents sains : la règle se tait ──────────────────────────────

    @Test
    fun `familles - un RelativeLayout sain ne signale rien`() {
        val problemes =
            ProblemesFamilles.evaluer(
                document(
                    "RelativeLayout",
                    """
                    <ImageView android:id="@+id/avatar" android:layout_alignParentStart="true"
                        android:layout_alignParentTop="true" android:layout_width="80dp" android:layout_height="80dp"/>
                    <TextView android:id="@+id/nom" android:layout_toEndOf="@id/avatar"
                        android:layout_alignTop="@id/avatar" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                    <TextView android:id="@+id/role" android:layout_below="@id/nom"
                        android:layout_alignStart="@id/nom" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                    """.trimIndent(),
                ),
            )
        assertTrue("le document sain ne porte aucun problème de famille", problemes.isEmpty())
    }

    @Test
    fun `familles - un CoordinatorLayout sain ne signale rien`() {
        val problemes =
            ProblemesFamilles.evaluer(
                document(
                    "CoordinatorLayout",
                    """
                    <com.google.android.material.appbar.AppBarLayout android:id="@+id/appbar"
                        android:layout_width="match_parent" android:layout_height="wrap_content"/>
                    <androidx.recyclerview.widget.RecyclerView android:id="@+id/liste"
                        android:layout_width="match_parent" android:layout_height="match_parent"
                        app:layout_behavior="@string/appbar_scrolling_view_behavior"/>
                    <com.google.android.material.floatingactionbutton.FloatingActionButton android:id="@+id/fab"
                        android:layout_width="56dp" android:layout_height="56dp"
                        app:layout_anchor="@id/appbar" app:layout_anchorGravity="bottom|end"/>
                    """.trimIndent(),
                ),
            )
        assertTrue("barre + comportement posé + ancre valide : rien à signaler", problemes.isEmpty())
    }

    @Test
    fun `familles - document vide ou etranger - rien, jamais d exception`() {
        assertTrue(ProblemesFamilles.evaluer("").isEmpty())
        assertTrue(ProblemesFamilles.evaluer("pas du tout du XML").isEmpty())
    }

    // ── ATTRIBUT_ORPHELIN (avertissement) ───────────────────────────────

    @Test
    fun `orphelin - le poids sous un FrameLayout est signalé avec le libellé du parent`() {
        val problemes =
            ProblemesFamilles.evaluer(
                document(
                    "FrameLayout",
                    """<Button android:id="@+id/b" android:layout_weight="1"
                        android:layout_width="wrap_content" android:layout_height="wrap_content"/>""",
                ),
            )
        assertEquals(1, problemes.size)
        val probleme = problemes.first()
        assertEquals(ProblemesPreview.GenreProbleme.ATTRIBUT_ORPHELIN, probleme.genre)
        assertEquals(ProblemesPreview.Severite.AVERTISSEMENT, probleme.severite)
        assertEquals(listOf("android:layout_weight"), probleme.attributs)
        assertEquals("FrameLayout", probleme.libelleParent)
        assertEquals("Button", probleme.balise)
        assertEquals("b", probleme.id)
        assertTrue(
            "le correctif vit sur le span de l'élément fautif",
            probleme.debut > 0 && probleme.fin > probleme.debut,
        )
    }

    @Test
    fun `orphelin - un attribut hors table n est jamais signale`() {
        val problemes =
            ProblemesFamilles.evaluer(
                document(
                    "FrameLayout",
                    """<Button android:layout_marginStart="16dp" android:layout_gravity="center"
                        android:layout_width="wrap_content" android:layout_height="wrap_content"/>""",
                ),
            )
        assertTrue("marges et gravité du FrameLayout : rien", problemes.isEmpty())
    }

    @Test
    fun `orphelin - plusieurs orphelins en une seule entree, dans l ordre du document`() {
        val problemes =
            ProblemesFamilles.evaluer(
                document(
                    "RelativeLayout",
                    """<Button android:layout_weight="1" android:layout_gravity="center"
                        android:layout_width="wrap_content" android:layout_height="wrap_content"/>""",
                ),
            )
        assertEquals(1, problemes.size)
        // Poids ET gravité ne se comprennent pas sous un RelativeLayout :
        // UNE carte, TOUS les attributs (le correctif les retire ensemble).
        assertEquals(
            listOf("android:layout_weight", "android:layout_gravity"),
            problemes.first().attributs,
        )
        assertEquals("RelativeLayout", problemes.first().libelleParent)
    }

    @Test
    fun `orphelin - parent hors familles connues - la balise du parent est le libelle`() {
        val problemes =
            ProblemesFamilles.evaluer(
                document(
                    "GridLayout",
                    """<Button android:layout_weight="1"
                        android:layout_width="wrap_content" android:layout_height="wrap_content"/>""",
                ),
            )
        assertEquals(1, problemes.size)
        assertEquals("GridLayout", problemes.first().libelleParent)
    }

    // ── REFERENCE_INTROUVABLE (avertissement) ───────────────────────────

    @Test
    fun `reference - une regle vers un frere absent est signalee`() {
        val problemes =
            ProblemesFamilles.evaluer(
                document(
                    "RelativeLayout",
                    """<TextView android:id="@+id/nom" android:layout_below="@id/absent"
                        android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                    <TextView android:id="@+id/role" android:layout_width="wrap_content"
                        android:layout_height="wrap_content"/>""",
                ),
            )
        assertEquals(1, problemes.size)
        val probleme = problemes.first()
        assertEquals(ProblemesPreview.GenreProbleme.REFERENCE_INTROUVABLE, probleme.genre)
        assertEquals(ProblemesPreview.Severite.AVERTISSEMENT, probleme.severite)
        assertEquals(listOf("android:layout_below"), probleme.attributs)
        assertEquals("@id/absent", probleme.cible)
    }

    @Test
    fun `reference - une regle vers un frere present ou vers soi meme passe`() {
        val problemes =
            ProblemesFamilles.evaluer(
                document(
                    "RelativeLayout",
                    """<TextView android:id="@+id/nom" android:layout_below="@id/nom"
                        android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                    <TextView android:id="@+id/role" android:layout_below="@id/nom"
                        android:layout_width="wrap_content" android:layout_height="wrap_content"/>""",
                ),
            )
        // Vers SOI-MÊME : pas une référence introuvable (miroir la
        // maquette — la règle vers soi est ignorée, la vue est son frère).
        assertTrue(problemes.isEmpty())
    }

    // ── ANCRE_INVALIDE (erreur) ─────────────────────────────────────────

    @Test
    fun `ancre - inconnue, soi-meme, descendante ou hors du conteneur - quatre refus`() {
        val refus =
            listOf(
                // Une vue inconnue du document.
                """<Button android:id="@+id/b" app:layout_anchor="@id/nulle_part"
                    android:layout_width="wrap_content" android:layout_height="wrap_content"/>""",
                // La vue ancrée elle-même.
                """<Button android:id="@+id/b" app:layout_anchor="@id/b"
                    android:layout_width="wrap_content" android:layout_height="wrap_content"/>""",
                // Un DESCENDANT de la vue ancrée.
                """<FrameLayout android:id="@+id/b" app:layout_anchor="@id/interne"
                    android:layout_width="wrap_content" android:layout_height="wrap_content">
                    <TextView android:id="@+id/interne" android:layout_width="wrap_content"
                        android:layout_height="wrap_content"/>
                </FrameLayout>""",
                // Une vue du document HORS du CoordinatorLayout.
                """<Button android:id="@+id/b" app:layout_anchor="@id/hors"
                    android:layout_width="wrap_content" android:layout_height="wrap_content"/>""",
            )
        for (corps in refus) {
            val texte =
                "<FrameLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                    "xmlns:app=\"http://schemas.android.com/apk/res/auto\"><TextView android:id=\"@+id/hors\" " +
                    "android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\"/>" +
                    "<CoordinatorLayout>$corps</CoordinatorLayout></FrameLayout>"
            val problemes = ProblemesFamilles.evaluer(texte)
            assertEquals("l'ancre de ce document doit être refusée : $corps", 1, problemes.size)
            val probleme = problemes.first()
            assertEquals(ProblemesPreview.GenreProbleme.ANCRE_INVALIDE, probleme.genre)
            assertEquals(ProblemesPreview.Severite.ERREUR, probleme.severite)
            // Le correctif retire l'ancre ET sa gravité (spec § 9).
            assertEquals(
                listOf("app:layout_anchor", "app:layout_anchorGravity"),
                probleme.attributs,
            )
        }
    }

    @Test
    fun `ancre - une vue du sous-arbre du conteneur passe`() {
        val problemes =
            ProblemesFamilles.evaluer(
                document(
                    "CoordinatorLayout",
                    """
                    <TextView android:id="@+id/appbar" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                    <Button android:id="@+id/b" app:layout_anchor="@id/appbar" app:layout_anchorGravity="bottom|end"
                        android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                    """.trimIndent(),
                ),
            )
        assertTrue("une ancre valide vers un frère : rien", problemes.isEmpty())
    }

    // ── COMPORTEMENT_MANQUANT (avertissement) ───────────────────────────

    @Test
    fun `comportement - defilant sans comportement sous une barre presente`() {
        val problemes =
            ProblemesFamilles.evaluer(
                document(
                    "CoordinatorLayout",
                    """
                    <com.google.android.material.appbar.AppBarLayout android:id="@+id/appbar"
                        android:layout_width="match_parent" android:layout_height="wrap_content"/>
                    <androidx.recyclerview.widget.RecyclerView android:id="@+id/liste"
                        android:layout_width="match_parent" android:layout_height="match_parent"/>
                    """.trimIndent(),
                ),
            )
        assertEquals(1, problemes.size)
        val probleme = problemes.first()
        assertEquals(ProblemesPreview.GenreProbleme.COMPORTEMENT_MANQUANT, probleme.genre)
        assertEquals(ProblemesPreview.Severite.AVERTISSEMENT, probleme.severite)
        // Pas d'attribut à RETIRER : le correctif en POSE un.
        assertTrue(probleme.attributs.isEmpty())
        assertEquals("RecyclerView", probleme.balise.substringAfterLast('.'))
    }

    @Test
    fun `comportement - avec comportement ou sans barre - rien`() {
        val avecComportement =
            document(
                "CoordinatorLayout",
                """
                <com.google.android.material.appbar.AppBarLayout android:layout_width="match_parent" android:layout_height="wrap_content"/>
                <androidx.recyclerview.widget.RecyclerView android:layout_width="match_parent" android:layout_height="match_parent"
                    app:layout_behavior="@string/appbar_scrolling_view_behavior"/>
                """.trimIndent(),
            )
        val sansBarre =
            document(
                "CoordinatorLayout",
                """<androidx.recyclerview.widget.RecyclerView android:layout_width="match_parent"
                    android:layout_height="match_parent"/>""",
            )
        assertTrue(ProblemesFamilles.evaluer(avecComportement).isEmpty())
        assertTrue(ProblemesFamilles.evaluer(sansBarre).isEmpty())
    }

    @Test
    fun `comportement - un ScrollView compte comme defilant`() {
        val problemes =
            ProblemesFamilles.evaluer(
                document(
                    "CoordinatorLayout",
                    """
                    <com.google.android.material.appbar.AppBarLayout android:layout_width="match_parent" android:layout_height="wrap_content"/>
                    <androidx.core.widget.NestedScrollView android:layout_width="match_parent" android:layout_height="match_parent"/>
                    """.trimIndent(),
                ),
            )
        assertEquals(1, problemes.size)
        assertEquals(ProblemesPreview.GenreProbleme.COMPORTEMENT_MANQUANT, problemes.first().genre)
    }

    // ── DEPENDANCE_CIRCULAIRE (erreur) ──────────────────────────────────

    @Test
    fun `boucle - cycle a deux, premiere vue en tete, regle a casser`() {
        val texte =
            document(
                "RelativeLayout",
                """
                <TextView android:id="@+id/nom" android:layout_below="@id/role"
                    android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                <TextView android:id="@+id/role" android:layout_below="@id/nom"
                    android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                """.trimIndent(),
            )
        val problemes = ProblemesFamilles.evaluer(texte)
        assertEquals(1, problemes.size)
        val probleme = problemes.first()
        assertEquals(ProblemesPreview.GenreProbleme.DEPENDANCE_CIRCULAIRE, probleme.genre)
        assertEquals(ProblemesPreview.Severite.ERREUR, probleme.severite)
        // Le chemin FERMÉ, première vue en tête, retour sur elle-même (§ 12).
        assertEquals(listOf("nom", "role", "nom"), probleme.cycle)
        // Rattachée à la PREMIÈRE vue du cycle : nom, et son identité.
        assertEquals("nom", probleme.id)
        assertEquals("TextView", probleme.balise)
        // Le correctif casse la boucle par la règle de nom qui vise role.
        assertEquals(listOf("android:layout_below"), probleme.attributs)
        assertEquals("RelativeLayout", probleme.conteneur)
    }

    @Test
    fun `boucle - le conteneur sans identifiant se nomme par sa balise`() {
        val problemes =
            ProblemesFamilles.evaluer(
                document(
                    "RelativeLayout",
                    """
                    <TextView android:id="@+id/nom" android:layout_below="@id/role"
                        android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                    <TextView android:id="@+id/role" android:layout_below="@id/nom"
                        android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                    """.trimIndent(),
                ),
            )
        assertEquals("RelativeLayout", problemes.first().conteneur)
    }

    @Test
    fun `boucle - sans boucle, boucleSurTexte repond null`() {
        assertNull(ProblemesFamilles.boucleSurTexte(document("RelativeLayout", fauxCorpsSain)))
        val avecBoucle =
            document(
                "RelativeLayout",
                """
                <TextView android:id="@+id/nom" android:layout_below="@id/role"
                    android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                <TextView android:id="@+id/role" android:layout_below="@id/nom"
                    android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                """.trimIndent(),
            )
        assertEquals(
            ProblemesPreview.GenreProbleme.DEPENDANCE_CIRCULAIRE,
            ProblemesFamilles.boucleSurTexte(avecBoucle)?.genre,
        )
    }

    @Test
    fun `boucle - le texte d erreur du rendu porte la signature de l exception`() {
        // Le message que LayoutLib remonte (spec § 12) — préfixé ou nu.
        assertTrue(
            ProblemesFamilles.estBoucleRelativeLayout(
                "java.lang.IllegalStateException: Circular dependencies cannot exist in RelativeLayout",
            ),
        )
        assertTrue(
            ProblemesFamilles.estBoucleRelativeLayout("Circular dependencies cannot exist in RelativeLayout"),
        )
        assertFalse(ProblemesFamilles.estBoucleRelativeLayout("android.view.InflateException: Binary XML file line #7"))
        assertFalse(ProblemesFamilles.estBoucleRelativeLayout(""))
    }

    // ── L'ordre des cinq genres, puis les CORRECTIFS (une édition chacun) ──

    @Test
    fun `familles - l ordre suit le document, la boucle en dernier`() {
        val texte =
            "<RelativeLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                "xmlns:app=\"http://schemas.android.com/apk/res/auto\" android:id=\"@+id/root\" " +
                "android:layout_width=\"match_parent\" android:layout_height=\"match_parent\">" +
                orphelinEtReference +
                coordonnateurAvecAncreEtDefilement +
                cycleADeux +
                "</RelativeLayout>"
        assertEquals(
            listOf(
                ProblemesPreview.GenreProbleme.ATTRIBUT_ORPHELIN,
                ProblemesPreview.GenreProbleme.REFERENCE_INTROUVABLE,
                ProblemesPreview.GenreProbleme.COMPORTEMENT_MANQUANT,
                ProblemesPreview.GenreProbleme.ANCRE_INVALIDE,
                ProblemesPreview.GenreProbleme.DEPENDANCE_CIRCULAIRE,
            ),
            ProblemesFamilles.evaluer(texte).map { it.genre },
        )
    }

    @Test
    fun `correctif - les orphelins partent ensemble en une seule edition`() {
        val texte =
            document(
                "RelativeLayout",
                orphelinEtReference,
            )
        val probleme =
            ProblemesFamilles
                .evaluer(texte)
                .first { it.genre == ProblemesPreview.GenreProbleme.ATTRIBUT_ORPHELIN }
        val edition =
            EditionAttributsXml.retirerAttributs(
                texte,
                InsertionXml.SpanParent(probleme.debut, probleme.fin),
                probleme.attributs,
            )
        assertTrue("l'édition doit exister", edition != null)
        val corrige = texte.replaceRange(edition!!.debut, edition.fin, edition.remplacement)
        assertFalse("le poids doit partir", "layout_weight" in corrige)
        assertFalse("la gravité doit partir", "layout_gravity" in corrige)
        assertTrue("la règle vers le frère absent reste (UN problème = UN correctif)", "layout_below" in corrige)
    }

    @Test
    fun `correctif - le comportement pose app bar scrolling view behavior`() {
        val texte =
            document(
                "CoordinatorLayout",
                """
                <com.google.android.material.appbar.AppBarLayout android:id="@+id/appbar"
                    android:layout_width="match_parent" android:layout_height="wrap_content"/>
                <androidx.recyclerview.widget.RecyclerView android:id="@+id/liste"
                    android:layout_width="match_parent" android:layout_height="match_parent"/>
                """.trimIndent(),
            )
        val probleme = ProblemesFamilles.evaluer(texte).first()
        val edition =
            EditionAttributsXml.definirReference(
                texte,
                InsertionXml.SpanParent(probleme.debut, probleme.fin),
                "app:layout_behavior",
                ProblemesFamilles.COMPORTEMENT_DEFILEMENT,
            )
        assertTrue(edition != null)
        val corrige = texte.replaceRange(edition!!.debut, edition.fin, edition.remplacement)
        assertTrue(
            "le comportement de défilement doit être posé sur la liste",
            "app:layout_behavior=\"@string/appbar_scrolling_view_behavior\"" in corrige,
        )
        // Le document corrigé ne signale PLUS le problème.
        assertTrue(ProblemesFamilles.evaluer(corrige).isEmpty())
    }

    @Test
    fun `correctif - casser la boucle retablit un document sain`() {
        val texte =
            document(
                "RelativeLayout",
                cycleADeux,
            )
        val probleme = ProblemesFamilles.boucleSurTexte(texte) ?: error("la boucle doit exister")
        val edition =
            EditionAttributsXml.retirerAttributs(
                texte,
                InsertionXml.SpanParent(probleme.debut, probleme.fin),
                probleme.attributs,
            )
        assertTrue(edition != null)
        val corrige = texte.replaceRange(edition!!.debut, edition.fin, edition.remplacement)
        assertTrue("le document cassé ne signale plus rien", ProblemesFamilles.evaluer(corrige).isEmpty())
    }

    // ── Fabriques : de vrais petits documents, comme l'éditeur les écrit ──

    private fun document(
        balise: String,
        corps: String,
    ): String =
        "<$balise xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
            "xmlns:app=\"http://schemas.android.com/apk/res/auto\" " +
            "android:layout_width=\"match_parent\" android:layout_height=\"match_parent\">$corps</$balise>"

    /** Un bouton dont le poids et la gravité sont orphelins, et dont la règle vise un frère absent. */
    private val orphelinEtReference: String =
        "<Button android:id=\"@+id/b\" android:layout_weight=\"1\" android:layout_gravity=\"center\" " +
            "android:layout_below=\"@id/absent\" android:layout_width=\"wrap_content\" " +
            "android:layout_height=\"wrap_content\"/>"

    /** Un CoordinatorLayout imbriqué : barre présente, liste sans comportement, ancre inconnue. */
    private val coordonnateurAvecAncreEtDefilement: String =
        "<CoordinatorLayout android:layout_width=\"match_parent\" android:layout_height=\"200dp\">" +
            "<com.google.android.material.appbar.AppBarLayout android:layout_width=\"match_parent\" " +
            "android:layout_height=\"wrap_content\"/>" +
            "<androidx.recyclerview.widget.RecyclerView android:id=\"@+id/liste\" " +
            "android:layout_width=\"match_parent\" android:layout_height=\"match_parent\"/>" +
            "<Button android:id=\"@+id/ancre\" app:layout_anchor=\"@id/nulle_part\" " +
            "android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\"/>" +
            "</CoordinatorLayout>"

    /** Deux vues qui se mettent mutuellement dessous : la boucle à deux du critère § 6.5. */
    private val cycleADeux: String =
        "<TextView android:id=\"@+id/nom\" android:layout_below=\"@id/role\" " +
            "android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\"/>" +
            "<TextView android:id=\"@+id/role\" android:layout_below=\"@id/nom\" " +
            "android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\"/>"

    /** Un corps sain pour les contrastes. */
    private val fauxCorpsSain: String =
        "<TextView android:id=\"@+id/nom\" " +
            "android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\"/>"
}
