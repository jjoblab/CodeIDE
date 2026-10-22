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
                // PE11h — la section AUTRES ne porte AUCUN contrôle du
                // catalogue : son corps vient du document (attribut libre).
                if (section.autres) return@forEach
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
        // Le miroir `sectionsFor` v6 : Texte pour TextView, Saisie pour EditText
        // (APRÈS Typographie, Lignes et Icônes du texte — § 7), Champ pour
        // TextInputLayout, Image pour ImageView, etc.
        assertEquals(CatalogueAttributs.TitreSection.IDENTITE, titrePropre("TextView"))
        assertEquals(CatalogueAttributs.TitreSection.TEXTE, titrePropre("TextView", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.TYPOGRAPHIE, titrePropre("EditText", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.SAISIE, titrePropre("EditText", indice = 4))
        assertEquals(CatalogueAttributs.TitreSection.CHAMP, titrePropre("TextInputLayout", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.AIDES_CHAMP, titrePropre("TextInputLayout", indice = 2))
        assertEquals(CatalogueAttributs.TitreSection.ICONES, titrePropre("TextInputLayout", indice = 3))
        assertEquals(CatalogueAttributs.TitreSection.CONTOUR, titrePropre("TextInputLayout", indice = 4))
        assertEquals(CatalogueAttributs.TitreSection.BOUTON, titrePropre("Button", indice = 5))
        assertEquals(CatalogueAttributs.TitreSection.ETAT, titrePropre("CheckBox", indice = 5))
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
        assertEquals(CatalogueAttributs.TitreSection.SAISIE, titres("TextInputEditText").get(4))
    }

    @Test
    fun `l extension palette v0_166_3 - familles des nouvelles entrees`() {
        val titres = { classe: String ->
            CatalogueAttributs.sections(classe, FamilleParent.AUTRE).map { it.titre }
        }
        // v0.166.3 « palette plus complète » : les MÊMES familles par
        // héritage de nom couvrent les 19 extensions — la fiche d'un
        // nouveau widget N'EST PAS nue.
        // AutoCompleteTextView (EditText descendant) : Typographie,
        // Lignes, Icônes du texte puis SAISIE — l'ordre § 7 d'EditText.
        assertEquals(CatalogueAttributs.TitreSection.TYPOGRAPHIE, titrePropre("AutoCompleteTextView", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.SAISIE, titrePropre("AutoCompleteTextView", indice = 4))
        // Les à-état de l'extension (RadioButton, ToggleButton, Chip)
        // reçoivent TEXTE + ÉTAT comme CheckBox (§ 7 : `checked`).
        listOf("RadioButton", "ToggleButton", "Chip").forEach { classe ->
            assertTrue(
                "La classe $classe devrait porter Texte + État",
                titres(classe).containsAll(
                    listOf(CatalogueAttributs.TitreSection.TEXTE, CatalogueAttributs.TitreSection.ETAT),
                ),
            )
        }
        // RatingBar reçoit la PROGRESSION de base (max/progress), SANS
        // indéterminé (ProgressBar) ni pouce (SeekBar).
        assertEquals(CatalogueAttributs.TitreSection.PROGRESSION, titrePropre("RatingBar", indice = 1))
        val progression =
            CatalogueAttributs
                .sections("RatingBar", FamilleParent.AUTRE)
                .first { it.titre == CatalogueAttributs.TitreSection.PROGRESSION }
        assertTrue(noms(progression).containsAll(listOf("android:max", "android:progress")))
        assertFalse(noms(progression).contains("android:indeterminate"))
        assertFalse(noms(progression).contains("android:thumbTint"))
        // Les nouveaux conteneurs restent cohérents : ScrollView reçoit le
        // CONTENEUR de sa famille FRAME (miroir § 2.2 par balise).
        assertEquals(CatalogueAttributs.TitreSection.CONTENEUR, titrePropre("ScrollView", indice = 1))
        assertTrue(titres("HorizontalScrollView").contains(CatalogueAttributs.TitreSection.CONTENEUR))
    }

    @Test
    fun `l extension palette v0_170 - familles des vingt-trois nouvelles entrees`() {
        val titres = { classe: String ->
            CatalogueAttributs.sections(classe, FamilleParent.AUTRE).map { it.titre }
        }
        // v0.170.0 S4 « palette proche d'Android Studio » : les MÊMES
        // familles par héritage de nom couvrent les 23 extensions.
        // MultiAutoCompleteTextView : la SAISIE d'EditText (miroir act).
        assertTrue(titres("MultiAutoCompleteTextView").contains(CatalogueAttributs.TitreSection.SAISIE))
        // CheckedTextView : TEXTE + ÉTAT (TextView à coche, § 7).
        assertTrue(
            "CheckedTextView porte Texte + État",
            titres("CheckedTextView").containsAll(
                listOf(CatalogueAttributs.TitreSection.TEXTE, CatalogueAttributs.TitreSection.ETAT),
            ),
        )
        // MaterialSwitch : l'ÉTAT interrupteur, SANS icônes du texte (§ 7).
        assertTrue(titres("MaterialSwitch").contains(CatalogueAttributs.TitreSection.ETAT))
        assertFalse(titres("MaterialSwitch").contains(CatalogueAttributs.TitreSection.ICONES_TEXTE))
        // La Toolbar androidx reçoit la BARRE D'OUTILS de MaterialToolbar
        // (§ 5.4 — elle est sa classe de base, mêmes six attributs).
        val barreToolbar =
            CatalogueAttributs
                .sections("Toolbar", FamilleParent.AUTRE)
                .first { it.titre == CatalogueAttributs.TitreSection.BARRE_OUTILS }
        assertTrue(noms(barreToolbar).contains("app:title"))
        // Les nouveaux conteneurs suivent leur famille PAR BALISE : le
        // TableLayout est LINEAR, le DrawerLayout est FRAME (§ 2.2).
        assertTrue(titres("TableLayout").contains(CatalogueAttributs.TitreSection.CONTENEUR))
        assertEquals(FamilleParent.LINEAR, FamilleParent.deBalise("TableLayout"))
        assertEquals(FamilleParent.FRAME, FamilleParent.deBalise("DrawerLayout"))
        // TextInputEditText seul : la SAISIE (miroir EditText).
        assertTrue(titres("TextInputEditText").contains(CatalogueAttributs.TitreSection.SAISIE))
    }

    @Test
    fun `la carte garde arrondi et elevation sous le titre Carte - le conteneur devient frame`() {
        // PE12c — spec § 5.1 : MaterialCardView (famille FRAME) reçoit le
        // Conteneur FRAME ; PE11 § 7 ÉTEND la section Carte (fond, arrondi,
        // élévation puis variante, bordure, ripple, padding, réglages).
        val sections = CatalogueAttributs.sections("MaterialCardView", FamilleParent.FRAME)
        val carte = sections.first { it.titre == CatalogueAttributs.TitreSection.CARTE }
        assertEquals(
            "Les trois courants de la § 7 ouvrent la carte",
            listOf("app:cardBackgroundColor", "app:cardCornerRadius", "app:cardElevation"),
            noms(carte).take(3),
        )
        assertTrue(
            noms(carte).containsAll(listOf("style", "app:strokeColor", "app:contentPadding", "android:checkable")),
        )
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
        // sections propres à la classe et AVANT les sections communes ;
        // PE11h — la section Autres attributs CLÔT la fiche (miroir le
        // `sec('Autres attributs', …)` en fin de `sectionsFor`) ; PE11 § 7 —
        // le bloc COMMUN s'étend (Apparence, Transformation, Interaction,
        // Accessibilité, Aperçu uniquement — miroir v6, même ordre).
        val sections =
            CatalogueAttributs.sections("LinearLayout", FamilleParent.RELATIVE, FamilleParent.LINEAR)
        assertEquals(
            listOf(
                CatalogueAttributs.TitreSection.IDENTITE,
                CatalogueAttributs.TitreSection.CONTENEUR,
                CatalogueAttributs.TitreSection.DANS_PARENT,
                CatalogueAttributs.TitreSection.POSITIONS_RELATIVES,
                CatalogueAttributs.TitreSection.DISPOSITION,
                CatalogueAttributs.TitreSection.APPARENCE,
                CatalogueAttributs.TitreSection.AFFICHAGE,
                CatalogueAttributs.TitreSection.TRANSFORMATION,
                CatalogueAttributs.TitreSection.INTERACTION,
                CatalogueAttributs.TitreSection.ACCESSIBILITE,
                CatalogueAttributs.TitreSection.APERCU,
                CatalogueAttributs.TitreSection.AUTRES,
            ),
            sections.map { section -> section.titre },
        )
    }

    // ── Classe inconnue et parents ───────────────────────────────────────

    @Test
    fun `une classe inconnue ne recoit que l'identite et les sections generiques`() {
        // PE11 § 7 — le bloc COMMUN de la v6 : Disposition, Apparence,
        // Affichage, Transformation, Interaction, Accessibilité, Aperçu
        // uniquement + Autres ; AUCUNE section propre (jamais un contrôle
        // qui pourrait être faux).
        val sections =
            CatalogueAttributs.sections("MockView", FamilleParent.AUTRE).map { it.titre }
        assertEquals(
            listOf(
                CatalogueAttributs.TitreSection.IDENTITE,
                CatalogueAttributs.TitreSection.DISPOSITION,
                CatalogueAttributs.TitreSection.APPARENCE,
                CatalogueAttributs.TitreSection.AFFICHAGE,
                CatalogueAttributs.TitreSection.TRANSFORMATION,
                CatalogueAttributs.TitreSection.INTERACTION,
                CatalogueAttributs.TitreSection.ACCESSIBILITE,
                CatalogueAttributs.TitreSection.APERCU,
                // PE11h — la section Autres attributs clôt TOUTE fiche non
                // racine, la vue dégradée comprise (elle édite le document).
                CatalogueAttributs.TitreSection.AUTRES,
            ),
            sections,
        )
    }

    @Test
    fun `le parent constraint ajoute les ancres lues puis les contraintes editables`() {
        // PE12b (spec § 2.3) : la famille CONSTRAINT couvre racine et
        // non-racine — l'ancienne distinction GenreParent ne servait à
        // rien, la section Contraintes est la même.
        // PE11h — le bloc des ancres LUES ouvre la section (annexe § 7
        // « Contraintes — ancres lues »), l'édition par côtés suit (PE8b).
        val sections =
            CatalogueAttributs
                .sections("TextView", FamilleParent.CONSTRAINT)
                .map { it.titre }
        assertTrue(sections.contains(CatalogueAttributs.TitreSection.CONTRAINTES))
        val contraintes =
            CatalogueAttributs
                .sections("TextView", FamilleParent.CONSTRAINT)
                .first { it.titre == CatalogueAttributs.TitreSection.CONTRAINTES }
        // PE11 § 7 — la section POURSUIT par les réglages du ConstraintLayout
        // (biais, pourcentage, ratio, bornes) : l'ORDRE ancres lues →
        // éditable reste la TÊTE de la liste (testés aux nouveaux tests § 7).
        assertEquals(
            listOf(
                CatalogueAttributs.ControleAttribut.ContraintesLecture(
                    CatalogueAttributs.Etiquette.CONTRAINTES_LUES,
                ),
                CatalogueAttributs.ControleAttribut.Contraintes,
            ),
            contraintes.controles.take(2),
        )
    }

    @Test
    fun `le parent lineaire ajoute poids gravite et action rapide`() {
        val dansParent =
            CatalogueAttributs
                .sections("TextView", FamilleParent.LINEAR)
                .first { it.titre == CatalogueAttributs.TitreSection.DANS_PARENT }
        assertEquals(
            listOf("android:layout_weight", "android:layout_gravity"),
            noms(dansParent),
        )
        // PE11h — l'action groupée « Remplir l'espace restant » clôt la
        // section (annexe § 7 : `—` ActionRapide, courant) ; elle ne porte
        // AUCUN nom (composite — jamais dans le badge « n définis »).
        val action =
            dansParent.controles.single { it is CatalogueAttributs.ControleAttribut.ActionRapide }
                as CatalogueAttributs.ControleAttribut.ActionRapide
        assertTrue(action.courant)
        assertEquals(CatalogueAttributs.Etiquette.ACTIONS, action.etiquette)
        assertEquals(
            CatalogueAttributs.ControleAttribut.ActionRapide.Action(
                CatalogueAttributs.Etiquette.REMPLIR_ESPACE,
                CatalogueAttributs.ControleAttribut.ActionRapide.Ecriture.RemplirEspaceRestant,
            ),
            action.actions.single(),
        )
        // Seul le parent LINÉAIRE porte l'action (miroir le `genre === 'lin'`).
        for (famille in listOf(FamilleParent.FRAME, FamilleParent.CONSTRAINT, FamilleParent.AUTRE)) {
            assertFalse(
                "la famille $famille ne porte pas d'action rapide",
                CatalogueAttributs
                    .sections("TextView", famille)
                    .any { section ->
                        section.controles.any { it is CatalogueAttributs.ControleAttribut.ActionRapide }
                    },
            )
        }
    }

    @Test
    fun `la section autres attributs clot la fiche des vues non racine`() {
        // PE11h — miroir le `sec('Autres attributs', [], {other:true})` de
        // `sectionsFor` : DERNIÈRE section, AUCUN contrôle du catalogue
        // (son corps vient du document), absente de la racine.
        for (famille in listOf(FamilleParent.LINEAR, FamilleParent.CONSTRAINT, FamilleParent.AUTRE)) {
            val sections = CatalogueAttributs.sections("Button", famille)
            assertEquals(
                "la section Autres attributs clôt la fiche ($famille)",
                CatalogueAttributs.TitreSection.AUTRES,
                sections.last().titre,
            )
            assertTrue(sections.last().autres)
            assertTrue(sections.last().controles.isEmpty())
            assertFalse(sections.first().autres)
        }
    }

    @Test
    fun `les noms connus couvrent les controles et les cinq attributs fixes`() {
        // PE11h — miroir `knownNames` : les noms portés par les contrôles
        // PLUS les cinq attributs que les composites couvrent sans les
        // porter (id, dimensions, marges, padding).
        val connus = CatalogueAttributs.nomsConnus("Button", FamilleParent.LINEAR, FamilleParent.LINEAR)
        assertTrue("android:id est couvert (composite identifiant)", "android:id" in connus)
        assertTrue("android:layout_width est couvert (composite dimensions)", "android:layout_width" in connus)
        assertTrue("android:layout_height est couvert (composite dimensions)", "android:layout_height" in connus)
        assertTrue("android:layout_margin est couvert (composite côtés)", "android:layout_margin" in connus)
        assertTrue("android:padding est couvert (composite côtés)", "android:padding" in connus)
        assertTrue("android:text est couvert (contrôle de la section Texte)", "android:text" in connus)
        assertTrue("android:layout_weight est couvert (parent linéaire)", "android:layout_weight" in connus)
        assertFalse("un nom hors catalogue n'est pas connu", "android:transitionName" in connus)
    }

    @Test
    fun `les noms libres suggeres sont les douze de la maquette`() {
        // PE11h — miroir FREE_NAMES (liste de référence figée) : la
        // complétion de l'ajout libre propose ces douze noms réels.
        assertEquals(
            listOf(
                "android:tag",
                "android:textDirection",
                "android:layoutDirection",
                "android:fadingEdge",
                "android:saveEnabled",
                "android:soundEffectsEnabled",
                "android:hapticFeedbackEnabled",
                "android:keepScreenOn",
                "android:requiresFadingEdge",
                "android:transitionName",
                "app:layout_goneMarginTop",
                "app:layout_constraintHorizontal_chainStyle",
            ),
            CatalogueAttributs.NOMS_LIBRES_SUGGERES,
        )
    }

    @Test
    fun `la racine n'a ni contraintes ni dans le parent`() {
        // PE11 § 7 — miroir le RETOUR ANTICIPÉ de `sectionsFor` v6 : la
        // racine vit Identité + RACINE + Conteneur (famille), SANS les
        // génériques ni Autres — la section Racine porte le fond, le
        // padding et compagnie (le piège § 5 de la 1re vague est clos).
        val titres =
            CatalogueAttributs
                .sections("LinearLayout", FamilleParent.AUCUN)
                .map { it.titre }
        assertFalse(titres.contains(CatalogueAttributs.TitreSection.CONTRAINTES))
        assertFalse(titres.contains(CatalogueAttributs.TitreSection.DANS_PARENT))
        // PE11h — la section Autres attributs est aussi ABSENTE de la
        // racine (miroir le retour anticipé de `sectionsFor`).
        assertFalse(titres.contains(CatalogueAttributs.TitreSection.AUTRES))
        // La RACINE remplace Disposition/Affichage (annexe « Racine »).
        assertTrue(titres.contains(CatalogueAttributs.TitreSection.RACINE))
        assertFalse(titres.contains(CatalogueAttributs.TitreSection.DISPOSITION))
        assertFalse(titres.contains(CatalogueAttributs.TitreSection.AFFICHAGE))
        // La fiche racine ENTIÈRE : Identité, Racine, Conteneur (LINEAR).
        assertEquals(
            listOf(
                CatalogueAttributs.TitreSection.IDENTITE,
                CatalogueAttributs.TitreSection.RACINE,
                CatalogueAttributs.TitreSection.CONTENEUR,
            ),
            titres,
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

    /**
     * Les valeurs de référence des LISTES § 7 (constantes de la maquette
     * v6 : SCALE, ALIGN, APPEAR, FONTS, WEIGHTS, ELLIP, IME, AUTOFILL,
     * TINTMODE, LMGR, OVERSCROLL, IMPORTANT_A11Y…).
     */
    private val referenceListes: Map<String, List<String>> =
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
            "android:textAlignment" to listOf("gravity", "textStart", "center", "textEnd", "viewStart", "viewEnd"),
            "android:textAppearance" to
                listOf(
                    "?attr/textAppearanceHeadlineLarge",
                    "?attr/textAppearanceHeadlineMedium",
                    "?attr/textAppearanceTitleLarge",
                    "?attr/textAppearanceTitleMedium",
                    "?attr/textAppearanceBodyLarge",
                    "?attr/textAppearanceBodyMedium",
                    "?attr/textAppearanceLabelLarge",
                ),
            "android:fontFamily" to
                listOf("sans-serif", "sans-serif-medium", "sans-serif-light", "serif", "monospace", "cursive"),
            "android:textFontWeight" to listOf("100", "200", "300", "400", "500", "600", "700", "800", "900"),
            "android:ellipsize" to listOf("none", "start", "middle", "end", "marquee"),
            "android:imeOptions" to
                listOf("actionNone", "actionGo", "actionSearch", "actionSend", "actionNext", "actionDone"),
            "android:autofillHints" to
                listOf(
                    "emailAddress",
                    "username",
                    "password",
                    "newPassword",
                    "name",
                    "phone",
                    "postalAddress",
                    "postalCode",
                    "smsOTPCode",
                ),
            "android:importantForAutofill" to listOf("auto", "yes", "no", "noExcludeDescendants"),
            "app:endIconMode" to
                listOf("none", "password_toggle", "clear_text", "custom", "dropdown_menu"),
            "app:iconGravity" to listOf("start", "textStart", "end", "textEnd", "top", "textTop"),
            "android:tintMode" to listOf("src_in", "src_atop", "multiply", "screen", "add"),
            "app:layoutManager" to
                listOf(
                    "androidx.recyclerview.widget.LinearLayoutManager",
                    "androidx.recyclerview.widget.GridLayoutManager",
                    "androidx.recyclerview.widget.StaggeredGridLayoutManager",
                ),
            "android:overScrollMode" to listOf("always", "ifContentScrolls", "never"),
            "android:importantForAccessibility" to listOf("auto", "yes", "no", "noHideDescendants"),
        )

    @Test
    fun `les valeurs d'enumeration sont valides pour la plateforme`() {
        // Listes de référence : attrs.xml d'AOSP (visibility) + Material
        // Components (boxBackgroundMode § 7 avec « none », fabSize) +
        // LinearLayout (orientation). PE11 § 7 : endIconMode quitte les CHOIX
        // (5 valeurs → LISTE testée plus bas), autoSizeTextType entre
        // (none, uniform — attrs.xml d'AOSP).
        val reference =
            mapOf(
                "app:boxBackgroundMode" to listOf("outline", "filled", "none"),
                "app:fabSize" to listOf("auto", "normal", "mini"),
                "android:orientation" to listOf("vertical", "horizontal"),
                "android:visibility" to listOf("visible", "invisible", "gone"),
                "android:autoSizeTextType" to listOf("none", "uniform"),
                "tools:visibility" to listOf("visible", "invisible", "gone"),
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
                "tools:visibility" to "visible",
                "app:boxBackgroundMode" to "outline",
                "android:orientation" to "vertical",
                "android:autoSizeTextType" to "none",
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
        // porte le contrôle LISTE (liste déroulante) — et PE11 § 7 en pose
        // BEAUCOUP (l'annexe liste même des enums COURTS : importantFor*,
        // overScrollMode, layoutManager — le catalogue curé décide, la
        // règle > 4 n'est que le DÉFAUT de dérivation). Les valeurs de
        // référence vivent en PROPRIÉTÉ (les constantes de la maquette v6 :
        // APPEAR, FONTS, WEIGHTS, ELLIP, IME, AUTOFILL, TINTMODE, LMGR…).
        val classeListes =
            classesCouvertes
                .flatMap { classe -> listesDe(classe).map { classe to it } }
                // Les TROIS listes « style » (Champ, Bouton « variante »,
                // Carte) portent le même NOM avec des VALEURS propres —
                // testées à part juste après.
                .filter { it.second.nom != "style" }
                .distinctBy { it.second.nom }
        for ((classe, liste) in classeListes) {
            assertEquals(
                "Valeurs de ${liste.nom} ($classe)",
                referenceListes[liste.nom],
                liste.valeurs,
            )
        }
    }

    @Test
    fun `les variantes de style § 7 - @style plein, affiche au dernier segment`() {
        // Les TROIS listes « style » (Champ, Bouton « variante », Carte)
        // portent le même NOM avec des VALEURS propres (annexe § 7) : la
        // VALEUR écrite est la forme `@style/` PLEINE, l'affiche retient le
        // DERNIER SEGMENT (miroir `x.replace(/^.*[./]/, '')`).
        assertEquals(
            "La variante du Button",
            listOf(
                "@style/Widget.Material3.Button",
                "@style/Widget.Material3.Button.OutlinedButton",
                "@style/Widget.Material3.Button.TextButton",
                "@style/Widget.Material3.Button.TonalButton",
                "@style/Widget.Material3.Button.ElevatedButton",
            ),
            listesDe("Button").first { it.nom == "style" }.valeurs,
        )
        assertEquals(
            "La variante du TextInputLayout",
            listOf(
                "@style/Widget.Material3.TextInputLayout.OutlinedBox",
                "@style/Widget.Material3.TextInputLayout.FilledBox",
                "@style/Widget.Material3.TextInputLayout.OutlinedBox.Dense",
            ),
            listesDe("TextInputLayout").first { it.nom == "style" }.valeurs,
        )
        assertEquals(
            "La variante de la MaterialCardView",
            listOf(
                "@style/Widget.Material3.CardView.Elevated",
                "@style/Widget.Material3.CardView.Filled",
                "@style/Widget.Material3.CardView.Outlined",
            ),
            listesDe("MaterialCardView").first { it.nom == "style" }.valeurs,
        )
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
                // PE11 § 7 (Lignes et débordement) : le masque des liens
                // automatiques (AUTOLINK de la maquette).
                "android:autoLink" to listOf("web", "email", "phone", "map", "all"),
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
                .sections("ImageView", FamilleParent.AUTRE)
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
    fun `le drapeau courant suit l annexe § 7 - huit sections maximum en mode courants`() {
        // PE11 § 7 (brief PE11d) : le champ `courant` de CHAQUE contrôle
        // suit la DERNIÈRE COLONNE de l'annexe — l'IDENTITÉ (id c:0, tag)
        // n'est PAS courante, Apparence/Transformation/Interaction/
        // Accessibilité ne montrent que leurs têtes (background s'écrit en
        // Racine, enabled garde son étoile, contentDescription aussi), et
        // AUCUNE classe n'affiche PLUS DE HUIT SECTIONS en mode Courants.
        for (classe in classesCouvertes) {
            for ((famille, plafond) in listOf(
                FamilleParent.CONSTRAINT to 8,
                FamilleParent.LINEAR to 8,
                FamilleParent.FRAME to 8,
                FamilleParent.COORDINATOR to 8,
                FamilleParent.APPBAR to 8,
                FamilleParent.RELATIVE to 9,
            )) {
                // RELATIVE porte DEUX sections de parent (PE12c — ancrage +
                // positions relatives) : son plafond monte à NEUF, l'écart
                // voulu de la spec § 5.2 (le « 6 à 8 sections » du brief
                // PE11d précédait PE12c — la 2e vague ne le régresse pas).
                val visibles =
                    CatalogueAttributs
                        .sections(classe, famille)
                        .filter { section -> section.controles.any { it.courant } }
                assertTrue(
                    "La fiche Courants de $classe (parent $famille) dépasse $plafond sections : " +
                        visibles.map { it.titre },
                    visibles.size <= plafond,
                )
            }
        }
        // L'IDENTITÉ n'est plus courante (miroir `c:0` du champ id v6).
        val identite =
            CatalogueAttributs
                .sections("TextView", FamilleParent.AUTRE)
                .first { it.titre == CatalogueAttributs.TitreSection.IDENTITE }
        assertTrue(identite.controles.none { it.courant })
        assertEquals(listOf("android:id", "android:tag"), noms(identite))
        // Les étoiles déménagées : enabled (Interaction), contentDescription
        // (Accessibilité), onClick (Interaction, validé) restent courants ;
        // l'AFFICHAGE se réduit à visibility + alpha.
        val generiques = CatalogueAttributs.sections("TextView", FamilleParent.AUTRE)
        assertEquals(
            listOf("android:visibility", "android:alpha"),
            noms(generiques.first { it.titre == CatalogueAttributs.TitreSection.AFFICHAGE }),
        )
        assertTrue(
            generiques
                .first { it.titre == CatalogueAttributs.TitreSection.APPARENCE }
                .controles
                .none { it.courant },
        )
        assertTrue(
            generiques
                .first { it.titre == CatalogueAttributs.TitreSection.TRANSFORMATION }
                .controles
                .none { it.courant },
        )
        assertTrue(
            "Le Contrôleur du Conteneur LINEAR garde ses deux étoiles PE12c",
            CatalogueAttributs
                .sections("LinearLayout", FamilleParent.AUTRE)
                .first { it.titre == CatalogueAttributs.TitreSection.CONTENEUR }
                .controles
                .take(2)
                .all { it.courant },
        )
    }

    @Test
    fun `tout attribut de couleur porte le controle Couleur - usager textColor`() {
        // Règle § 9 (PE11e) : un attribut de COULEUR n'est jamais un
        // champ texte libre — le contrôle `col` de la maquette (pastille
        // + hex + Palette). Premier usager du catalogue : android:textColor.
        // (parent AUTRE — AUCUN est la RACINE depuis § 7, retour anticipé.)
        val texte =
            CatalogueAttributs
                .sections("TextView", FamilleParent.AUTRE)
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
                .sections("TextView", FamilleParent.AUTRE)
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

    // ── PE11 § 7 : le catalogue curé de la deuxième vague ──────────────────

    /** Les contrôles d'une section donnée (helper des tests § 7). */
    private fun controlesDe(
        classe: String,
        titre: CatalogueAttributs.TitreSection,
    ): List<CatalogueAttributs.ControleAttribut> =
        CatalogueAttributs
            .sections(classe, FamilleParent.AUTRE)
            .first { it.titre == titre }
            .controles

    @Test
    fun `la saisie gagne imeOptions et longueur max - maxLines demenage en lignes`() {
        // Annexe § 7 « Saisie » : hint, inputType, imeOptions et maxLength
        // en courants, digits/autofill/selectAll/cursorVisible/textColorHint
        // ensuite — maxLines a DÉMÉNAGÉ vers « Lignes et débordement »
        // (bornes [1 à 50], non courant).
        assertEquals(
            listOf(
                "android:hint",
                "android:inputType",
                "android:imeOptions",
                "android:maxLength",
                "android:digits",
                "android:autofillHints",
                "android:importantForAutofill",
                "android:selectAllOnFocus",
                "android:cursorVisible",
                "android:textColorHint",
            ),
            controlesDe("EditText", CatalogueAttributs.TitreSection.SAISIE).flatMap { it.nomsAttributs },
        )
        assertFalse(
            "maxLength est courant",
            controlesDe("EditText", CatalogueAttributs.TitreSection.SAISIE)[3].courant.not(),
        )
        val lignes = controlesDe("EditText", CatalogueAttributs.TitreSection.LIGNES)
        assertEquals(
            "maxLines ouvre Lignes et débordement",
            "android:maxLines",
            lignes.first().nomsAttributs.single(),
        )
        val maxLines =
            lignes
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Nombre>()
                .first { it.nom == "android:maxLines" }
        assertEquals(CatalogueAttributs.Bornes(1f, 50f), maxLines.bornes)
        assertFalse(maxLines.courant)
    }

    @Test
    fun `le champ gagne aides compteur icones et contour - les coins comptent quatre attributs`() {
        // Annexe § 7 « Champ » : + style et hintEnabled ; Aides et compteur,
        // Icônes (endIconMode passe à la LISTE à cinq valeurs), Contour.
        assertTrue(
            controlesDe("TextInputLayout", CatalogueAttributs.TitreSection.CHAMP)
                .flatMap { it.nomsAttributs }
                .containsAll(listOf("style", "app:hintEnabled")),
        )
        assertEquals(
            listOf(
                "app:helperText",
                "app:errorEnabled",
                "app:counterEnabled",
                "app:counterMaxLength",
                "app:placeholderText",
                "app:prefixText",
                "app:suffixText",
            ),
            controlesDe("TextInputLayout", CatalogueAttributs.TitreSection.AIDES_CHAMP).flatMap { it.nomsAttributs },
        )
        // Le contrôle COINS porte les QUATRE attributs de coin (miroir
        // `[c.a].concat(c.also)`) — un par champ de la rangée `.mrow`.
        val coins =
            controlesDe("TextInputLayout", CatalogueAttributs.TitreSection.CONTOUR)
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Coins>()
                .single()
        assertEquals(
            listOf(
                "app:boxCornerRadiusTopStart",
                "app:boxCornerRadiusTopEnd",
                "app:boxCornerRadiusBottomEnd",
                "app:boxCornerRadiusBottomStart",
            ),
            coins.nomsAttributs,
        )
    }

    @Test
    fun `l etat separe la case de l interrupteur - l image garde ses bornes max`() {
        // Annexe § 7 « État » : la CheckBox teint sa CASE (buttonTint +
        // button), l'interrupteur teint POUCE et PISTE (+ textOn/textOff).
        assertEquals(
            listOf("android:checked", "app:buttonTint", "android:button"),
            controlesDe("CheckBox", CatalogueAttributs.TitreSection.ETAT).flatMap { it.nomsAttributs },
        )
        assertEquals(
            listOf(
                "android:checked",
                "android:thumbTint",
                "android:trackTint",
                "android:showText",
                "android:textOn",
                "android:textOff",
            ),
            controlesDe("Switch", CatalogueAttributs.TitreSection.ETAT).flatMap { it.nomsAttributs },
        )
        // Annexe « Image » : srcCompat, adjustViewBounds, tint, tintMode,
        // cropToPadding et LES BORNES MAX — que la DISPOSITION de la famille
        // image ne répète PAS (miroir le filtre /max/ v6).
        assertEquals(
            listOf(
                "android:src",
                "app:srcCompat",
                "android:scaleType",
                "android:adjustViewBounds",
                "app:tint",
                "android:tintMode",
                "android:cropToPadding",
                "android:maxWidth",
                "android:maxHeight",
            ),
            controlesDe("ImageView", CatalogueAttributs.TitreSection.IMAGE).flatMap { it.nomsAttributs },
        )
        val dispositionImage =
            controlesDe("ImageView", CatalogueAttributs.TitreSection.DISPOSITION)
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Nombre>()
                .map { it.nom }
        assertFalse("Pas de maxWidth dans la Disposition de l'image", dispositionImage.contains("android:maxWidth"))
        assertFalse("Pas de maxHeight dans la Disposition de l'image", dispositionImage.contains("android:maxHeight"))
        assertTrue("minHeight y reste", dispositionImage.contains("android:minHeight"))
        assertTrue("minWidth y reste", dispositionImage.contains("android:minWidth"))
    }

    @Test
    fun `les communs § 7 - apparence transformation interaction accessibilite et apercu`() {
        // Annexe § 7, sections communes : Apparence (6), Transformation
        // (6 curseurs), Interaction (enabled + onClick validé en courants),
        // Accessibilité (contentDescription, importantForAccessibility,
        // accessibilityHeading, labelFor en RÉFÉRENCE D'ID portée TOUT,
        // tooltipText, screenReaderFocusable), Aperçu uniquement (note
        // OUTILS_APERCU + la première entrée suit la classe).
        assertEquals(
            listOf(
                "android:background",
                "android:backgroundTint",
                "android:foreground",
                "android:elevation",
                "android:clipToOutline",
                "android:theme",
            ),
            controlesDe("TextView", CatalogueAttributs.TitreSection.APPARENCE).flatMap { it.nomsAttributs },
        )
        assertEquals(
            listOf(
                "android:rotation",
                "android:scaleX",
                "android:scaleY",
                "android:translationX",
                "android:translationY",
                "android:translationZ",
            ),
            controlesDe("TextView", CatalogueAttributs.TitreSection.TRANSFORMATION).flatMap { it.nomsAttributs },
        )
        val interaction = controlesDe("TextView", CatalogueAttributs.TitreSection.INTERACTION)
        assertEquals(
            "onClick porte la validation de nom de méthode",
            CatalogueAttributs.GenreValidationTexte.NOM_METHODE_JAVA,
            interaction
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Texte>()
                .first { it.nom == "android:onClick" }
                .validation,
        )
        val acces = controlesDe("TextView", CatalogueAttributs.TitreSection.ACCESSIBILITE)
        assertEquals(
            listOf(
                "android:contentDescription",
                "android:importantForAccessibility",
                "android:accessibilityHeading",
                "android:labelFor",
                "android:tooltipText",
                "android:screenReaderFocusable",
            ),
            acces.flatMap { it.nomsAttributs },
        )
        assertEquals(
            "labelFor est une référence d'id portée TOUT (mémo § 2)",
            CatalogueAttributs.ControleAttribut.ReferenceId.Portee.TOUT,
            acces
                .filterIsInstance<CatalogueAttributs.ControleAttribut.ReferenceId>()
                .first { it.nom == "android:labelFor" }
                .portee,
        )
    }

    @Test
    fun `l apercu uniquement suit la classe - tools text src et itemCount`() {
        // Annexe § 7 « Aperçu uniquement » : la PREMIÈRE entrée suit la
        // classe (tools:text pour la famille texte, tools:src pour l'image,
        // tools:itemCount pour RecyclerView, RIEN pour les autres), puis
        // tools:visibility et tools:ignore pour toutes — avec la NOTE
        // dédiée (miroir la `note` de la maquette).
        val apercu =
            CatalogueAttributs
                .sections("TextView", FamilleParent.AUTRE)
                .first { it.titre == CatalogueAttributs.TitreSection.APERCU }
        assertEquals(CatalogueAttributs.NoteSection.OUTILS_APERCU, apercu.note)
        assertEquals(
            "tools:text pour la famille texte",
            listOf(
                "tools:text",
                "tools:visibility",
                "tools:ignore",
            ),
            apercu.controles.flatMap {
                it.nomsAttributs
            },
        )
        assertEquals(
            "tools:src pour l'image",
            listOf("tools:src", "tools:visibility", "tools:ignore"),
            controlesDe("ImageView", CatalogueAttributs.TitreSection.APERCU).flatMap { it.nomsAttributs },
        )
        assertEquals(
            "tools:itemCount pour RecyclerView",
            listOf("tools:itemCount", "tools:visibility", "tools:ignore"),
            controlesDe("RecyclerView", CatalogueAttributs.TitreSection.APERCU).flatMap { it.nomsAttributs },
        )
        assertEquals(
            "Rien de classe pour LinearLayout",
            listOf("tools:visibility", "tools:ignore"),
            controlesDe("LinearLayout", CatalogueAttributs.TitreSection.APERCU).flatMap { it.nomsAttributs },
        )
    }

    @Test
    fun `les contraintes gagnent biais pourcentage ratio et bornes de largeur`() {
        // Annexe § 7 « Contraintes » : après les ancres LUES (PE11h) et
        // l'éditable par côtés (PE8b), les réglages du ConstraintLayout —
        // biais H courant, biais V désactivé sans contraintes haut+bas,
        // pourcentage, RATIO (premier usager du contrôle), bornes min/max.
        val contraintes =
            CatalogueAttributs
                .sections("Button", FamilleParent.CONSTRAINT)
                .first { it.titre == CatalogueAttributs.TitreSection.CONTRAINTES }
        assertEquals(
            listOf(
                "app:layout_constraintHorizontal_bias",
                "app:layout_constraintVertical_bias",
                "app:layout_constraintWidth_percent",
                "app:layout_constraintDimensionRatio",
                "app:layout_constraintWidth_min",
                "app:layout_constraintWidth_max",
            ),
            contraintes.controles.drop(2).flatMap { it.nomsAttributs },
        )
        val horizontal =
            contraintes.controles
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Curseur>()
                .first { it.nom == "app:layout_constraintHorizontal_bias" }
        assertTrue("Le biais horizontal est courant (annexe ★)", horizontal.courant)
        assertEquals(0.5f, horizontal.defaut)
        val vertical =
            contraintes.controles
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Curseur>()
                .first { it.nom == "app:layout_constraintVertical_bias" }
        assertFalse("Le biais vertical n'est pas courant", vertical.courant)
        assertTrue(
            "Le biais vertical se désactive sans contraintes haut ET bas",
            vertical.requiertContraintesVerticales,
        )
        val ratio =
            contraintes.controles
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Ratio>()
                .single()
        assertEquals("app:layout_constraintDimensionRatio", ratio.nom)
        assertEquals(CatalogueAttributs.Etiquette.RATIO, ratio.etiquette)
    }

    @Test
    fun `la racine porte tools context valide - fond couleur et padding`() {
        // Annexe § 7 « Racine » : tools:context COURANT et VALIDÉ (nom
        // d'activité), background couleur, padding côtés, ajustement
        // système, découpage et hauteur min — le tout après le retour
        // anticipé, SANS les génériques.
        val racine =
            CatalogueAttributs
                .sections("ConstraintLayout", FamilleParent.AUCUN, FamilleParent.CONSTRAINT)
                .first { it.titre == CatalogueAttributs.TitreSection.RACINE }
        assertEquals(
            listOf(
                "tools:context",
                "android:background",
                "android:padding",
                "android:fitsSystemWindows",
                "android:clipToPadding",
                "android:minHeight",
            ),
            racine.controles.flatMap { it.nomsAttributs },
        )
        val contexte =
            racine.controles
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Texte>()
                .first { it.nom == "tools:context" }
        assertEquals(CatalogueAttributs.GenreValidationTexte.NOM_ACTIVITE, contexte.validation)
        assertTrue("tools:context est courant (annexe ★)", contexte.courant)
        assertTrue(
            "android:background est le contrôle couleur de la Racine",
            racine.controles.any {
                it is CatalogueAttributs.ControleAttribut.Couleur && it.nom == "android:background"
            },
        )
    }

    @Test
    fun `l identite gagne android tag - le switch n a pas d icones du texte`() {
        // Annexe § 7 « Identité — toutes les vues » : android:id (renommage)
        // + android:tag (texte). « Icônes du texte » suit le miroir v6 :
        // `T && k !== 'switch' || k === 'edit'` — l'INTERRUPTEUR n'en a pas.
        assertTrue(
            "Le Switch n'a pas la section Icônes du texte",
            CatalogueAttributs
                .sections("Switch", FamilleParent.AUTRE)
                .none { it.titre == CatalogueAttributs.TitreSection.ICONES_TEXTE },
        )
        assertTrue(
            "La CheckBox a la section Icônes du texte",
            CatalogueAttributs
                .sections("CheckBox", FamilleParent.AUTRE)
                .any { it.titre == CatalogueAttributs.TitreSection.ICONES_TEXTE },
        )
        assertTrue(
            "L'EditText a la section Icônes du texte",
            CatalogueAttributs
                .sections("EditText", FamilleParent.AUTRE)
                .any { it.titre == CatalogueAttributs.TitreSection.ICONES_TEXTE },
        )
    }
}
