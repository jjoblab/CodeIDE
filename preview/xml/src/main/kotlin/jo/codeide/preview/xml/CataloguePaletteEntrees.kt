package jo.codeide.preview.xml

import jo.codeide.preview.xml.CataloguePalette.DEPENDANCE_APPCOMPAT
import jo.codeide.preview.xml.CataloguePalette.DEPENDANCE_CONSTRAINTLAYOUT
import jo.codeide.preview.xml.CataloguePalette.DEPENDANCE_DRAWERLAYOUT
import jo.codeide.preview.xml.CataloguePalette.DEPENDANCE_MATERIAL
import jo.codeide.preview.xml.CataloguePalette.DEPENDANCE_RECYCLERVIEW
import jo.codeide.preview.xml.CataloguePalette.DEPENDANCE_SWIPEREFRESH
import jo.codeide.preview.xml.CataloguePalette.DEPENDANCE_VIEWPAGER2
import jo.codeide.preview.xml.CataloguePalette.EntreePalette

/**
 * Les ENTRÉES du catalogue de la palette, PAR VAGUE (v0.170.0 — découpe
 * LargeClass de detekt, règle 8 : le catalogue est une TABLE DE DONNÉES,
 * les trois vagues vivent dans leur ordre d'arrivée — [CataloguePalette]
 * les COMPOSE en préservant l'ordre : les 22 de la maquette `WIDGETS`,
 * puis les 19 de l'extension v0.166.3, puis les 23 de l'extension S4
 * v0.170.0. Chaque vague garde ses commentaires de traçabilité ; les
 * conventions (identifiant en base camelCase du nom simple de classe,
 * extrait multi-lignes sans contrainte ni marge) sont celles du
 * [CataloguePalette] et de la maquette § « Catalogue et insertion XML ».
 *
 * Listes top-level INTERNE : le membre public reste [CataloguePalette.CATALOGUE].
 */

