package jo.codeide.preview.xml

/**
 * COULEURS DU PROJET (PE11e — miroir `RES.color` + `colorCss` de la
 * maquette) — FONCTIONS PURES SANS ANDROID : lecture du TEXTE des
 * `colors.xml` sous `res/values` et ses qualifiés, et RÉSOLUTION d'une
 * valeur de couleur pour la PASTILLE du contrôle couleur
 * (§ 9 `controle_couleur`).
 *
 * Le document reste la SEULE source (maquette § « Limite de la
 * maquette ») : [lire] reçoit les TEXTES des fichiers de valeurs
 * (l'UI les lit dans `res/` du projet — ordre `values/` D'ABORD), les
 * pastilles de la palette en sortent dans l'ordre DU DOCUMENT (miroir
 * `Object.keys(RES.color)`).
 *
 * [resoudre] miroir `colorCss` : hex `#RGB`/`#ARGB`/`#RRGGBB`/
 * `#AARRGGBB` (chiffre doublé en forme courte), `@color/nom` (UN
 * niveau — la maquette ne récursive pas, une référence imbriquée
 * reste NON résoluble), `@android:color/white|black|transparent`
 * (les TROIS du dictionnaire `ANDROID_COL` — un autre nom système est
 * VALIDE mais sans pastille), `?attr/…` → [ATTRS_THEME] (l'UI lit
 * les six via son THÈME Material — miroir `ATTR_CSS`), sinon `null`
 * (pastille transparente sur le damier).
 */
public object CouleursProjetXml {
    /** UNE couleur déclarée dans le projet (`<color name="x">valeur`). */
    public data class CouleurProjet(
        /** Le nom derrière `@color/` (clé d'affichage de la palette). */
        public val nom: String,
        /** La VALEUR littérale (hex, ou référence imbriquée — résolue à UN niveau). */
        public val valeur: String,
    )

    /**
     * Les `@android:color/` résolus par la maquette (miroir exact du
     * dictionnaire `ANDROID_COL` — `transparent` = damier seul).
     */
    public val ANDROID: Map<String, Long> =
        mapOf(
            "white" to ARGB_BLANC,
            "black" to ARGB_NOIR,
            "transparent" to ARGB_TRANSPARENT,
        )

    /**
     * Les attributs de thème `?attr/` que l'UI résout via SON thème
     * (miroir `ATTR_CSS` — les six clés du dictionnaire, dans l'ordre
     * de la maquette). Tout autre `?attr/` : VALIDE mais sans pastille.
     */
    public val ATTRS_THEME: List<String> =
        listOf(
            "colorPrimary",
            "colorOnPrimary",
            "colorSurface",
            "colorOnSurface",
            "colorOutline",
            "colorError",
        )

    /**
     * Les entrées FIXES de la palette après les `@color` du projet
     * (miroir le `.concat(...)` du bouton Palette : le trio système +
     * les deux attributs de thème).
     */
    public val PALETTE_FIXE: List<String> =
        listOf(
            "@android:color/white",
            "@android:color/black",
            "@android:color/transparent",
            "?attr/colorPrimary",
            "?attr/colorError",
        )

    /** Les `<color name="x">valeur</color>` d'un fichier de valeurs, dans l'ordre du document. */
    private val ENTREE: Regex = Regex("""<color\s+name\s*=\s*"([^"]+)"\s*>([^<]*)</color>""")

    /** Hexadécimal court `#RGB` ou `#ARGB`. */
    private val HEX_COURT: Regex = Regex("""^#([0-9a-fA-F]{3}|[0-9a-fA-F]{4})$""")

    /** Hexadécimal long `#RRGGBB` ou `#AARRGGBB`. */
    private val HEX_LONG: Regex = Regex("""^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$""")

    /** Un nom de ressource nu (`\w+` — miroir `colorCss`). */
    private val NOM_RESSOURCE: Regex = Regex("""^\w+$""")

    /** Nombre de chiffres d'une forme longue SANS alpha (`RRGGBB`). */
    private const val CHIFFRES_OPAQUES: Int = 6

    /** Décalage du canal alpha dans un long ARGB (trois octets). */
    private const val DECALAGE_ALPHA: Int = 24

