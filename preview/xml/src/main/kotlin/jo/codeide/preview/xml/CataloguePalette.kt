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

    /** AppCompat (Toolbar) — extension propriétaire (v0.170.0, S4 « palette proche d'Android Studio »). */
    public const val DEPENDANCE_APPCOMPAT: String = "androidx.appcompat:appcompat"

    /** DrawerLayout — extension propriétaire (v0.170.0, S4). */
    public const val DEPENDANCE_DRAWERLAYOUT: String = "androidx.drawerlayout:drawerlayout"

    /** SwipeRefreshLayout — extension propriétaire (v0.170.0, S4). */
    public const val DEPENDANCE_SWIPEREFRESH: String = "androidx.swiperefreshlayout:swiperefreshlayout"

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
            DEPENDANCE_APPCOMPAT -> "AppCompat"
            DEPENDANCE_DRAWERLAYOUT -> "DrawerLayout"
            DEPENDANCE_SWIPEREFRESH -> "SwipeRefreshLayout"
            else -> coordonnee
        }

    /**
     * Le catalogue COMPLET (miroir `WIDGETS`, même ordre) : les 22 entrées
     * de la maquette PUIS les extensions propriétaires, dans leur ordre
     * d'arrivée — v0.166.3 « palette plus complète » (19) puis v0.170.0
     * S4 « palette proche d'Android Studio » (23). Les extraits portent
     * les attributs des nœuds `make()` de la maquette (texte d'exemple,
     * hint, orientation, minHeight, style horizontal de ProgressBar…)
     * — l'esprit du rendu maquette, écrit en XML réel.
     *
     * v0.170.0 — découpe LargeClass (detekt, règle 8) : les entrées des
     * trois vagues vivent dans [CataloguePaletteEntrees.kt] (des listes
     * top-level PAR VAGUE, avec leurs commentaires de traçabilité) ; la
     * composition préserve l'ORDRE (l'API publique ne change pas).
     */
    public val CATALOGUE: List<EntreePalette> =
        ENTREES_MAQUETTE + ENTREES_EXTENSION_V0166 + ENTREES_EXTENSION_V0170

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
    // NB v0.170.0 : les 23 extensions S4 suivent la même convention —
    // multiAutoCompleteTextView, checkedTextView, textInputEditText,
    // radioGroup, materialSwitch, materialButtonToggleGroup, chipGroup,
    // tableLayout, tableRow, drawerLayout, swipeRefreshLayout,
    // collapsingToolbarLayout, bottomAppBar, navigationView,
    // bottomNavigationView, viewFlipper, textureView, expandableListView,
    // toolbar, chronometer, textClock, numberPicker, guideline.

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
