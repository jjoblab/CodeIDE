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
 * Les CONTRAINTES DE HIÉRARCHIE (v0.170.0, S3 — [ContraintesHierarchie]) :
 * les règles de CONTENANCE de la plateforme (miroir lint
 * `AdapterViewChildren`/`ScrollViewChildren`) — capacités par balise,
 * « Dedans » du popup et remontée du dépôt honorant la capacité, genres
 * évalués sur le TEXTE ([ProblemesFamilles]), et les CORRECTIFS
 * (envelopper les enfants d'un enfant-unique dépassé, retirer les
 * enfants fautifs d'une feuille, d'un adaptateur ou d'un conteneur à
 * enfant attendu).
 *
 * [ContraintesHierarchie] est PUR (zéro Android) mais les éditions
 * relisent le document par [ArbreAttributsXml.lire] : sous Robolectric,
 * comme [ProblemesFamillesTest] — la VRAIE implémentation
 * d'`XmlPullParser` en JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ContraintesHierarchieTest {
    // ── Capacités par balise ────────────────────────────────────────────

    @Test
    fun `capacite - les conteneurs a enfant unique`() {
        assertEquals(ContraintesHierarchie.CapaciteEnfants.UN_SEUL, ContraintesHierarchie.capacite("ScrollView"))
        assertEquals(
            ContraintesHierarchie.CapaciteEnfants.UN_SEUL,
            ContraintesHierarchie.capacite("HorizontalScrollView"),
        )
        assertEquals(ContraintesHierarchie.CapaciteEnfants.UN_SEUL, ContraintesHierarchie.capacite("NestedScrollView"))
        assertEquals(
            ContraintesHierarchie.CapaciteEnfants.UN_SEUL,
            ContraintesHierarchie.capacite("SwipeRefreshLayout"),
        )
        // Le suffixe attrape les sous-classes custom des dépôts réels.
        assertEquals(ContraintesHierarchie.CapaciteEnfants.UN_SEUL, ContraintesHierarchie.capacite("MonScrollView"))
        // Le nom QUALIFIÉ vaut le nom local.
        assertEquals(
            ContraintesHierarchie.CapaciteEnfants.UN_SEUL,
            ContraintesHierarchie.capacite("androidx.core.widget.NestedScrollView"),
        )
    }

    @Test
    fun `capacite - les conteneurs a adaptateur n acceptent aucun enfant`() {
        listOf(
            "ListView",
            "GridView",
            "ExpandableListView",
            "Spinner",
            "Gallery",
            "AdapterViewFlipper",
            "RecyclerView",
            "ViewPager",
            "ViewPager2",
        ).forEach { balise ->
            assertEquals(
                "capacity de $balise",
                ContraintesHierarchie.CapaciteEnfants.ADAPTATEUR,
                ContraintesHierarchie.capacite(balise),
            )
        }
        assertEquals(
            ContraintesHierarchie.CapaciteEnfants.ADAPTATEUR,
            ContraintesHierarchie.capacite("androidx.recyclerview.widget.RecyclerView"),
        )
    }

    @Test
    fun `capacite - les conteneurs a enfant attendu et le defaut permissif`() {
        assertEquals(ContraintesHierarchie.CapaciteEnfants.ATTENDU, ContraintesHierarchie.capacite("TabLayout"))
        assertEquals(ContraintesHierarchie.CapaciteEnfants.ATTENDU, ContraintesHierarchie.capacite("TextInputLayout"))
        assertEquals(ContraintesHierarchie.CapaciteEnfants.ATTENDU, ContraintesHierarchie.capacite("ChipGroup"))
        assertEquals(
            ContraintesHierarchie.CapaciteEnfants.ATTENDU,
            ContraintesHierarchie.capacite("MaterialButtonToggleGroup"),
        )
        assertEquals(
            "l'inconnu reste permissif",
            ContraintesHierarchie.CapaciteEnfants.PLUSIEURS,
            ContraintesHierarchie.capacite("MockView"),
        )
        assertEquals(ContraintesHierarchie.CapaciteEnfants.PLUSIEURS, ContraintesHierarchie.capacite("MaVueCustom"))
        assertEquals(ContraintesHierarchie.CapaciteEnfants.PLUSIEURS, ContraintesHierarchie.capacite("LinearLayout"))
    }

    @Test
    fun `accepteEnfant - un enfant de plus selon la capacite`() {
        // PLUSIEURS : toujours, même à trois enfants.
        assertTrue(ContraintesHierarchie.accepteEnfant("LinearLayout", 3))
        // UN_SEUL : seulement à vide.
        assertTrue(ContraintesHierarchie.accepteEnfant("ScrollView", 0))
        assertFalse(ContraintesHierarchie.accepteEnfant("ScrollView", 1))
        assertFalse(ContraintesHierarchie.accepteEnfant("NestedScrollView", 2))
        // ADAPTATEUR : jamais (l'adaptateur remplace le XML).
        assertFalse(ContraintesHierarchie.accepteEnfant("RecyclerView", 0))
        assertFalse(ContraintesHierarchie.accepteEnfant("ListView", 0))
        // ATTENDU : jamais depuis le catalogue (pas de TabItem au menu).
        assertFalse(ContraintesHierarchie.accepteEnfant("TabLayout", 0))
        assertFalse(ContraintesHierarchie.accepteEnfant("TextInputLayout", 0))
        // FEUILLE connue : jamais, même par la garde en profondeur.
        assertFalse(ContraintesHierarchie.accepteEnfant("TextView", 0))
        assertFalse(ContraintesHierarchie.accepteEnfant("Chip", 0))
    }

    @Test
    fun `feuilles connues - suffixes sûrs, inconnu jamais feuille`() {
        listOf(
            "TextView",
            "CheckedTextView",
            "EditText",
            "TextInputEditText",
            "AutoCompleteTextView",
            "Button",
            "MaterialButton",
            "ToggleButton",
            "RadioButton",
            "CheckBox",
            "Switch",
            "SwitchCompat",
            "MaterialSwitch",
            "Chip",
            "FloatingActionButton",
            "ImageView",
            "View",
            "Space",
            "Guideline",
            "Chronometer",
        ).forEach { balise ->
            assertTrue("$balise est une feuille connue", ContraintesHierarchie.estFeuilleConnue(balise))
        }
        // Aucun conteneur connu ne sort des tables : pas de faux refus.
        assertFalse(ContraintesHierarchie.estFeuilleConnue("ScrollView"))
        assertFalse(ContraintesHierarchie.estFeuilleConnue("RecyclerView"))
        assertFalse(ContraintesHierarchie.estFeuilleConnue("TextInputLayout"))
        assertFalse(ContraintesHierarchie.estFeuilleConnue("MaterialButtonToggleGroup"))
        assertFalse(ContraintesHierarchie.estFeuilleConnue("MaVueCustom"))
        assertFalse(ContraintesHierarchie.estFeuilleConnue("MockView"))
    }

    // ── Verdicts de contenance ──────────────────────────────────────────

    @Test
    fun `contenance - les verdicts sains restent muets`() {
        assertNull(ContraintesHierarchie.contenance("TextView", emptyList()))
        assertNull(ContraintesHierarchie.contenance("LinearLayout", listOf("Button", "TextView")))
        assertNull(ContraintesHierarchie.contenance("ScrollView", listOf("LinearLayout")))
        assertNull(ContraintesHierarchie.contenance("ChipGroup", listOf("Chip", "Chip")))
        assertNull(ContraintesHierarchie.contenance("MaVueCustom", listOf("Button")))
    }

    @Test
    fun `contenance - les quatre genres avec leur severite`() {
        // Feuille avec enfants : ERREUR.
        assertEquals(
            ContraintesHierarchie.ContenanceProbleme(
                ProblemesPreview.GenreProbleme.ENFANTS_INTERDITS,
                ProblemesPreview.Severite.ERREUR,
            ),
            ContraintesHierarchie.contenance("TextView", listOf("View")),
        )
        // Adaptateur avec enfants : ERREUR (miroir AdapterViewChildren).
        assertEquals(
            ContraintesHierarchie.ContenanceProbleme(
                ProblemesPreview.GenreProbleme.ENFANTS_ADAPTATEUR,
                ProblemesPreview.Severite.ERREUR,
            ),
            ContraintesHierarchie.contenance("RecyclerView", listOf("TextView")),
        )
        // Enfant unique dépassé : AVERTISSEMENT (miroir ScrollViewChildren).
        assertEquals(
            ContraintesHierarchie.ContenanceProbleme(
                ProblemesPreview.GenreProbleme.ENFANT_UNIQUE_DEPASSE,
                ProblemesPreview.Severite.AVERTISSEMENT,
            ),
            ContraintesHierarchie.contenance("ScrollView", listOf("Button", "TextView")),
        )
        // Enfant inattendu : AVERTISSEMENT quand la vraie classe tolère…
        assertEquals(
            ContraintesHierarchie.ContenanceProbleme(
                ProblemesPreview.GenreProbleme.ENFANT_INATTENDU,
                ProblemesPreview.Severite.AVERTISSEMENT,
            ),
            ContraintesHierarchie.contenance("TabLayout", listOf("TabItem", "TextView")),
        )
        // …ERREUR quand la bibliothèque jette (TextInputLayout).
        assertEquals(
            ContraintesHierarchie.ContenanceProbleme(
                ProblemesPreview.GenreProbleme.ENFANT_INATTENDU,
                ProblemesPreview.Severite.ERREUR,
            ),
            ContraintesHierarchie.contenance("TextInputLayout", listOf("Button")),
        )
    }

    @Test
    fun `contenance - l enfant attendu se nomme`() {
        assertEquals("TabItem", ContraintesHierarchie.baliseEnfantAttendu("TabLayout"))
        assertEquals(
            "TextInputEditText",
            ContraintesHierarchie.baliseEnfantAttendu("com.google.android.material.textfield.TextInputLayout"),
        )
        assertEquals("Chip", ContraintesHierarchie.baliseEnfantAttendu("ChipGroup"))
        assertEquals("MaterialButton", ContraintesHierarchie.baliseEnfantAttendu("MaterialButtonToggleGroup"))
        assertNull(ContraintesHierarchie.baliseEnfantAttendu("LinearLayout"))
        assertTrue(ContraintesHierarchie.refuseEnfantInattendu("TextInputLayout"))
        assertFalse(ContraintesHierarchie.refuseEnfantInattendu("TabLayout"))
    }

    // ── Évaluation sur le texte (ProblemesFamilles) ─────────────────────

    @Test
    fun `evaluation - un TextView racine qui porte des enfants est une erreur`() {
        val problemes = ProblemesFamilles.evaluer(document("TextView", boutonSimple))
        assertEquals(1, problemes.size)
        val probleme = problemes.first()
        assertEquals(ProblemesPreview.GenreProbleme.ENFANTS_INTERDITS, probleme.genre)
        assertEquals(ProblemesPreview.Severite.ERREUR, probleme.severite)
        assertEquals("TextView", probleme.balise)
        // La racine EST fautive : son span couvre le document entier.
        assertTrue("le problème vit au span du conteneur", probleme.debut == 0)
        assertTrue(probleme.fin > probleme.debut)
    }

    @Test
    fun `evaluation - un ScrollView a deux enfants est un avertissement, a un enfant rien`() {
        val deux = ProblemesFamilles.evaluer(document("ScrollView", boutonSimple + boutonSimple))
        assertEquals(1, deux.size)
        assertEquals(ProblemesPreview.GenreProbleme.ENFANT_UNIQUE_DEPASSE, deux.first().genre)
        assertEquals(ProblemesPreview.Severite.AVERTISSEMENT, deux.first().severite)
        assertTrue(
            "un seul enfant direct ne signale rien",
            ProblemesFamilles.evaluer(document("ScrollView", boutonSimple)).isEmpty(),
        )
    }

    @Test
    fun `evaluation - un RecyclerView avec enfants est une erreur`() {
        val problemes = ProblemesFamilles.evaluer(document("androidx.recyclerview.widget.RecyclerView", boutonSimple))
        assertEquals(1, problemes.size)
        assertEquals(ProblemesPreview.GenreProbleme.ENFANTS_ADAPTATEUR, problemes.first().genre)
        assertEquals(ProblemesPreview.Severite.ERREUR, problemes.first().severite)
    }

    @Test
    fun `evaluation - les conteneurs a enfant attendu distinguent tolere et refuse`() {
        val tolere = ProblemesFamilles.evaluer(document("TabLayout", boutonSimple))
        assertEquals(ProblemesPreview.GenreProbleme.ENFANT_INATTENDU, tolere.single().genre)
        assertEquals(ProblemesPreview.Severite.AVERTISSEMENT, tolere.single().severite)
        val refuse = ProblemesFamilles.evaluer(document("TextInputLayout", boutonSimple))
        assertEquals(ProblemesPreview.GenreProbleme.ENFANT_INATTENDU, refuse.single().genre)
        assertEquals(ProblemesPreview.Severite.ERREUR, refuse.single().severite)
        // Un groupe de chips sain ne signale rien.
        assertTrue(
            "des chips dans un ChipGroup vont de soi",
            ProblemesFamilles.evaluer(document("ChipGroup", chipSimple)).isEmpty(),
        )
    }

    // ── Correctif « Envelopper » ────────────────────────────────────────

    @Test
    fun `envelopper - les enfants passent sous un LinearLayout vertical, indentes`() {
        val texte = document("ScrollView", boutonSimple + boutonSimple)
        val probleme = ProblemesFamilles.evaluer(texte).single()
        val edition = ContraintesHierarchie.envelopperEnfants(texte, probleme)
        assertTrue("l'édition se calcule", edition != null)
        val corrige = texte.substring(0, edition!!.debut) + edition.remplacement + texte.substring(edition.fin)
        // Le LinearLayout d'enveloppe est vertical, plein en largeur.
        assertTrue(corrige.contains("<LinearLayout"))
        assertTrue(corrige.contains("android:orientation=\"vertical\""))
        assertTrue(corrige.contains("android:layout_width=\"match_parent\""))
        assertTrue(corrige.contains("</LinearLayout>"))
        // Le document corrigé est RELISIBLE et sain : le problème part,
        // les quatre vues vivent (ScrollView, enveloppe, deux boutons).
        assertTrue("le problème part", ProblemesFamilles.evaluer(corrige).isEmpty())
        val noeuds = ArbreAttributsXml.lire(corrige)
        assertEquals(4, noeuds.size)
        assertEquals("ScrollView", noeuds[0].balise)
        assertEquals("LinearLayout", noeuds[1].balise)
        assertEquals(0, noeuds[1].indiceParent)
        // Les enfants sont PLUS PROFONDS : leur parent est l'enveloppe.
        assertEquals(1, noeuds[2].indiceParent)
        assertEquals(1, noeuds[3].indiceParent)
        assertTrue(noeuds[2].debut > noeuds[1].debut)
    }

    @Test
    fun `envelopper - le probleme perime ou sain se refuse`() {
        val texte = document("ScrollView", boutonSimple + boutonSimple)
        val probleme = ProblemesFamilles.evaluer(texte).single()
        // Un texte où le conteneur a déménagé : le span du problème ne
        // désigne plus la même balise → refus, jamais de fausse édition.
        val autreTexte = document("LinearLayout", boutonSimple) + boutonSimple
        assertTrue(ContraintesHierarchie.envelopperEnfants(autreTexte, probleme) == null)
        // Un problème d'un autre genre ne s'enveloppe pas.
        val feuille = ProblemesFamilles.evaluer(document("TextView", boutonSimple)).single()
        assertTrue(ContraintesHierarchie.envelopperEnfants(document("TextView", boutonSimple), feuille) == null)
        // Un conteneur sain (un seul enfant) refuse aussi.
        val sain = document("ScrollView", boutonSimple)
        val problemeSain = ProblemesFamilles.evaluer(sain)
        assertTrue(problemeSain.isEmpty())
    }

    // ── Correctif « Retirer les enfants » ───────────────────────────────

    @Test
    fun `retirer - une feuille ou un adaptateur perd ses enfants avec le retrait de ligne`() {
        listOf("TextView", "ListView").forEach { balise ->
            val texte = document(balise, boutonSimple)
            val probleme = ProblemesFamilles.evaluer(texte).single()
            val edition = ContraintesHierarchie.retirerEnfants(texte, probleme)
            assertTrue("$balise : l'édition se calcule", edition != null)
            val corrige = texte.substring(0, edition!!.debut) + edition.remplacement + texte.substring(edition.fin)
            assertTrue(
                "$balise : le problème part",
                ProblemesFamilles.evaluer(corrige).isEmpty(),
            )
            assertFalse("l'enfant part entièrement", corrige.contains("<Button"))
        }
    }

    @Test
    fun `retirer - seuls les enfants de mauvais type partent, s ils sont contigus`() {
        // ChipGroup : une Chip ET un Button — seul le Button part.
        val texte = document("ChipGroup", chipSimple + boutonSimple)
        val probleme = ProblemesFamilles.evaluer(texte).single()
        val edition = ContraintesHierarchie.retirerEnfants(texte, probleme)
        assertTrue(edition != null)
        val corrige = texte.substring(0, edition!!.debut) + edition.remplacement + texte.substring(edition.fin)
        assertTrue("la Chip reste", corrige.contains("Chip"))
        assertFalse("le Button part", corrige.contains("Button"))
        assertTrue("le conteneur est sain", ProblemesFamilles.evaluer(corrige).isEmpty())
    }

    @Test
    fun `retirer - des fautifs intercales refusent l edition unique`() {
        // ChipGroup : Button + Chip + Button — les deux fautifs ne forment
        // PAS un run contigu (une Chip légitime les sépare) : un retrait
        // en UNE seule édition avalerait la Chip — refus, le message reste.
        val texte = document("ChipGroup", boutonSimple + chipSimple + boutonSimple)
        val probleme = ProblemesFamilles.evaluer(texte).single()
        assertEquals(ProblemesPreview.GenreProbleme.ENFANT_INATTENDU, probleme.genre)
        assertNull("fautifs non contigus : pas d'édition", ContraintesHierarchie.retirerEnfants(texte, probleme))
    }

    @Test
    fun `retirer - le fautif unique du milieu part, les chips restent`() {
        val texte = document("ChipGroup", chipSimple + boutonSimple + chipSimple)
        val probleme = ProblemesFamilles.evaluer(texte).single()
        val edition = ContraintesHierarchie.retirerEnfants(texte, probleme)
        assertTrue(edition != null)
        val corrige = texte.substring(0, edition!!.debut) + edition.remplacement + texte.substring(edition.fin)
        assertTrue("les Chip restent", corrige.contains("Chip"))
        assertFalse("le Button part", corrige.contains("Button"))
        assertTrue("le conteneur est sain", ProblemesFamilles.evaluer(corrige).isEmpty())
    }

    // ── Le popup honore la capacité ─────────────────────────────────────

    @Test
    fun `popup - un scrollview vide offre dedans, plein ne l offre plus`() {
        val vide = arbreAvecEnfants("ScrollView", 0)
        assertTrue(PlacementPopup.Position.DEDANS in PlacementPopup.positionsAutorisees(vide, 0))
        val plein = arbreAvecEnfants("ScrollView", 1)
        assertFalse(PlacementPopup.Position.DEDANS in PlacementPopup.positionsAutorisees(plein, 0))
        // La racine adaptateur n'offre RIEN : nulle part où placer.
        assertTrue(PlacementPopup.positionsAutorisees(arbreAvecEnfants("RecyclerView", 0), 0).isEmpty())
    }

    @Test
    fun `popup - un frere adaptateur ou plein garde avant et apres`() {
        // Un ListView conteneur-frère : pas de Dedans, Avant/Après restent.
        val vues =
            listOf(
                vue(classe = "LinearLayout", parent = -1, conteneur = true),
                vue(classe = "ListView", parent = 0, conteneur = true),
            )
        assertEquals(
            setOf(PlacementPopup.Position.AVANT, PlacementPopup.Position.APRES),
            PlacementPopup.positionsAutorisees(vues, 1),
        )
        assertEquals(PlacementPopup.Position.APRES, PlacementPopup.positionInitiale(vues, 1))
    }

    // ── Le dépôt remonte au premier ancêtre accepteur ───────────────────

    @Test
    fun `depot - un point sur une liste vise le parent lineaire`() {
        val vues =
            listOf(
                vue(classe = "LinearLayout", parent = -1, conteneur = true),
                vue(gauche = 0, haut = 0, droite = 360, bas = 160, classe = "ListView", parent = 0, conteneur = true),
            )
        val cible = ZonesDepot.cibleSous(vues, 180f, 120f)
        assertTrue("la liste remonte au parent", cible != null)
        assertEquals(0, cible!!.indiceParent)
        assertEquals(1, cible.index)
    }

    @Test
    fun `depot - un scrollview vide se cible, plein remonte, racine adaptateur refuse`() {
        // Vide : le ScrollView EST la cible.
        val vide =
            listOf(
                vue(
                    gauche = 0,
                    haut = 0,
                    droite = 360,
                    bas = 160,
                    classe = "ScrollView",
                    parent = -1,
                    conteneur = true,
                ),
            )
        assertEquals(0, ZonesDepot.cibleSous(vide, 180f, 80f)!!.indiceParent)
        // Plein (un enfant couvre le point) : le PÈRE (FrameLayout racine)
        // devient la cible — l'insertion se fait APRÈS le ScrollView.
        val plein =
            listOf(
                vue(
                    gauche = 0,
                    haut = 0,
                    droite = 360,
                    bas = 640,
                    classe = "FrameLayout",
                    parent = -1,
                    conteneur = true,
                ),
                vue(gauche = 0, haut = 0, droite = 360, bas = 160, classe = "ScrollView", parent = 0, conteneur = true),
                vue(
                    gauche = 0,
                    haut = 0,
                    droite = 360,
                    bas = 160,
                    classe = "LinearLayout",
                    parent = 1,
                    conteneur = true,
                ),
            )
        val cible = ZonesDepot.cibleSous(plein, 180f, 120f)
        assertTrue(cible != null)
        assertEquals(0, cible!!.indiceParent)
        assertEquals(1, cible.index)
        // Racine adaptateur sans ancêtre accepteur : le dépôt se refuse.
        val sansSortie =
            listOf(
                vue(
                    gauche = 0,
                    haut = 0,
                    droite = 360,
                    bas = 640,
                    classe = "RecyclerView",
                    parent = -1,
                    conteneur = true,
                ),
            )
        assertNull(ZonesDepot.cibleSous(sansSortie, 180f, 80f))
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    /** Un document racine [balise] au contenu [corps], namespaces déclarés. */
    private fun document(
        balise: String,
        corps: String,
    ): String =
        "<$balise xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
            "xmlns:app=\"http://schemas.android.com/apk/res/auto\" " +
            "android:layout_width=\"match_parent\" android:layout_height=\"match_parent\">$corps</$balise>"

    /** Un bouton compact, lisible sur une ligne. */
    private val boutonSimple: String =
        "<Button android:id=\"@+id/bouton\" android:layout_width=\"wrap_content\" " +
            "android:layout_height=\"wrap_content\"/>"

    /** Une chip Material compacte (balise qualifiée réelle). */
    private val chipSimple: String =
        "<com.google.android.material.chip.Chip android:id=\"@+id/chip\" " +
            "android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\"/>"

    /** Une racine [classe] avec [nombre] enfants TextView conteneurs ou feuilles. */
    private fun arbreAvecEnfants(
        classe: String,
        nombre: Int,
    ): List<InfoVueRendu> {
        val vues =
            mutableListOf(
                vue(classe = classe, parent = -1, conteneur = true),
            )
        repeat(nombre) { vues.add(vue(classe = "TextView", parent = 0)) }
        return vues
    }

    /** Une vue de test compacte (bornes par défaut 0-360 × 0-160). */
    @Suppress("LongParameterList")
    private fun vue(
        gauche: Int = 0,
        haut: Int = 0,
        droite: Int = 360,
        bas: Int = 160,
        classe: String,
        parent: Int,
        conteneur: Boolean = false,
    ): InfoVueRendu =
        InfoVueRendu(
            gauche = gauche,
            haut = haut,
            droite = droite,
            bas = bas,
            libelle = classe,
            debutElement = 0,
            finElement = 1,
            indiceParent = parent,
            profondeur = if (parent < 0) 0 else 1,
            classe = classe,
            idRes = "",
            conteneur = conteneur,
        )
}