    /** Base hexadécimale des conversions. */
    private const val BASE_HEXA: Int = 16

    /** Alpha OPAQUE en tête d'ARGB. */
    private const val OPAQUE: Long = 0xFFL

    /** Le BLANC opaque du trio système (`#FFFFFF`). */
    private const val ARGB_BLANC: Long = 0xFFFFFFFFL

    /** Le NOIR opaque du trio système (`#000000`). */
    private const val ARGB_NOIR: Long = 0xFF000000L

    /** Le TRANSPARENT du trio système (damier seul). */
    private const val ARGB_TRANSPARENT: Long = 0x00000000L

    /**
     * Les couleurs déclarées dans les TEXTES des fichiers de valeurs,
     * dans l'ordre de lecture : le PREMIER nom vu GAGNE (miroir
     * `Object.keys(RES.color)` — `values/` d'abord donne la valeur
     * CLAIRE, les qualifiés ne rabattent que les NOMS manquants).
     */
    public fun lire(textes: List<String>): List<CouleurProjet> {
        val vus = mutableSetOf<String>()
        val couleurs = mutableListOf<CouleurProjet>()
        for (texte in textes) {
            for (correspondance in ENTREE.findAll(texte)) {
                val nom = correspondance.groupValues[1].trim()
                if (nom.isEmpty() || !vus.add(nom)) continue
                couleurs.add(CouleurProjet(nom, correspondance.groupValues[2].trim()))
            }
        }
        return couleurs
    }

    /** Le nom derrière un préfixe `@type/` si le nom est légal, sinon `null`. */
    private fun nomRessource(valeur: String): String? {
        val nom = valeur.substringAfter('/', "")
        return if (NOM_RESSOURCE.matches(nom)) nom else null
    }

    /**
     * RÉSOUT une valeur de couleur en long ARGB pour la pastille
     * (miroir `colorCss` — `null` = transparent : le damier reste
     * seul). Les CHIFFRES passés à [hexLong]/[hexCourt] sont SANS
     * « # ».
     *
     * Exemption detekt ciblée (règle 16, même motivation que
     * [ExtractionRessource]) : ReturnCount — chaque garde est une
     * VOIE DE RÉSOLUTION DISTINCTE du `colorCss` de la maquette
     * (hex long, hex court, `@color/`, `@android:color/`) ; les
     * noyer masquerait le miroir.
     */
    @Suppress("ReturnCount")
    public fun resoudre(valeur: String?, couleurs: Map<String, String>): Long? {
        if (valeur.isNullOrEmpty()) return null
        HEX_LONG.find(valeur)?.let { return hexLong(it.groupValues[1]) }
        HEX_COURT.find(valeur)?.let { return hexCourt(it.groupValues[1]) }
        if (valeur.startsWith("@color/")) {
            val nom = nomRessource(valeur) ?: return null
            val cible = couleurs[nom] ?: return null
            HEX_LONG.find(cible)?.let { return hexLong(it.groupValues[1]) }
            HEX_COURT.find(cible)?.let { return hexCourt(it.groupValues[1]) }
            return null
        }
        if (valeur.startsWith("@android:color/")) {
            return ANDROID[nomRessource(valeur) ?: return null]
        }
        return null
    }

    /** `RRGGBB` (alpha opaque posé) ou `AARRGGBB` → long ARGB. */
    private fun hexLong(chiffres: String): Long = if (chiffres.length == CHIFFRES_OPAQUES) {
        OPAQUE shl DECALAGE_ALPHA or chiffres.toLong(BASE_HEXA)
    } else {
        chiffres.toLong(BASE_HEXA)
    }

    /** `RGB` (alpha opaque posé) ou `ARGB` → chaque chiffre DOUBLÉ (miroir `c + c`). */
    private fun hexCourt(chiffres: String): Long {
        val doubles = chiffres.map { "$it$it" }.joinToString("")
        return if (doubles.length == CHIFFRES_OPAQUES) {
            OPAQUE shl DECALAGE_ALPHA or doubles.toLong(BASE_HEXA)
        } else {
            doubles.toLong(BASE_HEXA)
        }
    }
}
