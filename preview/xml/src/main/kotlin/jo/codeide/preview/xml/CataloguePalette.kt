package jo.codeide.preview.xml

/**
 * Catalogue des widgets de la palette (PE7a, maquette
 * `docs/mockups/apercu-xml.html` § « Catalogue et insertion XML, en
 * fonctions pures » : « [CataloguePalette] décrit chaque widget :
 * classe, extrait XML, dépendance requise ») — DONNÉES PURES, sans
 * Android ni Material (règle du module : `preview/xml` zéro-Material ;
 * les tuiles dessinées, les icônes et les récents sont le chrome de
 * `:feature:preview`, PE7c).
 *
 * Miroir fidèle de `WIDGETS` de la maquette (22 entrées après PE12h,
 * 6 catégories) PUIS extension propriétaire v0.166.3 (+19 entrées,
 * demande « palette plus complète » — la maquette reste la source de
 * vérité de SES entrées, l'extension suit ses conventions) : chaque
 * entrée porte un EXTRAIT XML prêt à insérer —
 * balise multi-lignes, un attribut par ligne, l'identifiant en
 * `@+id/base` (la base est renumérotée au premier suffixe libre par
 * [InsertionXml.renumeroter], miroir `nextId`). L'extrait ne porte
 * JAMAIS de contrainte ni de marge : le façonnage au dépôt (gravité de
 * la cellule, règles vers un frère ou le parent, ancre — PE12d) est le
 * rôle de [InsertionXml.ajouterAttributs] sur le plan de
 * [PlanInsertionFamille], calculé par le contrôleur avec la densité du
 * profil.
 *
 * Le texte du document reste la SEULE source (maquette § « Limite de la
 * maquette ») : le catalogue ne décrit que CE QUE la palette offre,
 * l'écriture passe par l'édition [InsertionXml.EditionXml] posée dans la
 * session via le port `SourceApercu.remplacer`.
 */
public object CataloguePalette {
    /**
     * UN widget proposé par la palette.
     *
     * @property id clé stable (miroir `id` de la maquette — récents,
     *           persistance PE7c).
     * @property nomAffiche libellé de la tuile (ex. « Plain Text »).
     * @property classe nom simple de la classe (ex. « EditText » —
     *           miroir `cls`, affiché sous le nom).
     * @property categorie catégorie de regroupement (miroir `cat`).
     * @property extraitXml extrait XML à insérer (balise multi-lignes).
     * @property dependanceRequise coordonnée Maven de la bibliothèque
     *           requise (miroir `dep` — null = classes du SDK), ex.
     *           « com.google.android.material:material ».
     */
    public data class EntreePalette(
        val id: String,
        val nomAffiche: String,
        val classe: String,
        val categorie: String,
        val extraitXml: String,
        val dependanceRequise: String? = null,
    )

    /** Catégories de la palette, dans l'ordre de la maquette (`CATS`). */
    public val CATEGORIES: List<String> =
        listOf(
            "Texte",
            "Boutons",
            "Conteneurs",
            "Médias",
            "Listes",
            "Autres",
        )

    /** Coordonnée Maven de Material Components (miroir `MAT`). */
    public const val DEPENDANCE_MATERIAL: String = "com.google.android.material:material"

    /** Coordonnée Maven de RecyclerView (miroir `dep` de la tuile rv). */
    public const val DEPENDANCE_RECYCLERVIEW: String = "androidx.recyclerview:recyclerview"

    /** ConstraintLayout — extension propriétaire (v0.166.3, « palette plus complète »). */
    public const val DEPENDANCE_CONSTRAINTLAYOUT: String = "androidx.constraintlayout:constraintlayout"

    /** ViewPager2 — extension propriétaire (v0.166.3, « palette plus complète »). */
    public const val DEPENDANCE_VIEWPAGER2: String = "androidx.viewpager2:viewpager2"

    /**
     * Le libellé COURT du tag de dépendance d'une tuile (v0.166.3) : le
     * badge est étroit — la coordonnée Maven entière ne tient pas sur un
     * tag, la maquette n'affiche que « Material ». Les coordonnées
     * connues portent leur nom d'artefact, une coordonnée inconnue
     * s'affiche TELLE QUELLE (jamais tronquée à tort).
     */
    public fun libelleCourtDependance(coordonnee: String): String =
        when (coordonnee) {
            DEPENDANCE_MATERIAL -> "Material"
            DEPENDANCE_RECYCLERVIEW -> "RecyclerView"
            DEPENDANCE_CONSTRAINTLAYOUT -> "ConstraintLayout"
            DEPENDANCE_VIEWPAGER2 -> "ViewPager2"
            else -> coordonnee
        }

    /**
     * Le catalogue COMPLET (miroir `WIDGETS`, même ordre) : les extraits
     * portent les attributs des nœuds `make()` de la maquette (texte
     * d'exemple, hint, orientation, minHeight, style horizontal de
     * ProgressBar…) — l'esprit du rendu maquette, écrit en XML réel.
     */
    public val CATALOGUE: List<EntreePalette> =
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

    // NB PE12h : les six entrées de la vague parents portent la même
    // convention d'identifiant que les seize historiques — base camelCase
    // du nom simple de classe (miroir `nextId`) : frameLayout,
    // relativeLayout, coordinatorLayout, appBarLayout, materialToolbar,
    // floatingActionButton.
    // NB v0.166.3 : les 19 extensions suivent la même convention —
    // autoCompleteTextView, radioButton, toggleButton, materialButton,
    // chip, scrollView, horizontalScrollView, nestedScrollView,
    // constraintLayout, gridLayout, tabLayout, viewPager2, videoView,
    // spinner, listView, gridView, webView, ratingBar, searchView.

    /** L'entrée par identifiant (miroir `wById` — récents, PE7c). */
    public fun parId(id: String): EntreePalette? = CATALOGUE.firstOrNull { it.id == id }

    /**
     * Les entrées d'une catégorie (« Tous » renvoie tout — miroir
     * `fillGrid`).
     */
    public fun parCategorie(categorie: String): List<EntreePalette> =
        if (categorie == TOUTES) {
            CATALOGUE
        } else {
            CATALOGUE.filter { it.categorie == categorie }
        }

    /** Filtre par recherche (miroir `fillGrid` : nom + classe, insensible à la casse). */
    public fun rechercher(
        categorie: String,
        requete: String,
    ): List<EntreePalette> {
        val requeteEpuree = requete.trim()
        val base = parCategorie(categorie)
        if (requeteEpuree.isEmpty()) return base
        return base.filter { entree ->
            (entree.nomAffiche + " " + entree.classe)
                .lowercase()
                .contains(requeteEpuree.lowercase())
        }
    }

    /** Libellé de l'onglet « Tous » de la maquette. */
    public const val TOUTES: String = "Tous"
}