internal val ENTREES_MAQUETTE: List<CataloguePalette.EntreePalette> =
    listOf(
        EntreePalette(
            id = "tv",
            nomAffiche = "TextView",
            classe = "TextView",
            categorie = "Texte",
            extraitXml =
            "<TextView\n" +
                "    android:id=\"@+id/textView\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:text=\"TextView\" />",
        ),
        EntreePalette(
            id = "et",
            nomAffiche = "Plain Text",
            classe = "EditText",
            categorie = "Texte",
            extraitXml =
            "<EditText\n" +
                "    android:id=\"@+id/editText\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"48dp\"\n" +
                "    android:hint=\"Nom\"\n" +
                "    android:inputType=\"text\" />",
        ),
        EntreePalette(
            id = "til",
            nomAffiche = "TextInputLayout",
            classe = "TextInputLayout",
            categorie = "Texte",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.textfield.TextInputLayout\n" +
                "    android:id=\"@+id/textInputLayout\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:hint=\"Libellé\">\n" +
                "\n" +
                "    <com.google.android.material.textfield.TextInputEditText\n" +
                "        android:layout_width=\"match_parent\"\n" +
                "        android:layout_height=\"wrap_content\" />\n" +
                "\n" +
                "</com.google.android.material.textfield.TextInputLayout>",
        ),
        EntreePalette(
            id = "btn",
            nomAffiche = "Button",
            classe = "Button",
            categorie = "Boutons",
            extraitXml =
            "<Button\n" +
                "    android:id=\"@+id/button\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"48dp\"\n" +
                "    android:text=\"Button\" />",
        ),
        EntreePalette(
            id = "ib",
            nomAffiche = "ImageButton",
            classe = "ImageButton",
            categorie = "Boutons",
            extraitXml =
            "<ImageButton\n" +
                "    android:id=\"@+id/imageButton\"\n" +
                "    android:layout_width=\"48dp\"\n" +
                "    android:layout_height=\"48dp\"\n" +
                "    android:contentDescription=\"\" />",
        ),
        EntreePalette(
            id = "cb",
            nomAffiche = "CheckBox",
            classe = "CheckBox",
            categorie = "Boutons",
            extraitXml =
            "<CheckBox\n" +
                "    android:id=\"@+id/checkBox\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"48dp\"\n" +
                "    android:text=\"CheckBox\" />",
        ),
        EntreePalette(
            id = "sw",
            nomAffiche = "Switch",
            classe = "Switch",
            categorie = "Boutons",
            extraitXml =
            "<Switch\n" +
                "    android:id=\"@+id/switch1\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"48dp\"\n" +
                "    android:text=\"Switch\" />",
        ),
        EntreePalette(
            id = "llv",
            nomAffiche = "LinearLayout (V)",
            classe = "LinearLayout",
            categorie = "Conteneurs",
            extraitXml =
            "<LinearLayout\n" +
                "    android:id=\"@+id/linearLayout\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:minHeight=\"96dp\"\n" +
                "    android:orientation=\"vertical\" />",
        ),
        EntreePalette(
            id = "llh",
            nomAffiche = "LinearLayout (H)",
            classe = "LinearLayout",
            categorie = "Conteneurs",
            extraitXml =
            "<LinearLayout\n" +
                "    android:id=\"@+id/linearLayout2\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:minHeight=\"96dp\"\n" +
                "    android:orientation=\"horizontal\" />",
        ),
        EntreePalette(
            id = "card",
            nomAffiche = "MaterialCardView",
            classe = "MaterialCardView",
            categorie = "Conteneurs",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.card.MaterialCardView\n" +
                "    android:id=\"@+id/materialCardView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:padding=\"16dp\" />",
        ),
        // PE12h — les six entrées de la vague « familles de parents »
        // (spec § 11, miroir `WIDGETS` : fl, rl, cl, abl, tb, fab —
        // même position dans le catalogue que la maquette, entre la
        // carte et les médias).
        EntreePalette(
            id = "fl",
            nomAffiche = "FrameLayout",
            classe = "FrameLayout",
            categorie = "Conteneurs",
            extraitXml =
            "<FrameLayout\n" +
                "    android:id=\"@+id/frameLayout\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:minHeight=\"96dp\" />",
        ),
        EntreePalette(
            id = "rl",
            nomAffiche = "RelativeLayout",
            classe = "RelativeLayout",
            categorie = "Conteneurs",
            extraitXml =
            "<RelativeLayout\n" +
                "    android:id=\"@+id/relativeLayout\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:minHeight=\"96dp\" />",
        ),
        EntreePalette(
            id = "cl",
            nomAffiche = "CoordinatorLayout",
            classe = "CoordinatorLayout",
            categorie = "Conteneurs",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<androidx.coordinatorlayout.widget.CoordinatorLayout\n" +
                "    android:id=\"@+id/coordinatorLayout\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"160dp\" />",
        ),
        EntreePalette(
            id = "abl",
            nomAffiche = "AppBarLayout",
            classe = "AppBarLayout",
            categorie = "Conteneurs",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.appbar.AppBarLayout\n" +
                "    android:id=\"@+id/appBarLayout\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:minHeight=\"56dp\" />",
        ),
        EntreePalette(
            id = "tb",
            nomAffiche = "MaterialToolbar",
            classe = "MaterialToolbar",
            categorie = "Autres",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.appbar.MaterialToolbar\n" +
                "    android:id=\"@+id/materialToolbar\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"56dp\"\n" +
                "    app:title=\"Titre\" />",
        ),
        EntreePalette(
            id = "fab",
            nomAffiche = "FloatingActionButton",
            classe = "FloatingActionButton",
            categorie = "Boutons",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.floatingactionbutton.FloatingActionButton\n" +
                "    android:id=\"@+id/floatingActionButton\"\n" +
                "    android:layout_width=\"56dp\"\n" +
                "    android:layout_height=\"56dp\"\n" +
                "    app:srcCompat=\"@drawable/ic_search\"\n" +
                "    android:contentDescription=\"Action\" />",
        ),
        EntreePalette(
            id = "iv",
            nomAffiche = "ImageView",
            classe = "ImageView",
            categorie = "Médias",
            extraitXml =
            "<ImageView\n" +
                "    android:id=\"@+id/imageView\"\n" +
                "    android:layout_width=\"96dp\"\n" +
                "    android:layout_height=\"96dp\"\n" +
                "    android:contentDescription=\"\" />",
        ),
        EntreePalette(
            id = "rv",
            nomAffiche = "RecyclerView",
            classe = "RecyclerView",
            categorie = "Listes",
            dependanceRequise = DEPENDANCE_RECYCLERVIEW,
            extraitXml =
            "<androidx.recyclerview.widget.RecyclerView\n" +
                "    android:id=\"@+id/recyclerView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"160dp\" />",
        ),
        EntreePalette(
            id = "pb",
            nomAffiche = "ProgressBar",
            classe = "ProgressBar",
            categorie = "Autres",
            extraitXml =
            "<ProgressBar\n" +
                "    android:id=\"@+id/progressBar\"\n" +
                "    style=\"?android:attr/progressBarStyleHorizontal\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\" />",
        ),
        EntreePalette(
            id = "sk",
            nomAffiche = "SeekBar",
            classe = "SeekBar",
            categorie = "Autres",
            extraitXml =
            "<SeekBar\n" +
                "    android:id=\"@+id/seekBar\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"48dp\" />",
        ),
        EntreePalette(
            id = "dv",
            nomAffiche = "Séparateur",
            classe = "View",
            categorie = "Autres",
            extraitXml =
            "<View\n" +
                "    android:id=\"@+id/separateur\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"1dp\" />",
        ),
        EntreePalette(
            id = "sp",
            nomAffiche = "Space",
            classe = "Space",
            categorie = "Autres",
            extraitXml =
            "<Space\n" +
                "    android:id=\"@+id/space\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"16dp\" />",
        ),
    )

