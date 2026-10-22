package jo.codeide.preview.xml

/**
 * LA FAMILLE DE PARENT (PE12a — `docs/PREVIEW_PARENTS.md` § 2, ADR 0135
 * D1) : l'ensemble des règles avec lesquelles un `ViewGroup` interprète
 * les `LayoutParams` de ses enfants. Huit familles couvrent les
 * conteneurs de la plateforme ; [AUTRE] rassemble le reste (GridLayout,
 * TableLayout, DrawerLayout, ViewPager…).
 *
 * FONCTIONS PURES, SANS ANDROID : la famille se déduit de NOMS, jamais
 * d'`instanceof` (spec § 2.2) — `:preview:xml` ne dépend ni de Material
 * ni d'AndroidX en compilation. [deClasses] parcourt la chaîne
 * `Class.getSuperclass()` de la vue RÉELLE (noms qualifiés, fournis par
 * le rendu) ; [deBalise] applique la même table au nom de balise —
 * repli des `MockView` (classe introuvable à l'inflation) et des vues
 * sans information de rendu.
 *
 * [AUCUN] ne sort JAMAIS de [deClasses] ni de [deBalise] : il décrit la
 * vue RACINE (aucun parent), pas une famille de conteneur. Depuis
 * PE12b, [CatalogueAttributs.sections] la prend TELLE QUELLE :
 * l'ancien alias `GenreParent` a disparu (spec § 2.3).
 */
public enum class FamilleParent {
    /** La vue est la racine : aucun parent, aucune section de position. */
    AUCUN,

    /** ConstraintLayout (racine ou non) et MotionLayout. */
    CONSTRAINT,

    /** LinearLayout et ses sous-classes, HORS AppBarLayout (testé avant). */
    LINEAR,

    /** FrameLayout, MaterialCardView, NestedScrollView, ScrollView, CollapsingToolbarLayout… */
    FRAME,

    /** RelativeLayout. */
    RELATIVE,

    /** CoordinatorLayout. */
    COORDINATOR,

    /** AppBarLayout — sous-classe de LinearLayout : teste AVANT LINEAR. */
    APPBAR,

    /** Tout le reste : GridLayout, TableLayout, DrawerLayout, ViewPager… */
    AUTRE,
    ;

    public companion object {
        /**
         * Classes CONNUES, par nom QUALIFIÉ (spec § 2.2) — une seule par
         * famille de la plateforme : les sous-classes (MaterialCardView,
         * NestedScrollView, ScrollView, CollapsingToolbarLayout…) y
         * tombent par la chaîne de superclasses, sans être listées.
         */
        private val PAR_CLASSE: Map<String, FamilleParent> =
            mapOf(
                "com.google.android.material.appbar.AppBarLayout" to APPBAR,
                "androidx.coordinatorlayout.widget.CoordinatorLayout" to COORDINATOR,
                "androidx.constraintlayout.widget.ConstraintLayout" to CONSTRAINT,
                "androidx.constraintlayout.motion.widget.MotionLayout" to CONSTRAINT,
                "android.widget.RelativeLayout" to RELATIVE,
                "android.widget.LinearLayout" to LINEAR,
                "android.widget.FrameLayout" to FRAME,
            )

        /**
         * Balises par nom SIMPLE (repli § 2.2) : la table des classes +
         * les sous-classes connues qui ne portent PAS le nom de leur
         * ancêtre — un « MaterialCardView » de balise vaut FRAME, la
         * chaîne réelle n'étant pas atteignable sans classe chargée.
         * v0.166.3 : les extensions de la palette (HorizontalScrollView,
         * TabLayout — HorizontalScrollView, ViewPager2 — FrameLayout)
         * y tombent aussi, leurs dépôts usagers arrivent par balise.
         * v0.170.0 (S4) : la deuxième vague de la palette y tombe de
         * même — LINEAR pour RadioGroup, TableLayout, TableRow,
         * MaterialButtonToggleGroup (sous-classes de LinearLayout),
         * FRAME pour DrawerLayout, SwipeRefreshLayout, NavigationView,
         * BottomNavigationView, ViewFlipper, NumberPicker (sous-classes
         * de FrameLayout).
         */
        private val PAR_BALISE: Map<String, FamilleParent> =
            mapOf(
                "AppBarLayout" to APPBAR,
                "CoordinatorLayout" to COORDINATOR,
                "ConstraintLayout" to CONSTRAINT,
                "MotionLayout" to CONSTRAINT,
                "RelativeLayout" to RELATIVE,
                "LinearLayout" to LINEAR,
                "FrameLayout" to FRAME,
                "MaterialCardView" to FRAME,
                "NestedScrollView" to FRAME,
                "ScrollView" to FRAME,
                "HorizontalScrollView" to FRAME,
                "CollapsingToolbarLayout" to FRAME,
                "TabLayout" to FRAME,
                "ViewPager2" to FRAME,
                "RadioGroup" to LINEAR,
                "TableLayout" to LINEAR,
                "TableRow" to LINEAR,
                "MaterialButtonToggleGroup" to LINEAR,
                "DrawerLayout" to FRAME,
                "SwipeRefreshLayout" to FRAME,
                "NavigationView" to FRAME,
                "BottomNavigationView" to FRAME,
                "ViewFlipper" to FRAME,
                "NumberPicker" to FRAME,
            )

        /**
         * La famille depuis la chaîne de superclasses de la vue réelle
         * ([chaine] = noms QUALIFIÉS, de la classe à `Object`) : la
         * PREMIÈRE classe connue en remontant gagne — un `AppBarLayout`
         * se rencontre avant le `LinearLayout` qu'il étend, un
         * `CoordinatorLayout` avant le `FrameLayout` ; classe inconnue
         * ou chaîne vide → [AUTRE].
         */
        public fun deClasses(chaine: List<String>): FamilleParent =
            chaine.firstNotNullOfOrNull(PAR_CLASSE::get) ?: AUTRE

        /**
         * La famille depuis le nom de BALISE, simple (« RelativeLayout »)
         * ou qualifié (« androidx.coordinatorlayout.widget.CoordinatorLayout »)
         * — même table que [deClasses] sur le nom local ; balise inconnue
         * ou vide → [AUTRE].
         */
        public fun deBalise(nomBalise: String): FamilleParent = PAR_BALISE[nomBalise.substringAfterLast('.')] ?: AUTRE

        /**
         * La famille depuis son NOM d'entrée ([name] — la valeur qui
         * voyage dans [InfoVueRendu.famille]) : null si le nom est
         * inconnu ou absent (rendu ancien, chaîne altérée — jamais de
         * faux genre sur la frontière AIDL).
         */
        public fun deNom(nom: String?): FamilleParent? = entries.firstOrNull { it.name == nom }
    }
}
