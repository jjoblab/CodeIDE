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
 * Contrat du CATALOGUE des attributs (PE7e, maquette § « Attributs en
 * sections selon le type de vue » — miroir `sectionsFor`) : chaque widget
 * de [CataloguePalette] reçoit des sections NON VIDES et SANS DOUBLON
 * d'attribut, une classe inconnue ne reçoit que l'IDENTITÉ (PE8a) et les
 * sections GÉNÉRIQUES (miroir piège § 5 du prompt PE7e), et les valeurs
 * d'énumération correspondent à la LISTE DE RÉFÉRENCE figée de la plateforme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
// Exemption detekt ciblée (règle 16) : LargeClass — le contrat couvre
// les 16 widgets de la palette ET les familles de parent (PE12c), chaque
// face du catalogue a SES tests ; le regrouper masquerait le miroir.
@Suppress("LargeClass")
public class CatalogueAttributsTest {
    /**
     * Les noms d'attributs d'une section (miroir `some` — portés par le
     * TYPE, comme le `when` de dispatch l'a été avant la CI de 175ce7a7 :
     * les bascules y comptent LEURS attributs, les composites sans champ
     * n'en portent AUCUN).
     */
    private fun noms(section: CatalogueAttributs.SectionAttributs): List<String> =
        section.controles.flatMap { controle -> controle.nomsAttributs }

    // ── Couverture des 16 widgets de la palette ──────────────────────────