internal val ENTREES_EXTENSION_V0166: List<CataloguePalette.EntreePalette> =
    listOf(
        // ── v0.166.3 — EXTENSION PROPRIÉTAIRE « palette plus complète »
        // ────────────────────────────────────────────────────────────
        // 19 entrées AU-DELÀ de la maquette v6 (demande propriétaire du
        // 2026-10-22 : « l'écran palettes n'affiche que peu, il faut
        // qu'il soit plus complet »). La maquette reste la source de
        // vérité pour ses 22 entrées (ordre du catalogue inchangé,
        // miroir `WIDGETS`) ; les extensions s'APPENDENT à la fin,
        // catégorisées comme les autres, avec les MÊMES conventions :
        // identifiant en base camelCase du nom simple de classe
        // (miroir `nextId` — renuméroté par [InsertionXml.renumeroter]),
        // extrait multi-lignes sans contrainte ni marge (le façonnage
        // au dépôt reste [InsertionXml.ajouterAttributs]). Les
        // fiches d'attributs suivent la même extension côté
        // [CatalogueAttributs] (familleSaisie/État/Progression).
        // Texte — AutoCompleteTextView (EditText descendant).
        EntreePalette(
            id = "act",
            nomAffiche = "AutoCompleteTextView",
            classe = "AutoCompleteTextView",
            categorie = "Texte",
            extraitXml =
            "<AutoCompleteTextView\n" +
                "    android:id=\"@+id/autoCompleteTextView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"48dp\"\n" +
                "    android:hint=\"Recherche\" />",
        ),
        // Boutons — RadioButton, ToggleButton, MaterialButton, Chip.
        EntreePalette(
            id = "rb",
            nomAffiche = "RadioButton",
            classe = "RadioButton",
            categorie = "Boutons",
            extraitXml =
            "<RadioButton\n" +
                "    android:id=\"@+id/radioButton\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"48dp\"\n" +
                "    android:text=\"RadioButton\" />",
        ),
        EntreePalette(
            id = "tg",
            nomAffiche = "ToggleButton",
            classe = "ToggleButton",
            categorie = "Boutons",
            extraitXml =
            "<ToggleButton\n" +
                "    android:id=\"@+id/toggleButton\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"48dp\" />",
        ),
        EntreePalette(
            id = "mb",
            nomAffiche = "MaterialButton",
            classe = "MaterialButton",
            categorie = "Boutons",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.button.MaterialButton\n" +
                "    android:id=\"@+id/materialButton\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"48dp\"\n" +
                "    android:text=\"MaterialButton\" />",
        ),
        EntreePalette(
            id = "chip",
            nomAffiche = "Chip",
            classe = "Chip",
            categorie = "Boutons",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.chip.Chip\n" +
                "    android:id=\"@+id/chip\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:text=\"Chip\" />",
        ),
        // Conteneurs — ScrollView, HorizontalScrollView, NestedScrollView,
        // ConstraintLayout, GridLayout, TabLayout, ViewPager2.
        EntreePalette(
            id = "sv",
            nomAffiche = "ScrollView",
            classe = "ScrollView",
            categorie = "Conteneurs",
            extraitXml =
            "<ScrollView\n" +
                "    android:id=\"@+id/scrollView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:minHeight=\"96dp\" />",
        ),
        EntreePalette(
            id = "hsv",
            nomAffiche = "HorizontalScrollView",
            classe = "HorizontalScrollView",
            categorie = "Conteneurs",
            extraitXml =
            "<HorizontalScrollView\n" +
                "    android:id=\"@+id/horizontalScrollView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:minHeight=\"96dp\" />",
        ),
        EntreePalette(
            id = "nsv",
            nomAffiche = "NestedScrollView",
            classe = "NestedScrollView",
            categorie = "Conteneurs",
            // androidx.core est transitive de TOUT projet AndroidX ou
            // Material : aucune coordonnée à signaler sur la tuile.
            extraitXml =
            "<androidx.core.widget.NestedScrollView\n" +
                "    android:id=\"@+id/nestedScrollView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:minHeight=\"96dp\" />",
        ),
        EntreePalette(
            id = "ctl",
            nomAffiche = "ConstraintLayout",
            classe = "ConstraintLayout",
            categorie = "Conteneurs",
            dependanceRequise = DEPENDANCE_CONSTRAINTLAYOUT,
            extraitXml =
            "<androidx.constraintlayout.widget.ConstraintLayout\n" +
                "    android:id=\"@+id/constraintLayout\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:minHeight=\"96dp\" />",
        ),
        EntreePalette(
            id = "gl",
            nomAffiche = "GridLayout",
            classe = "GridLayout",
            categorie = "Conteneurs",
            extraitXml =
            "<GridLayout\n" +
                "    android:id=\"@+id/gridLayout\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:minHeight=\"96dp\"\n" +
                "    android:columnCount=\"2\" />",
        ),
        EntreePalette(
            id = "tl",
            nomAffiche = "TabLayout",
            classe = "TabLayout",
            categorie = "Conteneurs",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.tabs.TabLayout\n" +
                "    android:id=\"@+id/tabLayout\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"48dp\" />",
        ),
        EntreePalette(
            id = "vp",
            nomAffiche = "ViewPager2",
            classe = "ViewPager2",
            categorie = "Conteneurs",
            dependanceRequise = DEPENDANCE_VIEWPAGER2,
            extraitXml =
            "<androidx.viewpager2.widget.ViewPager2\n" +
                "    android:id=\"@+id/viewPager2\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"160dp\" />",
        ),
        // Médias — VideoView.
        EntreePalette(
            id = "vv",
            nomAffiche = "VideoView",
            classe = "VideoView",
            categorie = "Médias",
            extraitXml =
            "<VideoView\n" +
                "    android:id=\"@+id/videoView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"160dp\" />",
        ),
        // Listes — Spinner, ListView, GridView.
        EntreePalette(
            id = "spn",
            nomAffiche = "Spinner",
            classe = "Spinner",
            categorie = "Listes",
            extraitXml =
            "<Spinner\n" +
                "    android:id=\"@+id/spinner\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"48dp\" />",
        ),
        EntreePalette(
            id = "lv",
            nomAffiche = "ListView",
            classe = "ListView",
            categorie = "Listes",
            extraitXml =
            "<ListView\n" +
                "    android:id=\"@+id/listView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"160dp\" />",
        ),
        EntreePalette(
            id = "gv",
            nomAffiche = "GridView",
            classe = "GridView",
            categorie = "Listes",
            extraitXml =
            "<GridView\n" +
                "    android:id=\"@+id/gridView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"160dp\"\n" +
                "    android:numColumns=\"auto_fit\" />",
        ),
        // Autres — WebView, RatingBar, SearchView.
        EntreePalette(
            id = "web",
            nomAffiche = "WebView",
            classe = "WebView",
            categorie = "Autres",
            extraitXml =
            "<WebView\n" +
                "    android:id=\"@+id/webView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"160dp\" />",
        ),
        EntreePalette(
            id = "rat",
            nomAffiche = "RatingBar",
            classe = "RatingBar",
            categorie = "Autres",
            extraitXml =
            "<RatingBar\n" +
                "    android:id=\"@+id/ratingBar\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:numStars=\"5\" />",
        ),
        EntreePalette(
            id = "srch",
            nomAffiche = "SearchView",
            classe = "SearchView",
            categorie = "Autres",
            extraitXml =
            "<SearchView\n" +
                "    android:id=\"@+id/searchView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"48dp\" />",
        ),
    )

