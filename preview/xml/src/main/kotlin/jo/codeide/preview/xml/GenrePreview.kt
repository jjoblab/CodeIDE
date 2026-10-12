package jo.codeide.preview.xml

/**
 * Genre d'un fichier previewable (classification `previewKindOf` à la
 * CodeAssist, PREVIEW.md §3.2 : layout / drawable / valeurs).
 *
 * PE1 ne REND que [LAYOUT] ; [DRAWABLE] et [VALEURS] sont classifiés dès
 * maintenant (l'activation du panneau et l'état des lieux en parlent déjà)
 * et rendus en PE3 (`docs/PREVIEW.md` §3.4).
 */
public enum class GenrePreview {
    /** Layout XML (`res/layout*`, racine vue/groupe). */
    LAYOUT,

    /** Drawable XML (`res/drawable*`, racine shape/selector/vector…). */
    DRAWABLE,

    /** Fichier de ressources (`res/values*`, racine `resources`). */
    VALEURS,
}

/** Racines de layout les plus courantes (framework + AndroidX de base). */
private val RACINES_LAYOUT =
    setOf(
        "LinearLayout",
        "FrameLayout",
        "RelativeLayout",
        "GridLayout",
        "TableLayout",
        "TableRow",
        "ScrollView",
        "HorizontalScrollView",
        "NestedScrollView",
        "CoordinatorLayout",
        "ConstraintLayout",
        "MotionLayout",
        "DrawerLayout",
        "SwipeRefreshLayout",
        "ViewPager",
        "ViewPager2",
        "RecyclerView",
        "GridView",
        "ListView",
        "ExpandableListView",
        "ViewFlipper",
        "StackView",
        "TextSwitcher",
        "ViewSwitcher",
        "ImageSwitcher",
        "SurfaceView",
        "TextureView",
        "View",
        "TextView",
        "Button",
        "ImageView",
        "EditText",
        "CheckBox",
        "RadioButton",
        "Switch",
        "SeekBar",
        "ProgressBar",
        "Space",
        "ViewStub",
        "merge",
        "include",
        "DatePicker",
        "TimePicker",
        "CalendarView",
        "ZoomControls",
        "VideoView",
        "Chronometer",
        "QuickContactBadge",
        "RatingBar",
        "SearchView",
        "Spinner",
        "TabHost",
        "Toolbar",
        "WebView",
        "AutoCompleteTextView",
        "MultiAutoCompleteTextView",
    )

/** Racines de drawable XML (module-drawables LayoutLib). */
private val RACINES_DRAWABLE =
    setOf(
        "shape",
        "selector",
        "vector",
        "layer-list",
        "ripple",
        "inset",
        "bitmap",
        "clip",
        "scale",
        "rotate",
        "translate",
        "animated-rotate",
        "animated-vector",
        "animation-list",
        "level-list",
        "adaptive-icon",
        "nine-patch",
    )

/**
 * Classifie un fichier XML pour la preview — conservateur (null si le
 * genre ne se laisse pas établir : D3/LSP_XML X4, placeholder neutre
 * plutôt que mauvaise guess).
 *
 * L'ordre de décision : le CHEMIN d'abord (`res/layout*`,
 * `res/drawable*`, `res/values*` — source de vérité du projet), puis la
 * racine XML en repli (fichier pas encore enregistré à son emplacement,
 * nouveau layout en cours de création).
 */
public object ClassificateurPreview {
    /** Classifie par chemin relatif au projet et texte du document ; null = pas previewable. */
    public fun genreDe(
        cheminRelatif: String,
        texte: String,
    ): GenrePreview? {
        val chemin = cheminRelatif.replace('\\', '/')
        return when {
            chemin.contains("res/layout") -> GenrePreview.LAYOUT
            chemin.contains("res/drawable") -> GenrePreview.DRAWABLE
            chemin.contains("res/values") -> GenrePreview.VALEURS
            else -> genreParRacine(texte)
        }
    }

    /** Classifie par la racine du document XML (repli sans chemin). */
    public fun genreParRacine(texte: String): GenrePreview? {
        val racineBrute = racineXml(texte) ?: return null
        // Les racines AndroidX arrivent avec leur nom PLEIN
        // (androidx.constraintlayout.widget.ConstraintLayout) : on
        // classifie sur le nom simple — conservateur sur le reste.
        val racine = racineBrute.substringAfterLast('.')
        return when {
            racine in RACINES_LAYOUT -> GenrePreview.LAYOUT
            racine in RACINES_DRAWABLE -> GenrePreview.DRAWABLE
            racine == "resources" -> GenrePreview.VALEURS
            else -> null
        }
    }

    /** Nom de l'élément racine du XML, ou null (malformé/invalide — édition en cours). */
    internal fun racineXml(texte: String): String? {
        if (texte.isBlank()) return null
        return try {
            premierNomDElement(texte)
        } catch (_: org.xmlpull.v1.XmlPullParserException) {
            null
        } catch (_: java.io.IOException) {
            // StringReader : n'arrive pas en pratique — conservateur quand même.
            null
        }
    }

    /** Parcourt le document jusqu'au premier élément ; null si aucun (vide). */
    private fun premierNomDElement(texte: String): String? {
        val fabrique =
            org.xmlpull.v1.XmlPullParserFactory
                .newInstance()
        fabrique.isNamespaceAware = true
        val parseur = fabrique.newPullParser()
        parseur.setInput(java.io.StringReader(texte))
        var evenement = parseur.eventType
        while (evenement != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (evenement == org.xmlpull.v1.XmlPullParser.START_TAG) {
                return parseur.name
            }
            evenement = parseur.next()
        }
        return null
    }
}
