package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Les RELATIONS D'UN PARENT (PE12b — `docs/PREVIEW_PARENTS.md` § 4.2,
 * miroir maquette `relEdges` / `relCycle` / `wouldCycle` / `FAM_ATTRS`,
 * § 16) : une arête par règle de côté, par alignement, par ancre ; les
 * cycles direct et à trois ; [RelationsParents.fermeraitBoucle] ; les
 * orphelins par famille.
 *
 * Les parents viennent d'[ArbreAttributsXml.lire] sur de vrais petits
 * documents (namespaces déclarés, comme l'éditeur les écrit) — le
 * lecteur et le calcul des arêtes sont testés ENSEMBLE, bout en bout.
 * Sous Robolectric, comme [AligneurElementsXmlTest] : la VRAIE
 * implémentation d'`XmlPullParser` en JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class RelationsParentsTest {
    // ── Arêtes : règles vers un frère (une par règle de CÔTÉ) ──────────

    @Test
    fun `relations - une arete par regle de cote`() {
        for (regle in listOf("below", "above", "toStartOf", "toEndOf", "toLeftOf", "toRightOf")) {
            val parent =
                parentRelatif(
                    "<Button android:id=\"@+id/a\" android:layout_$regle=\"@id/b\"/><Button android:id=\"@+id/b\"/>",
                )
            assertEquals(
                "la règle $regle doit donner une arête vers le frère",
                listOf(
                    RelationsParents.Relation(
                        "a",
                        "b",
                        "android:layout_$regle",
                        RelationsParents.TypeRelation.REGLE_FRERE,
                    ),
                ),
                RelationsParents.relations(parent),
            )
        }
    }

    // ── Arêtes : règles vers un frère (une par ALIGNEMENT) ─────────────

    @Test
    fun `relations - une arete par alignement`() {
        for (regle in listOf(
            "alignTop",
            "alignBottom",
            "alignStart",
            "alignEnd",
            "alignLeft",
            "alignRight",
            "alignBaseline",
        )) {
            val parent =
                parentRelatif(
                    "<Button android:id=\"@+id/a\" android:layout_$regle=\"@id/b\"/><Button android:id=\"@+id/b\"/>",
                )
            assertEquals(
                "l'alignement $regle doit donner une arête vers le frère",
                listOf(
                    RelationsParents.Relation(
                        "a",
                        "b",
                        "android:layout_$regle",
                        RelationsParents.TypeRelation.REGLE_FRERE,
                    ),
                ),
                RelationsParents.relations(parent),
            )
        }
    }

    @Test
    fun `relations - une regle vers un frere inconnu ou vers soi-meme n'est pas une arete`() {
        val parent =
            parentRelatif(
                "<Button android:id=\"@+id/a\" android:layout_below=\"@id/fantome\" " +
                    "android:layout_above=\"@id/a\"/>" +
                    "<Button android:id=\"@+id/b\"/>",
            )
        assertTrue(RelationsParents.relations(parent).isEmpty())
    }

    // ── Arêtes : drapeaux vers le parent ───────────────────────────────

    @Test
    fun `relations - un drapeau vers le parent vaut true seulement`() {
        val drapeaux =
            listOf(
                "alignParentStart",
                "alignParentEnd",
                "alignParentLeft",
                "alignParentRight",
                "alignParentTop",
                "alignParentBottom",
                "centerInParent",
                "centerHorizontal",
                "centerVertical",
            )
        for (drapeau in drapeaux) {
            val parent = parentRelatif("<Button android:id=\"@+id/a\" android:layout_$drapeau=\"true\"/>")
            assertEquals(
                "le drapeau $drapeau à true doit viser le parent (cible nulle)",
                listOf(
                    RelationsParents.Relation(
                        "a",
                        null,
                        "android:layout_$drapeau",
                        RelationsParents.TypeRelation.REGLE_PARENT,
                    ),
                ),
                RelationsParents.relations(parent),
            )
        }
        val faux = parentRelatif("<Button android:id=\"@+id/a\" android:layout_centerInParent=\"false\"/>")
        assertTrue(RelationsParents.relations(faux).isEmpty())
    }

    // ── Arêtes : ancre (une par ANCRE) ─────────────────────────────────

    @Test
    fun `relations - l'ancre vise une vue du sous-arbre hors la vue et sa descendance`() {
        // Une ancre vers un FRÈRE direct : arête.
        val versFrere =
            parentCoordonnateur(
                "<Button android:id=\"@+id/a\" app:layout_anchor=\"@id/b\"/>" +
                    "<Button android:id=\"@+id/b\"/>",
            )
        assertEquals(
            listOf(RelationsParents.Relation("a", "b", "app:layout_anchor", RelationsParents.TypeRelation.ANCRE)),
            RelationsParents.relations(versFrere),
        )
        // Une ancre vers un PETIT-ENFANT du conteneur : valide aussi
        // (l'ancre cherche dans tout le sous-arbre, spec § 3.3).
        val versPetitEnfant =
            parentCoordonnateur(
                "<Button android:id=\"@+id/a\" app:layout_anchor=\"@id/cible\"/>" +
                    "<LinearLayout><Button android:id=\"@+id/cible\"/></LinearLayout>",
            )
        assertEquals(
            listOf(RelationsParents.Relation("a", "cible", "app:layout_anchor", RelationsParents.TypeRelation.ANCRE)),
            RelationsParents.relations(versPetitEnfant),
        )
        // Vers soi-même, vers son PROPRE descendant, vers l'inconnu :
        // jamais d'arête (ce sont des ancres INVALIDES, spec § 9 —
        // elles ne dessinent ni ne relient rien).
        val invalide =
            parentCoordonnateur(
                "<LinearLayout android:id=\"@+id/pere\" app:layout_anchor=\"@id/fils\">" +
                    "<Button android:id=\"@+id/fils\"/></LinearLayout>" +
                    "<Button android:id=\"@+id/b\" app:layout_anchor=\"@id/inconnu\"/>" +
                    "<Button android:id=\"@+id/c\" app:layout_anchor=\"@id/c\"/>",
            )
        assertTrue(RelationsParents.relations(invalide).isEmpty())
    }

    // ── Arêtes : contraintes ───────────────────────────────────────────

    @Test
    fun `relations - les contraintes qui visent seulement`() {
        val parent =
            lireParent(
                "ConstraintLayout",
                "<Button android:id=\"@+id/a\" app:layout_constraintTop_toTopOf=\"@id/b\" " +
                    "app:layout_constraintStart_toStartOf=\"parent\" " +
                    "app:layout_constraintWidth_min=\"32dp\"/>" +
                    "<Button android:id=\"@+id/b\"/>",
            )
        assertEquals(
            listOf(
                RelationsParents.Relation(
                    "a",
                    "b",
                    "app:layout_constraintTop_toTopOf",
                    RelationsParents.TypeRelation.CONTRAINTE,
                ),
                RelationsParents.Relation(
                    "a",
                    null,
                    "app:layout_constraintStart_toStartOf",
                    RelationsParents.TypeRelation.CONTRAINTE,
                ),
            ),
            RelationsParents.relations(parent),
        )
    }

    // ── Cycles ─────────────────────────────────────────────────────────

    @Test
    fun `cycle - direct`() {
        val parent =
            parentRelatif(
                "<Button android:id=\"@+id/a\" android:layout_below=\"@id/b\"/>" +
                    "<Button android:id=\"@+id/b\" android:layout_above=\"@id/a\"/>",
            )
        assertEquals(listOf("a", "b", "a"), RelationsParents.cycle(parent))
    }

    @Test
    fun `cycle - a trois`() {
        val parent =
            parentRelatif(
                "<Button android:id=\"@+id/a\" android:layout_below=\"@id/b\"/>" +
                    "<Button android:id=\"@+id/b\" android:layout_below=\"@id/c\"/>" +
                    "<Button android:id=\"@+id/c\" android:layout_above=\"@id/a\"/>",
            )
        assertEquals(listOf("a", "b", "c", "a"), RelationsParents.cycle(parent))
    }

    @Test
    fun `cycle - pas de cycle`() {
        val parent =
            parentRelatif(
                "<Button android:id=\"@+id/a\" android:layout_below=\"@id/b\"/>" +
                    "<Button android:id=\"@+id/b\" android:layout_below=\"@id/c\"/>" +
                    "<Button android:id=\"@+id/c\"/>",
            )
        assertNull(RelationsParents.cycle(parent))
    }

    @Test
    fun `cycle - ni frere inconnu ni vue sans identifiant ne ferment de boucle`() {
        val parent =
            parentRelatif(
                "<Button android:id=\"@+id/a\" android:layout_below=\"@id/fantome\"/>" +
                    "<Button/>",
            )
        assertNull(RelationsParents.cycle(parent))
    }

    @Test
    fun `cycle - le premier enfant du document ouvre le chemin`() {
        val parent =
            parentRelatif(
                "<Button android:id=\"@+id/b\" android:layout_above=\"@id/a\"/>" +
                    "<Button android:id=\"@+id/a\" android:layout_below=\"@id/b\"/>",
            )
        assertEquals(listOf("b", "a", "b"), RelationsParents.cycle(parent))
    }

    // ── fermeraitBoucle ────────────────────────────────────────────────

    @Test
    fun `fermeraitBoucle - une cible sans chemin de retour ne ferme rien`() {
        val parent =
            parentRelatif(
                "<Button android:id=\"@+id/a\" android:layout_below=\"@id/b\"/>" +
                    "<Button android:id=\"@+id/b\"/>",
            )
        assertTrue(!RelationsParents.fermeraitBoucle(parent, "a", "android:layout_below", "b"))
    }

    @Test
    fun `fermeraitBoucle - la boucle a trois se ferme par le dernier maillon`() {
        val parent =
            parentRelatif(
                "<Button android:id=\"@+id/a\" android:layout_below=\"@id/b\"/>" +
                    "<Button android:id=\"@+id/b\" android:layout_below=\"@id/c\"/>" +
                    "<Button android:id=\"@+id/c\"/>",
            )
        // c → a : a → b → c referme la boucle (nom court toléré).
        assertTrue(RelationsParents.fermeraitBoucle(parent, "c", "below", "a"))
        // c → b : b → c referme la boucle.
        assertTrue(RelationsParents.fermeraitBoucle(parent, "c", "android:layout_below", "b"))
        // Rediriger a de b vers c ne referme RIEN (c n'a pas de chemin vers a).
        assertTrue(!RelationsParents.fermeraitBoucle(parent, "a", "android:layout_below", "c"))
    }

    @Test
    fun `fermeraitBoucle - seules les regles vers un frere ferment une boucle`() {
        val parent = parentRelatif("<Button android:id=\"@+id/a\"/>")
        assertTrue(!RelationsParents.fermeraitBoucle(parent, "a", "android:layout_alignParentStart", "a"))
    }

    @Test
    fun `fermeraitBoucle - une regle vers soi-meme est une boucle`() {
        val parent = parentRelatif("<Button android:id=\"@+id/a\"/><Button android:id=\"@+id/b\"/>")
        assertTrue(RelationsParents.fermeraitBoucle(parent, "a", "android:layout_below", "a"))
    }

    // ── Attributs orphelins (spec § 9 — une par famille) ───────────────

    @Test
    fun `orphelins - les regles de RelativeLayout n'appartiennent qu'a un RelativeLayout`() {
        val attributs = mapOf("android:layout_below" to "@id/b")
        assertEquals(emptyList<String>(), RelationsParents.attributsOrphelins(FamilleParent.RELATIVE, attributs))
        assertEquals(
            listOf("android:layout_below"),
            RelationsParents.attributsOrphelins(FamilleParent.LINEAR, attributs),
        )
        assertEquals(
            listOf("android:layout_below"),
            RelationsParents.attributsOrphelins(FamilleParent.CONSTRAINT, attributs),
        )
    }

    @Test
    fun `orphelins - le poids se comprend en lineaire et barre d'application`() {
        val attributs = mapOf("android:layout_weight" to "1")
        assertEquals(emptyList<String>(), RelationsParents.attributsOrphelins(FamilleParent.LINEAR, attributs))
        assertEquals(emptyList<String>(), RelationsParents.attributsOrphelins(FamilleParent.APPBAR, attributs))
        assertEquals(
            listOf("android:layout_weight"),
            RelationsParents.attributsOrphelins(FamilleParent.FRAME, attributs),
        )
        assertEquals(
            listOf("android:layout_weight"),
            RelationsParents.attributsOrphelins(FamilleParent.CONSTRAINT, attributs),
        )
    }

    @Test
    fun `orphelins - la gravite se comprend partout sauf relative et contrainte`() {
        val attributs = mapOf("android:layout_gravity" to "center")
        for (famille in listOf(
            FamilleParent.LINEAR,
            FamilleParent.APPBAR,
            FamilleParent.FRAME,
            FamilleParent.COORDINATOR,
        )) {
            assertEquals(
                "la famille $famille comprend layout_gravity",
                emptyList<String>(),
                RelationsParents.attributsOrphelins(famille, attributs),
            )
        }
        assertEquals(
            listOf("android:layout_gravity"),
            RelationsParents.attributsOrphelins(FamilleParent.RELATIVE, attributs),
        )
        assertEquals(
            listOf("android:layout_gravity"),
            RelationsParents.attributsOrphelins(FamilleParent.CONSTRAINT, attributs),
        )
    }

    @Test
    fun `orphelins - l'ancre et le comportement n'appartiennent qu'au CoordinatorLayout`() {
        val attributs =
            mapOf(
                "app:layout_anchor" to "@id/b",
                "app:layout_behavior" to "@string/appbar_scrolling_view_behavior",
            )
        assertEquals(emptyList<String>(), RelationsParents.attributsOrphelins(FamilleParent.COORDINATOR, attributs))
        assertEquals(attributs.keys.toList(), RelationsParents.attributsOrphelins(FamilleParent.RELATIVE, attributs))
    }

    @Test
    fun `orphelins - les drapeaux de defilement n'appartiennent qu'a la barre d'application`() {
        val attributs = mapOf("app:layout_scrollFlags" to "scroll", "app:layout_scrollInterpolator" to "@anim/linear")
        assertEquals(emptyList<String>(), RelationsParents.attributsOrphelins(FamilleParent.APPBAR, attributs))
        assertEquals(attributs.keys.toList(), RelationsParents.attributsOrphelins(FamilleParent.COORDINATOR, attributs))
    }

    @Test
    fun `orphelins - les contraintes n'appartiennent qu'au ConstraintLayout`() {
        val attributs = mapOf("app:layout_constraintTop_toTopOf" to "parent")
        assertEquals(emptyList<String>(), RelationsParents.attributsOrphelins(FamilleParent.CONSTRAINT, attributs))
        assertEquals(
            listOf("app:layout_constraintTop_toTopOf"),
            RelationsParents.attributsOrphelins(FamilleParent.LINEAR, attributs),
        )
    }

    @Test
    fun `orphelins - un attribut hors table n'est jamais signale`() {
        val attributs =
            mapOf(
                "android:layout_marginStart" to "16dp",
                "android:layout_width" to "match_parent",
                "android:layout_height" to "wrap_content",
                "android:gravity" to "center",
                "app:title" to "@string/titre",
            )
        for (famille in FamilleParent.entries) {
            assertEquals(
                "la famille $famille ne signale jamais un attribut hors table",
                emptyList<String>(),
                RelationsParents.attributsOrphelins(famille, attributs),
            )
        }
    }

    @Test
    fun `orphelins - l'ordre suit le document`() {
        assertEquals(
            listOf("android:layout_below", "app:layout_anchor"),
            RelationsParents.attributsOrphelins(
                FamilleParent.LINEAR,
                linkedMapOf(
                    "android:layout_below" to "@id/b",
                    "android:layout_width" to "match_parent",
                    "app:layout_anchor" to "@id/x",
                ),
            ),
        )
    }

    // ── Fabriques : de vrais petits documents, lus par ArbreAttributsXml ──

    private fun parentRelatif(corps: String): ArbreAttributsXml.Noeud = lireParent("RelativeLayout", corps)

    private fun parentCoordonnateur(corps: String): ArbreAttributsXml.Noeud = lireParent("CoordinatorLayout", corps)

    private fun lireParent(balise: String, corps: String): ArbreAttributsXml.Noeud {
        val texte =
            "<$balise xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                "xmlns:app=\"http://schemas.android.com/apk/res/auto\">$corps</$balise>"
        return ArbreAttributsXml.lire(texte).first()
    }
}