internal val ENTREES_EXTENSION_V0170: List<CataloguePalette.EntreePalette> =
    listOf(
        // ── v0.170.0 — EXTENSION S4 « palette proche d'Android Studio »
        // ────────────────────────────────────────────────────────────
        // 23 entrées AU-DELÀ des 41 (demande propriétaire du 2026-10-23 :
        // « enrichir les palettes, plus proche du réel »). MÊMES
        // conventions que la vague v0.166.3 : identifiant en base
        // camelCase, extraits multi-lignes SANS contrainte ni marge,
        // catégories existantes, APPEND à la fin du catalogue. Les
        // TAGS du LayoutLib sont TOUS enregistrés au ViewTagRegistry
        // (vues réelles sur tout hôte) ou issus de la plateforme ;
        // les fiches suivent [CatalogueAttributs] (familleSaisie +
        // MultiAutoCompleteTextView, familleEtat + CheckedTextView,
        // familleInterrupteur + MaterialSwitch, Barre d'outils +
        // Toolbar) et [FamilleParent.deBalise] (RadioGroup, TableLayout,
        // TableRow, MaterialButtonToggleGroup → LINEAR ; DrawerLayout,
        // SwipeRefreshLayout, NavigationView, BottomNavigationView,
        // ViewFlipper, NumberPicker → FRAME).
        // Texte — MultiAutoCompleteTextView, CheckedTextView,
        // TextInputEditText seul.
        EntreePalette(
            id = "mact",
            nomAffiche = "MultiAutoCompleteTextView",
            classe = "MultiAutoCompleteTextView",
            categorie = "Texte",
            extraitXml =
            "<MultiAutoCompleteTextView\n" +
                "    android:id=\"@+id/multiAutoCompleteTextView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"48dp\"\n" +
                "    android:hint=\"Destinataires\" />",
        ),
        EntreePalette(
            id = "ctv",
            nomAffiche = "CheckedTextView",
            classe = "CheckedTextView",
            categorie = "Texte",
            extraitXml =
            "<CheckedTextView\n" +
                "    android:id=\"@+id/checkedTextView\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"48dp\"\n" +
                "    android:text=\"CheckedTextView\" />",
        ),
        EntreePalette(
            id = "tiet",
            nomAffiche = "TextInputEditText",
            classe = "TextInputEditText",
            categorie = "Texte",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.textfield.TextInputEditText\n" +
                "    android:id=\"@+id/textInputEditText\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:hint=\"Valeur\" />",
        ),
        // Boutons — RadioGroup, MaterialSwitch, MaterialButtonToggleGroup,
        // ChipGroup.
        EntreePalette(
            id = "rg",
            nomAffiche = "RadioGroup",
            classe = "RadioGroup",
            categorie = "Boutons",
            extraitXml =
            "<RadioGroup\n" +
                "    android:id=\"@+id/radioGroup\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:orientation=\"vertical\" />",
        ),
        EntreePalette(
            id = "msw",
            nomAffiche = "MaterialSwitch",
            classe = "MaterialSwitch",
            categorie = "Boutons",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.materialswitch.MaterialSwitch\n" +
                "    android:id=\"@+id/materialSwitch\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"48dp\"\n" +
                "    android:text=\"MaterialSwitch\" />",
        ),
        EntreePalette(
            id = "mbtg",
            nomAffiche = "MaterialButtonToggleGroup",
            classe = "MaterialButtonToggleGroup",
            categorie = "Boutons",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.button.MaterialButtonToggleGroup\n" +
                "    android:id=\"@+id/materialButtonToggleGroup\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    app:singleSelection=\"true\" />",
        ),
        EntreePalette(
            id = "cg",
            nomAffiche = "ChipGroup",
            classe = "ChipGroup",
            categorie = "Boutons",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.chip.ChipGroup\n" +
                "    android:id=\"@+id/chipGroup\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\" />",
        ),
        // Conteneurs — TableLayout, TableRow, DrawerLayout,
        // SwipeRefreshLayout, CollapsingToolbarLayout, BottomAppBar,
        // NavigationView, BottomNavigationView, ViewFlipper.
        EntreePalette(
            id = "tabl",
            nomAffiche = "TableLayout",
            classe = "TableLayout",
            categorie = "Conteneurs",
            extraitXml =
            "<TableLayout\n" +
                "    android:id=\"@+id/tableLayout\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:minHeight=\"96dp\"\n" +
                "    android:stretchColumns=\"1\" />",
        ),
        EntreePalette(
            id = "tr",
            nomAffiche = "TableRow",
            classe = "TableRow",
            categorie = "Conteneurs",
            extraitXml =
            "<TableRow\n" +
                "    android:id=\"@+id/tableRow\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:minHeight=\"48dp\" />",
        ),
        EntreePalette(
            id = "dl",
            nomAffiche = "DrawerLayout",
            classe = "DrawerLayout",
            categorie = "Conteneurs",
            dependanceRequise = DEPENDANCE_DRAWERLAYOUT,
            extraitXml =
            "<androidx.drawerlayout.widget.DrawerLayout\n" +
                "    android:id=\"@+id/drawerLayout\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"160dp\" />",
        ),
        EntreePalette(
            id = "srl",
            nomAffiche = "SwipeRefreshLayout",
            classe = "SwipeRefreshLayout",
            categorie = "Conteneurs",
            dependanceRequise = DEPENDANCE_SWIPEREFRESH,
            extraitXml =
            "<androidx.swiperefreshlayout.widget.SwipeRefreshLayout\n" +
                "    android:id=\"@+id/swipeRefreshLayout\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"160dp\" />",
        ),
        EntreePalette(
            id = "cctl",
            nomAffiche = "CollapsingToolbarLayout",
            classe = "CollapsingToolbarLayout",
            categorie = "Conteneurs",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.appbar.CollapsingToolbarLayout\n" +
                "    android:id=\"@+id/collapsingToolbarLayout\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:minHeight=\"96dp\" />",
        ),
        EntreePalette(
            id = "bab",
            nomAffiche = "BottomAppBar",
            classe = "BottomAppBar",
            categorie = "Autres",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.bottomappbar.BottomAppBar\n" +
                "    android:id=\"@+id/bottomAppBar\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"56dp\" />",
        ),
        EntreePalette(
            id = "nv",
            nomAffiche = "NavigationView",
            classe = "NavigationView",
            categorie = "Conteneurs",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.navigation.NavigationView\n" +
                "    android:id=\"@+id/navigationView\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"match_parent\" />",
        ),
        EntreePalette(
            id = "bnv",
            nomAffiche = "BottomNavigationView",
            classe = "BottomNavigationView",
            categorie = "Autres",
            dependanceRequise = DEPENDANCE_MATERIAL,
            extraitXml =
            "<com.google.android.material.bottomnavigation.BottomNavigationView\n" +
                "    android:id=\"@+id/bottomNavigationView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"56dp\" />",
        ),
        EntreePalette(
            id = "flp",
            nomAffiche = "ViewFlipper",
            classe = "ViewFlipper",
            categorie = "Conteneurs",
            extraitXml =
            "<ViewFlipper\n" +
                "    android:id=\"@+id/viewFlipper\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"160dp\" />",
        ),
        // Médias — TextureView.
        EntreePalette(
            id = "tex",
            nomAffiche = "TextureView",
            classe = "TextureView",
            categorie = "Médias",
            extraitXml =
            "<TextureView\n" +
                "    android:id=\"@+id/textureView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"160dp\" />",
        ),
        // Listes — ExpandableListView.
        EntreePalette(
            id = "elv",
            nomAffiche = "ExpandableListView",
            classe = "ExpandableListView",
            categorie = "Listes",
            extraitXml =
            "<ExpandableListView\n" +
                "    android:id=\"@+id/expandableListView\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"160dp\" />",
        ),
        // Autres — Toolbar, Chronometer, TextClock, NumberPicker, Guideline.
        EntreePalette(
            id = "tbx",
            nomAffiche = "Toolbar",
            classe = "Toolbar",
            categorie = "Autres",
            dependanceRequise = DEPENDANCE_APPCOMPAT,
            extraitXml =
            "<androidx.appcompat.widget.Toolbar\n" +
                "    android:id=\"@+id/toolbar\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"56dp\"\n" +
                "    app:title=\"Titre\" />",
        ),
        EntreePalette(
            id = "chr",
            nomAffiche = "Chronometer",
            classe = "Chronometer",
            categorie = "Autres",
            extraitXml =
            "<Chronometer\n" +
                "    android:id=\"@+id/chronometer\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"48dp\" />",
        ),
        EntreePalette(
            id = "tclk",
            nomAffiche = "TextClock",
            classe = "TextClock",
            categorie = "Autres",
            extraitXml =
            "<TextClock\n" +
                "    android:id=\"@+id/textClock\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"48dp\"\n" +
                "    android:format12Hour=\"hh:mm\" />",
        ),
        EntreePalette(
            id = "npk",
            nomAffiche = "NumberPicker",
            classe = "NumberPicker",
            categorie = "Autres",
            extraitXml =
            "<NumberPicker\n" +
                "    android:id=\"@+id/numberPicker\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"160dp\" />",
        ),
        EntreePalette(
            id = "gui",
            nomAffiche = "Guideline",
            classe = "Guideline",
            categorie = "Autres",
            dependanceRequise = DEPENDANCE_CONSTRAINTLAYOUT,
            extraitXml =
            "<androidx.constraintlayout.widget.Guideline\n" +
                "    android:id=\"@+id/guideline\"\n" +
                "    android:layout_width=\"wrap_content\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:orientation=\"vertical\"\n" +
                "    app:layout_constraintGuide_begin=\"16dp\" />",
        ),
    )