    @Test
    fun `chaque widget de la palette a des sections non vides`() {
        for (entree in CataloguePalette.CATALOGUE) {
            val sections = CatalogueAttributs.sections(entree.classe, FamilleParent.AUTRE)
            assertTrue(
                "Le widget ${entree.classe} (${entree.id}) doit avoir des sections",
                sections.isNotEmpty(),
            )
            sections.forEach { section ->
                assertTrue(
                    "La section ${section.titre} de ${entree.classe} doit avoir des contrôles",
                    section.controles.isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun `aucun attribut n'apparait dans deux sections d'une meme vue`() {
        val classes =
            CataloguePalette.CATALOGUE.map { it.classe }.distinct() +
                listOf("MaterialButton", "SwitchMaterial", "TextInputEditText", "MockView")
        for (classe in classes) {
            val sections = CatalogueAttributs.sections(classe, FamilleParent.AUTRE)
            val tous = sections.flatMap { noms(it) }
            assertEquals(
                "La classe $classe a un attribut en double",
                tous.size,
                tous.distinct().size,
            )
        }
    }

    /** Le premier titre de section pour une classe (les assertions des classes propres). */
    private fun titrePropre(
        classe: String,
        indice: Int = 0,
    ): CatalogueAttributs.TitreSection = CatalogueAttributs.sections(classe, FamilleParent.AUTRE)[indice].titre

    @Test
    fun `chaque widget reconnu recoit sa section propre`() {
        // Le miroir `sectionsFor` : Texte pour TextView, Saisie pour EditText,
        // Champ pour TextInputLayout, Image pour ImageView, etc.
        assertEquals(CatalogueAttributs.TitreSection.IDENTITE, titrePropre("TextView"))
        assertEquals(CatalogueAttributs.TitreSection.TEXTE, titrePropre("TextView", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.SAISIE, titrePropre("EditText", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.CHAMP, titrePropre("TextInputLayout", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.BOUTON, titrePropre("Button", indice = 2))
        assertEquals(CatalogueAttributs.TitreSection.ETAT, titrePropre("CheckBox", indice = 2))
        assertEquals(CatalogueAttributs.TitreSection.IMAGE, titrePropre("ImageView", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.CONTENEUR, titrePropre("LinearLayout", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.PROGRESSION, titrePropre("ProgressBar", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.LISTE, titrePropre("RecyclerView", indice = 1))
    }

    @Test
    fun `les familles par heritage recoivent les sections de leur famille`() {
        // MaterialButton → Texte + Bouton, SwitchMaterial → Texte + État,
        // TextInputEditText → Saisie (miroir § 4.1 du prompt PE7e).
        val titres = { classe: String ->
            CatalogueAttributs.sections(classe, FamilleParent.AUTRE).map { it.titre }
        }
        assertTrue(
            titres("MaterialButton").containsAll(
                listOf(CatalogueAttributs.TitreSection.TEXTE, CatalogueAttributs.TitreSection.BOUTON),
            ),
        )
        assertTrue(
            titres("SwitchMaterial").containsAll(
                listOf(CatalogueAttributs.TitreSection.TEXTE, CatalogueAttributs.TitreSection.ETAT),
            ),
        )
        assertEquals(CatalogueAttributs.TitreSection.SAISIE, titres("TextInputEditText").get(1))
    }

    @Test
    fun `la carte garde arrondi et elevation sous le titre Carte - le conteneur devient frame`() {
        // PE12c — spec § 5.1 : MaterialCardView (famille FRAME) reçoit le
        // Conteneur FRAME ; ses deux contrôles historiques passent sous
        // le titre Carte (« + Carte (existant), inchangé »).
        val sections = CatalogueAttributs.sections("MaterialCardView", FamilleParent.FRAME)
        val carte = sections.first { it.titre == CatalogueAttributs.TitreSection.CARTE }
        assertEquals(listOf("app:cardCornerRadius", "app:cardElevation"), noms(carte))
        val conteneur = sections.first { it.titre == CatalogueAttributs.TitreSection.CONTENEUR }
        assertEquals(
            listOf(
                "android:foregroundGravity",
                "android:measureAllChildren",
                "android:clipChildren",
                "android:clipToPadding",
            ),
            noms(conteneur),
        )
        val ligne =
            CatalogueAttributs
                .sections("LinearLayout", FamilleParent.AUTRE)
                .first { it.titre == CatalogueAttributs.TitreSection.CONTENEUR }
        assertTrue(noms(ligne).containsAll(listOf("android:orientation", "android:gravity")))
        assertFalse(noms(ligne).contains("app:cardCornerRadius"))
    }

    // ── PE12c — sections par famille de parent (spec § 5) ──────────────

    @Test
    fun `le conteneur lineaire etendu porte les onze attributs des deux vagues`() {
        // § 5.1 — LINEAR : les trois attributs de la 1re vague + les huit
        // de la 2e vague (liste identique à l'annexe § 7 du brief PE11).
        assertEquals(
            listOf(
                "android:orientation",
                "android:gravity",
                "android:baselineAligned",
                "android:weightSum",
                "android:showDividers",
                "android:divider",
                "android:dividerPadding",
                "android:measureWithLargestChild",
                "android:clipChildren",
                "android:clipToPadding",
                "android:animateLayoutChanges",
            ),
            noms(
                CatalogueAttributs.sections("LinearLayout", FamilleParent.AUTRE, FamilleParent.LINEAR).first {
                    it.titre ==
                        CatalogueAttributs.TitreSection.CONTENEUR
                },
            ),
        )
    }

    @Test
    fun `le conteneur suit la famille de la vue - frame, relative, coordinateur`() {
        // § 5.1 — FRAME/RELATIVE/COORDINATOR avec LEURS listes propres ;
        // CONSTRAINT n'a PAS de section Conteneur.
        assertEquals(
            listOf(
                "android:foregroundGravity",
                "android:measureAllChildren",
                "android:clipChildren",
                "android:clipToPadding",
            ),
            noms(
                CatalogueAttributs.sections("FrameLayout", FamilleParent.AUTRE, FamilleParent.FRAME).first {
                    it.titre ==
                        CatalogueAttributs.TitreSection.CONTENEUR
                },
            ),
        )
        assertEquals(
            listOf("android:gravity", "android:ignoreGravity", "android:clipChildren", "android:clipToPadding"),
            noms(
                CatalogueAttributs.sections("RelativeLayout", FamilleParent.AUTRE, FamilleParent.RELATIVE).first {
                    it.titre ==
                        CatalogueAttributs.TitreSection.CONTENEUR
                },
            ),
        )
        assertEquals(
            listOf("android:fitsSystemWindows", "app:statusBarBackground", "android:clipChildren"),
            noms(
                CatalogueAttributs.sections("CoordinatorLayout", FamilleParent.AUTRE, FamilleParent.COORDINATOR).first {
                    it.titre ==
                        CatalogueAttributs.TitreSection.CONTENEUR
                },
            ),
        )
        // CONSTRAINT/AUTRE : le conteneur n'a PAS de section propre.
        assertFalse(
            CatalogueAttributs
                .sections("ConstraintLayout", FamilleParent.AUTRE, FamilleParent.CONSTRAINT)
                .any { it.titre == CatalogueAttributs.TitreSection.CONTENEUR },
        )
    }

    @Test
    fun `un AppBarLayout selectionne a sa section Barre d application`() {
        val barre =
            CatalogueAttributs
                .sections("AppBarLayout", FamilleParent.LINEAR, FamilleParent.APPBAR)
                .first { it.titre == CatalogueAttributs.TitreSection.BARRE_APPLICATION }
        assertEquals(
            listOf("app:liftOnScroll", "app:liftOnScrollTargetViewId", "android:fitsSystemWindows", "app:expanded"),
            noms(barre),
        )
    }

    @Test
    fun `sur la racine le conteneur ne repete pas fitsSystemWindows ni clipToPadding`() {
        // spec § 5.1 — miroir `DUP` : la section Racine de la 2e vague les porte.
        val coordRacine =
            CatalogueAttributs
                .sections("CoordinatorLayout", FamilleParent.AUCUN, FamilleParent.COORDINATOR)
                .first { it.titre == CatalogueAttributs.TitreSection.CONTENEUR }
        assertFalse(noms(coordRacine).contains("android:fitsSystemWindows"))
        val lineaireRacine =
            CatalogueAttributs
                .sections("LinearLayout", FamilleParent.AUCUN, FamilleParent.LINEAR)
                .first { it.titre == CatalogueAttributs.TitreSection.CONTENEUR }
        assertFalse(noms(lineaireRacine).contains("android:clipToPadding"))
        // HORS racine, ils restent.
        val coordEnfant =
            CatalogueAttributs
                .sections("CoordinatorLayout", FamilleParent.FRAME, FamilleParent.COORDINATOR)
                .first { it.titre == CatalogueAttributs.TitreSection.CONTENEUR }
        assertTrue(noms(coordEnfant).contains("android:fitsSystemWindows"))
    }

    @Test
    fun `le parent relatif ajoute l ancrage au parent et les positions relatives`() {
        val sections = CatalogueAttributs.sections("TextView", FamilleParent.RELATIVE)
        val dans = sections.first { it.titre == CatalogueAttributs.TitreSection.DANS_PARENT }
        val bascules =
            dans.controles.first { it is CatalogueAttributs.ControleAttribut.Bascules }
                as CatalogueAttributs.ControleAttribut.Bascules
        assertEquals(
            listOf(
                "android:layout_alignParentStart",
                "android:layout_alignParentEnd",
                "android:layout_alignParentTop",
                "android:layout_alignParentBottom",
                "android:layout_centerHorizontal",
                "android:layout_centerVertical",
                "android:layout_centerInParent",
            ),
            bascules.entrees.map { entree -> entree.nom },
        )
        val positions = sections.first { it.titre == CatalogueAttributs.TitreSection.POSITIONS_RELATIVES }
        assertEquals(CatalogueAttributs.NoteSection.REGLES_SOEURS, positions.note)
        val references = positions.controles.filterIsInstance<CatalogueAttributs.ControleAttribut.ReferenceId>()
        assertEquals(9, references.size)
        assertTrue(
            references.all { reference ->
                reference.portee ==
                    CatalogueAttributs.ControleAttribut.ReferenceId.Portee.FRERES
            },
        )
        assertTrue(references.first { it.nom == "android:layout_below" }.courant)
        assertTrue(references.first { it.nom == "android:layout_toEndOf" }.courant)
        assertFalse(references.first { it.nom == "android:layout_above" }.courant)
    }

    @Test
    fun `le parent coordinateur ajoute ancre, gravite d ancre et comportement`() {
        val sections = CatalogueAttributs.sections("FloatingActionButton", FamilleParent.COORDINATOR)
        val dans = sections.first { it.titre == CatalogueAttributs.TitreSection.DANS_PARENT }
        val ancre = dans.controles.filterIsInstance<CatalogueAttributs.ControleAttribut.ReferenceId>().single()
        assertEquals("app:layout_anchor", ancre.nom)
        assertEquals(CatalogueAttributs.ControleAttribut.ReferenceId.Portee.SOUS_ARBRE, ancre.portee)
        val graviteAncre =
            dans.controles
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Drapeaux>()
                .first { it.nom == "app:layout_anchorGravity" }
        assertEquals("app:layout_anchor", graviteAncre.requiert)
        val comportement =
            dans.controles
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Liste>()
                .first { it.nom == "app:layout_behavior" }
        assertTrue(comportement.valeurs.contains("@string/appbar_scrolling_view_behavior"))
        assertEquals(
            "app:layout_keyline",
            dans.controles
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Nombre>()
                .single()
                .nom,
        )
    }

    @Test
    fun `le parent appbar ajoute les drapeaux de defilement`() {
        val sections = CatalogueAttributs.sections("MaterialToolbar", FamilleParent.APPBAR)
        val dans = sections.first { it.titre == CatalogueAttributs.TitreSection.DANS_PARENT }
        val drapeaux =
            dans.controles
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Drapeaux>()
                .first { it.nom == "app:layout_scrollFlags" }
        assertEquals(
            listOf("scroll", "exitUntilCollapsed", "enterAlways", "enterAlwaysCollapsed", "snap", "snapMargins"),
            drapeaux.valeurs,
        )
        assertEquals(
            "app:layout_scrollInterpolator",
            dans.controles
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Texte>()
                .single()
                .nom,
        )
    }

    @Test
    fun `le parent frame n ajoute que la gravite`() {
        val dans =
            CatalogueAttributs
                .sections("TextView", FamilleParent.FRAME)
                .first { it.titre == CatalogueAttributs.TitreSection.DANS_PARENT }
        assertEquals(listOf("android:layout_gravity"), noms(dans))
    }

    @Test
    fun `toolbar et bouton d action ont leurs sections propres`() {
        val barre =
            CatalogueAttributs
                .sections("MaterialToolbar", FamilleParent.COORDINATOR)
                .first { it.titre == CatalogueAttributs.TitreSection.BARRE_OUTILS }
        assertEquals(
            listOf(
                "app:title",
                "app:subtitle",
                "app:navigationIcon",
                "app:titleCentered",
                "app:titleTextColor",
                "app:menu",
            ),
            noms(barre),
        )
        val fab =
            CatalogueAttributs
                .sections("FloatingActionButton", FamilleParent.COORDINATOR)
                .first { it.titre == CatalogueAttributs.TitreSection.BOUTON_ACTION }
        assertEquals(
            listOf(
                "app:srcCompat",
                "app:backgroundTint",
                "app:fabSize",
                "app:tint",
                "app:elevation",
                "app:borderWidth",
                "app:rippleColor",
                "app:useCompatPadding",
            ),
            noms(fab),
        )
    }

    @Test
    fun `l ordre suit propres puis conteneur puis parent puis generiques`() {
        // spec § 5 : les deux groupes de famille viennent APRÈS les
        // sections propres à la classe et AVANT les sections communes.
        val sections =
            CatalogueAttributs.sections("LinearLayout", FamilleParent.RELATIVE, FamilleParent.LINEAR)
        assertEquals(
            listOf(
                CatalogueAttributs.TitreSection.IDENTITE,
                CatalogueAttributs.TitreSection.CONTENEUR,
                CatalogueAttributs.TitreSection.DANS_PARENT,
                CatalogueAttributs.TitreSection.POSITIONS_RELATIVES,
                CatalogueAttributs.TitreSection.DISPOSITION,
                CatalogueAttributs.TitreSection.AFFICHAGE,
            ),
            sections.map { section -> section.titre },
        )
    }

    // ── Classe inconnue et parents ───────────────────────────────────────

    @Test
    fun `une classe inconnue ne recoit que l'identite et les sections generiques`() {
        val sections =
            CatalogueAttributs.sections("MockView", FamilleParent.AUTRE).map { it.titre }
        assertEquals(
            listOf(
                CatalogueAttributs.TitreSection.IDENTITE,
                CatalogueAttributs.TitreSection.DISPOSITION,
                CatalogueAttributs.TitreSection.AFFICHAGE,
            ),
            sections,
        )
    }

    @Test
    fun `le parent constraint ajoute les contraintes editables`() {
        // PE12b (spec § 2.3) : la famille CONSTRAINT couvre racine et
        // non-racine — l'ancienne distinction GenreParent ne servait à
        // rien, la section Contraintes est la même.
        val sections =
            CatalogueAttributs
                .sections("TextView", FamilleParent.CONSTRAINT)
                .map { it.titre }
        assertTrue(sections.contains(CatalogueAttributs.TitreSection.CONTRAINTES))
        val contraintes =
            CatalogueAttributs
                .sections("TextView", FamilleParent.CONSTRAINT)
                .first { it.titre == CatalogueAttributs.TitreSection.CONTRAINTES }
        assertEquals(
            CatalogueAttributs.ControleAttribut.Contraintes,
            contraintes.controles.single(),
        )
    }

    @Test
    fun `le parent lineaire ajoute poids et gravite de mise en page`() {
        val dansParent =
            CatalogueAttributs
                .sections("TextView", FamilleParent.LINEAR)
                .first { it.titre == CatalogueAttributs.TitreSection.DANS_PARENT }
        assertEquals(
            listOf("android:layout_weight", "android:layout_gravity"),
            noms(dansParent),
        )
    }

    @Test
    fun `la racine n'a ni contraintes ni dans le parent`() {
        val titres =
            CatalogueAttributs
                .sections("LinearLayout", FamilleParent.AUCUN)
                .map { it.titre }
        assertFalse(titres.contains(CatalogueAttributs.TitreSection.CONTRAINTES))
        assertFalse(titres.contains(CatalogueAttributs.TitreSection.DANS_PARENT))
        // La racine reste éditable pour Disposition et Affichage (piège § 5).
        assertTrue(
            titres.containsAll(
                listOf(CatalogueAttributs.TitreSection.DISPOSITION, CatalogueAttributs.TitreSection.AFFICHAGE),
            ),
        )
    }

    @Test
    fun `les familles sans section ne donnent aucune section de parent`() {
        // PE12c (spec § 5.2) livre les sections « Dans le parent » de
        // FRAME/RELATIVE/COORDINATOR/APPBAR — seuls AUCUN (la racine) et
        // AUTRE (parent inconnu) n'en donnent toujours aucune.
        for (famille in listOf(
            FamilleParent.AUCUN,
            FamilleParent.AUTRE,
        )) {
            val titres = CatalogueAttributs.sections("TextView", famille).map { it.titre }
            assertFalse(
                "la famille $famille n'a pas encore de section de contraintes",
                titres.contains(CatalogueAttributs.TitreSection.CONTRAINTES),
            )
            assertFalse(
                "la famille $famille n'a pas encore de section dans le parent",
                titres.contains(CatalogueAttributs.TitreSection.DANS_PARENT),
            )
        }
    }

    // ── Valeurs d'énumération (listes de référence figées — Android) ─────

    /** Les CLASSES testées (palette + les descendants hérités ajoutés). */
    private val classesCouvertes: List<String> =
        CataloguePalette.CATALOGUE.map { it.classe }.distinct() +
            listOf("MaterialButton", "SwitchMaterial", "TextInputEditText")

    /** Les contrôles CHOIX d'une classe, aplatis avec leur section (helper d'itération). */
    private fun choixDe(classe: String): List<CatalogueAttributs.ControleAttribut.Choix> =
        CatalogueAttributs
            .sections(classe, FamilleParent.AUTRE)
            .flatMap { it.controles }
            .filterIsInstance<CatalogueAttributs.ControleAttribut.Choix>()

    /** Les contrôles LISTE d'une classe (helper d'itération — PE11c). */
    private fun listesDe(classe: String): List<CatalogueAttributs.ControleAttribut.Liste> =
        CatalogueAttributs
            .sections(classe, FamilleParent.AUTRE)
            .flatMap { it.controles }
            .filterIsInstance<CatalogueAttributs.ControleAttribut.Liste>()

    @Test
    fun `les valeurs d'enumeration sont valides pour la plateforme`() {
        // Listes de référence : attrs.xml d'AOSP (visibility) + Material
        // Components (boxBackgroundMode, endIconMode, fabSize) + LinearLayout
        // (orientation). Les masques | (textStyle, gravity, layout_gravity)
        // et le type de saisie sont des DRAPEAUX/TypeSaisie — testés plus bas.
        // PE12h : FloatingActionButton entre dans les classes couvertes quand
        // la palette offre le bouton (§ 11) — sa section propre (PE12c, § 5.4)
        // porte le Choix fabSize.
        val reference =
            mapOf(
                "app:boxBackgroundMode" to listOf("outline", "filled"),
                "app:endIconMode" to listOf("none", "password_toggle", "clear_text"),
                "app:fabSize" to listOf("auto", "normal", "mini"),
                "android:orientation" to listOf("vertical", "horizontal"),
                "android:visibility" to listOf("visible", "invisible", "gone"),
            )
        val classeChoix =
            classesCouvertes
                .flatMap { classe -> choixDe(classe).map { classe to it } }
                .distinctBy { it.second.nom }
        for ((classe, choix) in classeChoix) {
            assertEquals(
                "Valeurs de ${choix.nom} ($classe)",
                reference[choix.nom],
                choix.valeurs,
            )
        }
    }

    @Test
    fun `le premier choix est le defaut documente`() {
        // Miroir § 4.2 : le premier choix RETIRE l'attribut — ce n'est
        // correct que pour les attributs dont le défaut plateforme coincide.
        val defauts =
            mapOf(
                "android:visibility" to "visible",
                "app:boxBackgroundMode" to "outline",
                "app:endIconMode" to "none",
                "android:orientation" to "vertical",
            )
        val classeChoix =
            classesCouvertes
                .flatMap { classe -> choixDe(classe).map { classe to it } }
                .filter { it.second.nom in defauts }
                .distinctBy { it.second.nom }
        for ((classe, choix) in classeChoix) {
            assertEquals(
                "Défaut de ${choix.nom} ($classe)",
                defauts[choix.nom],
                choix.valeurs.first(),
            )
        }
    }

    @Test
    fun `les enums de plus de 4 valeurs sont des listes deroulantes`() {
        // Miroir § 9 / maquette « Liste » : un enum de plus de 4 valeurs
        // porte le contrôle LISTE (liste déroulante) ; scaleType est le
        // premier usager (8 valeurs plateforme, SCALE de la maquette).
        val reference =
            mapOf(
                "android:scaleType" to
                    listOf(
                        "matrix",
                        "fitXY",
                        "fitStart",
                        "fitCenter",
                        "fitEnd",
                        "center",
                        "centerCrop",
                        "centerInside",
                    ),
            )
        val classeListes =
            classesCouvertes
                .flatMap { classe -> listesDe(classe).map { classe to it } }
                .distinctBy { it.second.nom }
        for ((classe, liste) in classeListes) {
            assertEquals(
                "Valeurs de ${liste.nom} ($classe)",
                reference[liste.nom],
                liste.valeurs,
            )
        }
    }

    // ── PE11d : les masques multi-valeurs (miroir `flags` de la maquette) ─

    /** Les contrôles DRAPEAUX d'une classe (helper d'itération — PE11d). */
    private fun drapeauxDe(classe: String): List<CatalogueAttributs.ControleAttribut.Drapeaux> =
        CatalogueAttributs
            .sections(classe, FamilleParent.AUTRE)
            .flatMap { it.controles }
            .filterIsInstance<CatalogueAttributs.ControleAttribut.Drapeaux>()

    @Test
    fun `les masques multi-valeurs sont des drapeaux`() {
        // Miroir § 9 / maquette `flags` : tout attribut dont la valeur est
        // un masque « a|b|c » porte le contrôle DRAPEAUX. Références :
        // GRAV de la maquette (8 valeurs AOSP), ['bold','italic'] pour
        // textStyle (« normal » est l'état VIDE), showDividers et
        // scrollbars de la maquette.
        val reference =
            mapOf(
                "android:textStyle" to listOf("bold", "italic"),
                "android:gravity" to
                    listOf(
                        "start",
                        "end",
                        "top",
                        "bottom",
                        "center_horizontal",
                        "center_vertical",
                        "center",
                        "fill",
                    ),
                "android:showDividers" to listOf("beginning", "middle", "end"),
                "android:scrollbars" to listOf("vertical", "horizontal"),
                // PE12c (spec § 5.1) : la gravité d'avant-plan du conteneur
                // FRAME porte le même masque GRAV que gravity.
                "android:foregroundGravity" to
                    listOf(
                        "start",
                        "end",
                        "top",
                        "bottom",
                        "center_horizontal",
                        "center_vertical",
                        "center",
                        "fill",
                    ),
            )
        val classeDrapeaux =
            classesCouvertes
                .flatMap { classe -> drapeauxDe(classe).map { classe to it } }
                .distinctBy { it.second.nom }
        for ((classe, drapeaux) in classeDrapeaux) {
            assertEquals(
                "Valeurs de ${drapeaux.nom} ($classe)",
                reference[drapeaux.nom],
                drapeaux.valeurs,
            )
        }
    }

    @Test
    fun `layout_gravity est un drapeaux dans le parent lineaire`() {
        // La section DANS_PARENT n'existe que pour un parent LinearLayout :
        // layout_gravity y porte le MASQUE complet de la maquette (GRAV).
        val sections =
            CatalogueAttributs.sections("TextView", FamilleParent.LINEAR)
        val drapeaux =
            sections
                .flatMap { it.controles }
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Drapeaux>()
                .first { it.nom == "android:layout_gravity" }
        assertEquals(
            "layout_gravity porte les 8 drapeaux de la maquette",
            listOf(
                "start",
                "end",
                "top",
                "bottom",
                "center_horizontal",
                "center_vertical",
                "center",
                "fill",
            ),
            drapeaux.valeurs,
        )
    }

    @Test
    fun `le type de saisie est le composite liste plus drapeaux`() {
        // Miroir § 9 / maquette `input` : inputType porte le composite
        // TypeSaisie — IN_BASE (12 bases, « text » défaut) et IN_MOD (5
        // modificateurs) de la maquette ; c'est l'usager de la SAISIE.
        val composite =
            classesCouvertes
                .flatMap { classe ->
                    CatalogueAttributs
                        .sections(classe, FamilleParent.AUTRE)
                        .flatMap { it.controles }
                        .filterIsInstance<CatalogueAttributs.ControleAttribut.TypeSaisie>()
                }.distinctBy { it.nom }
                .single()
        assertEquals("L'usager du composite est inputType", "android:inputType", composite.nom)
        assertEquals(
            "Les bases (IN_BASE de la maquette)",
            listOf(
                "text",
                "textEmailAddress",
                "textPassword",
                "textUri",
                "textPersonName",
                "textMultiLine",
                "number",
                "numberDecimal",
                "numberSigned",
                "numberPassword",
                "phone",
                "datetime",
            ),
            composite.bases,
        )
        assertEquals(
            "Les modificateurs (IN_MOD de la maquette)",
            listOf(
                "textCapSentences",
                "textCapWords",
                "textCapCharacters",
                "textAutoCorrect",
                "textNoSuggestions",
            ),
            composite.modificateurs,
        )
    }

    @Test
    fun `la bascule d un drapeau suit la maquette`() {
        // Miroir `opts.filter(o => n.includes(o)).join('|')` : l'ordre du
        // CATALOGUE prime sur l'ordre des clics, une partie hors catalogue
        // reste visible ET conservée, tout décoché retire l'attribut.
        val style =
            CatalogueAttributs.ControleAttribut.Drapeaux(
                "android:textStyle",
                CatalogueAttributs.Etiquette.STYLE,
                listOf("bold", "italic"),
            )
        assertEquals("Poser bold", "bold", style.basculer(null, "bold"))
        assertEquals("Poser italic en second", "bold|italic", style.basculer("bold", "italic"))
        assertEquals("Retirer bold", "italic", style.basculer("bold|italic", "bold"))
        assertEquals("Retirer italic (tout décoché = retrait)", "", style.basculer("italic", "italic"))

        // L'ordre du catalogue prime : poser italic puis bold, l'écriture
        // ordonne bold PUIS italic (jamais l'ordre des clics).
        assertEquals("L'ordre du catalogue prime", "bold|italic", style.basculer("italic", "bold"))

        // Une valeur hors catalogue : visible, cochée, conservée à l'écriture.
        assertEquals("Partie hors catalogue conservée", "bold|italic|custom", style.basculer("bold|custom", "italic"))
        assertEquals(
            "Options : catalogue puis hors catalogue",
            listOf("bold", "italic", "custom"),
            style.options("bold|custom"),
        )
        assertEquals("Parties d'une valeur vide", emptyList<String>(), style.parties(null))
    }

    @Test
    fun `la composition du type de saisie suit la maquette`() {
        // Miroir `write` : « text » sans modificateur RETIRE l'attribut ;
        // la base courante est la première partie connue, sinon « text » ;
        // les parties inconnues sont ignorées.
        val composite =
            CatalogueAttributs.ControleAttribut.TypeSaisie(
                "android:inputType",
                listOf("text", "number", "phone"),
                listOf("textCapSentences", "textAutoCorrect"),
            )
        assertEquals("text seul retire l'attribut", "", composite.composer("text", emptyList()))
        assertEquals(
            "Un modificateur compose",
            "text|textCapSentences",
            composite.composer("text", listOf("textCapSentences")),
        )
        assertEquals(
            "Une base non textuelle reste",
            "number",
            composite.composer("number", emptyList()),
        )
        assertEquals(
            "Base et modificateurs composés",
            "text|textCapSentences|textAutoCorrect",
            composite.composer("text", listOf("textCapSentences", "textAutoCorrect")),
        )
        assertEquals("La base courante par défaut", "text", composite.base(null))
        assertEquals("La base courante extraite", "number", composite.base("number|xyz"))
        assertEquals(
            "La base courante avec modificateurs",
            "text",
            composite.base("text|textCapSentences"),
        )
        assertEquals("Base inconnue → défaut", "text", composite.base("xyz"))
        assertEquals(
            "Les modificateurs posés",
            listOf("textAutoCorrect"),
            composite.modificateursPoses("text|textAutoCorrect|xyz"),
        )
    }

    @Test
    fun `tout attribut de reference porte le controle Ressource - usagers icon et src`() {
        // Règle § 9 (PE11f) : une RÉFÉRENCE `@type/nom` n'est jamais un
        // champ texte libre — le contrôle `res` de la maquette
        // (suggestions + complétion du nom nu). Usagers de la 1re vague :
        // app:icon (Bouton) et android:src (Image), types ['drawable'].
        val bouton =
            CatalogueAttributs
                .sections("Button", FamilleParent.CONSTRAINT)
                .first { it.titre == CatalogueAttributs.TitreSection.BOUTON }
        val icon =
            bouton.controles.first { it is CatalogueAttributs.ControleAttribut.Ressource }
        assertEquals(
            "app:icon est le contrôle ressource drawable",
            CatalogueAttributs.ControleAttribut.Ressource(
                "app:icon",
                CatalogueAttributs.Etiquette.ICONE,
                listOf("drawable"),
            ),
            icon,
        )
        val image =
            CatalogueAttributs
                .sections("ImageView", FamilleParent.AUCUN)
                .first { it.titre == CatalogueAttributs.TitreSection.IMAGE }
        val src =
            image.controles.first { it is CatalogueAttributs.ControleAttribut.Ressource }
        assertEquals(
            "android:src est le contrôle ressource drawable",
            CatalogueAttributs.ControleAttribut.Ressource(
                "android:src",
                CatalogueAttributs.Etiquette.SOURCE,
                listOf("drawable"),
            ),
            src,
        )
    }

    @Test
    fun `tout controle de la premiere vague est courant - le drapeau existe`() {
        // PE10a/PE11f : le drapeau `courant` existe sur CHAQUE contrôle ;
        // la 1re vague est TOUJOURS courante — sauf le Conteneur LINEAR,
        // étendu dès PE12c (spec § 5.1) des attributs NON courants de la
        // 2e vague (seuls orientation et gravity, marqués ★, y restent).
        for (classe in listOf("TextView", "Button", "EditText", "ImageView", "LinearLayout")) {
            for (section in CatalogueAttributs.sections(classe, FamilleParent.CONSTRAINT)) {
                if (classe == "LinearLayout" && section.titre == CatalogueAttributs.TitreSection.CONTENEUR) {
                    continue
                }
                for (controle in section.controles) {
                    assertTrue(
                        "contrôle de $classe/${section.titre}",
                        controle.courant,
                    )
                }
            }
        }
    }

    @Test
    fun `tout attribut de couleur porte le controle Couleur - usager textColor`() {
        // Règle § 9 (PE11e) : un attribut de COULEUR n'est jamais un
        // champ texte libre — le contrôle `col` de la maquette (pastille
        // + hex + Palette). Premier usager du catalogue : android:textColor.
        val texte =
            CatalogueAttributs
                .sections("TextView", FamilleParent.AUCUN)
                .first { it.titre == CatalogueAttributs.TitreSection.TEXTE }
        val controle =
            texte.controles.first { it is CatalogueAttributs.ControleAttribut.Couleur }
        assertEquals(
            "textColor est le contrôle couleur",
            CatalogueAttributs.ControleAttribut.Couleur(
                "android:textColor",
                CatalogueAttributs.Etiquette.COULEUR,
            ),
            controle,
        )
        assertFalse(
            "plus AUCUN champ Texte ne porte l'étiquette COULEUR",
            CatalogueAttributs
                .sections("TextView", FamilleParent.AUCUN)
                .any { section ->
                    section.controles.any {
                        it is CatalogueAttributs.ControleAttribut.Texte &&
                            it.etiquette == CatalogueAttributs.Etiquette.COULEUR
                    }
                },
        )
    }

    @Test
    fun `l alpha et la progression portent le controle curseur de la maquette`() {
        // PE11g-contrôles — règle § 9 (miroir `sld`) : un attribut
        // NUMÉRIQUE BORNÉ est une GLISSIÈRE liée à son champ, pas un
        // champ nombre. Usagers de la 1re vague : android:alpha
        // (Affichage, [0 à 1] pas 0.05, défaut 1) et android:progress
        // (Progression, [0 à 100] pas 1, défaut 0 — la borne HAUTE suit
        // android:max, miroir `Math.max(1, dpv(ex(v, 'android:max')) || 100)`).
        val affichage =
            CatalogueAttributs
                .sections("TextView", FamilleParent.CONSTRAINT)
                .first { it.titre == CatalogueAttributs.TitreSection.AFFICHAGE }
        assertEquals(
            "alpha est le curseur [0 à 1] de défaut 1",
            CatalogueAttributs.ControleAttribut.Curseur(
                "android:alpha",
                CatalogueAttributs.Etiquette.ALPHA,
                min = 0f,
                max = 1f,
                pas = 0.05f,
                unite = "",
                defaut = 1f,
            ),
            affichage.controles.first { it is CatalogueAttributs.ControleAttribut.Curseur },
        )
        assertFalse(
            "plus AUCUN champ nombre ne porte l'étiquette ALPHA",
            CatalogueAttributs
                .sections("TextView", FamilleParent.CONSTRAINT)
                .any { section ->
                    section.controles.any {
                        it is CatalogueAttributs.ControleAttribut.Nombre &&
                            it.etiquette == CatalogueAttributs.Etiquette.ALPHA
                    }
                },
        )
        val progression =
            CatalogueAttributs
                .sections("SeekBar", FamilleParent.CONSTRAINT)
                .first { it.titre == CatalogueAttributs.TitreSection.PROGRESSION }
        assertEquals(
            "progress est le curseur borné au max du document",
            CatalogueAttributs.ControleAttribut.Curseur(
                "android:progress",
                CatalogueAttributs.Etiquette.PROGRESSION,
                min = 0f,
                max = 100f,
                pas = 1f,
                unite = "",
                defaut = 0f,
                maxDe = "android:max",
            ),
            progression.controles.first { it is CatalogueAttributs.ControleAttribut.Curseur },
        )
    }

    @Test
    fun `le curseur affiche son defaut, ecrit nombre plus unite et retire au defaut`() {
        // Miroir `sld` : `cur === '' ? c.def : (dpv(cur) ?? c.def)` à
        // l'affichage, `num === c.def ? '' : num + c.u` à l'écriture,
        // `Math.max(1, dpv(ex(v, 'android:max')) || 100)` pour la borne
        // DYNAMIQUE (le zéro est FAUX côté maquette — il retombe sur le
        // max statique ; un négatif prend le plancher 1).
        val alpha =
            CatalogueAttributs.ControleAttribut.Curseur(
                "android:alpha",
                CatalogueAttributs.Etiquette.ALPHA,
                min = 0f,
                max = 1f,
                pas = 0.05f,
                unite = "",
                defaut = 1f,
            )
        assertEquals("Absent → défaut", 1f, alpha.valeurAffichee(null))
        assertEquals("Vide → défaut", 1f, alpha.valeurAffichee(""))
        assertEquals("Non numérique → défaut", 1f, alpha.valeurAffichee("plein"))
        assertEquals("La valeur lue sert le nombre", 0.5f, alpha.valeurAffichee("0.5"))
        assertEquals("Le nombre en tête survit à l'unité (dpv)", 12f, alpha.valeurAffichee("12dp"))
        assertEquals("Égale au défaut → RETRAIT", "", alpha.ecrire(1f))
        assertEquals("Nombre + unité", "0.5", alpha.ecrire(0.5f))
        val translation =
            CatalogueAttributs.ControleAttribut.Curseur(
                "android:translationZ",
                CatalogueAttributs.Etiquette.ELEVATION,
                min = 0f,
                max = 24f,
                pas = 1f,
                unite = "dp",
                defaut = 0f,
            )
        assertEquals("L'entier s'écrit SANS décimales, l'unité suit", "16dp", translation.ecrire(16f))
        assertEquals("Égale au défaut → RETRAIT (unité ou non)", "", translation.ecrire(0f))
        val progress =
            CatalogueAttributs.ControleAttribut.Curseur(
                "android:progress",
                CatalogueAttributs.Etiquette.PROGRESSION,
                min = 0f,
                max = 100f,
                pas = 1f,
                unite = "",
                defaut = 0f,
                maxDe = "android:max",
            )
        assertEquals("Sans max dans le document → 100", 100f, progress.borneMax(null))
        assertEquals("La borne suit le max du document", 50f, progress.borneMax("50"))
        assertEquals("L'unité du max ne compte pas (dpv)", 60f, progress.borneMax("60dp"))
        assertEquals("Le zéro est FAUX côté maquette → max statique", 100f, progress.borneMax("0"))
        assertEquals("Le non numérique retombe sur le max statique", 100f, progress.borneMax("plein"))
        assertEquals("Un négatif prend le plancher 1 (Math.max)", 1f, progress.borneMax("-5"))
    }

    @Test
    fun `le format des nombres tue le bruit de pas de la glissiere`() {
        // Miroir le `num + c.u` de la maquette (le nombre JS se
        // imprime SANS zéros de queue) : l'entier sans décimales, le
        // décimal au plus court — et le BRUIT de pas de la glissière
        // Material (3 × 0.05f en binaire) ne fuit jamais dans le XML.
        assertEquals("16", formaterNombre(16f))
        assertEquals("0.05", formaterNombre(0.05f))
        assertEquals("0.15", formaterNombre(3f * 0.05f))
        assertEquals("-180", formaterNombre(-180f))
        assertEquals("0.123456", formaterNombre(0.123456f))
    }

    @Test
    fun `le ratio lit et ecrit la forme W virgule a deux points b de la maquette`() {
        // Miroir `/^(?:([WH]),)?(\d+(?:\.\d+)?):(\d+(?:\.\d+)?)$/` :
        // le verrou est OPTIONNEL, les nombres TELS QUE SAISIS se
        // restituent à l'écriture (miroir `(lk ? lk + ',' : '') + x + ':' + y`).
        // L'étiquette vient avec l'entrée § 7 (Contraintes) — le test ne
        // porte que la DONNÉE.
        val ratio =
            CatalogueAttributs.ControleAttribut.Ratio(
                "app:layout_constraintDimensionRatio",
                CatalogueAttributs.Etiquette.TEXTE,
            )
        assertEquals(
            CatalogueAttributs.ControleAttribut.Ratio.Valeur(verrou = null, a = "16", b = "9"),
            ratio.lire("16:9"),
        )
        assertEquals(
            CatalogueAttributs.ControleAttribut.Ratio.Valeur(verrou = "W", a = "3", b = "4"),
            ratio.lire("W,3:4"),
        )
        assertEquals(
            "Les décimales se lisent",
            CatalogueAttributs.ControleAttribut.Ratio.Valeur(verrou = null, a = "1.5", b = "2"),
            ratio.lire("1.5:2"),
        )
        assertNull("Absent", ratio.lire(null))
        assertNull("Vide", ratio.lire(""))
        assertNull("Sans les deux-points", ratio.lire("16"))
        assertNull("Non numérique", ratio.lire("a:b"))
        assertNull("Trois nombres", ratio.lire("16:9:8"))
        assertNull("Verrou inconnu", ratio.lire("X,1:1"))
        assertEquals("16:9", ratio.ecrire(null, "16", "9"))
        assertEquals("W,3:4", ratio.ecrire("W", "3", "4"))
        assertEquals(
            "Les quatre préréglages de la maquette",
            listOf(
                CatalogueAttributs.ControleAttribut.Ratio.Prereglage("1:1", "1", "1"),
                CatalogueAttributs.ControleAttribut.Ratio.Prereglage("4:3", "4", "3"),
                CatalogueAttributs.ControleAttribut.Ratio.Prereglage("16:9", "16", "9"),
                CatalogueAttributs.ControleAttribut.Ratio.Prereglage("3:4", "3", "4"),
            ),
            CatalogueAttributs.ControleAttribut.Ratio.PREDEFINIS,
        )
    }
}
