package jo.codeide.core.domain

/**
 * Une couleur des 8+8 du SGR (ADR 0160 § Décision 4 : palette minimale) —
 * l'UI projette chaque valeur sur un jeton de thème réel (jamais une
 * couleur littérale ; ADR 0127). La variante brillante (90-97) porte
 * son propre drapeau sur [SegmentAnsi.couleurVive] : un seul jeu de
 * huit valeurs, deux intensités, aucun état mutable dans le modèle.
 */
public enum class CouleurAnsi {
    /** Noir (30) / gris clair (90). */
    NOIR,

    /** Rouge (31) / rouge vif (91). */
    ROUGE,

    /** Vert (32) / vert vif (92). */
    VERT,

    /** Jaune (33) / jaune vif (93). */
    JAUNE,

    /** Bleu (34) / bleu vif (94). */
    BLEU,

    /** Magenta (35) / magenta vive (95). */
    MAGENTA,

    /** Cyan (36) / cyan vif (96). */
    CYAN,

    /** Blanc (37) / blanc vif (97). */
    BLANC,
}

/**
 * Un segment de texte à style homogène produit par [ParseurAnsiSgr] —
 * la ligne ANSI se rend en une liste de segments, chaque segment portant
 * le style du SGR au moment de son écriture (gras, italique, 8+8
 * couleurs — ADR 0160). Tout contrôleur NON supporté a déjà été purgé :
 * un segment ne contient jamais de séquence d'échappement résiduelle.
 *
 * @property texte contenu brut du segment (jamais vide dans la sortie du
 *           parseur — les segments vides ne sont pas émis).
 * @property gras SGR 1 (arrêté par 22).
 * @property italique SGR 3 (arrêté par 23).
 * @property couleur couleur de texte SGR 30-37 / 90-97, ou `null` quand
 *           la couleur par défaut (39) est active.
 * @property couleurVive `true` pour la variante brillante (90-97).
 */
public data class SegmentAnsi(
    public val texte: String,
    public val gras: Boolean = false,
    public val italique: Boolean = false,
    public val couleur: CouleurAnsi? = null,
    public val couleurVive: Boolean = false,
)

/**
 * Parseur ANSI SGR minimal du Centre de sorties (ADR 0160 § Décision 4,
 * arbitrage Q7 : OUI aux ANSI v1) — PUR, testé, désactivable (réglage
 * M13 : quand il est désactivé, l'UI utilise [purger] et rend le texte
 * nu). Jamais une interprétation de contrôleurs non supportés : tout ce
 * qui n'est pas gras/italique/8+8 couleurs est CONSOMMÉ sans rendu —
 * jamais affiché comme du texte parasite.
 *
 * Périmètre de consommation :
 * - **SGR** (`ESC[…m`) : 0 (réinitialisation), 1/22 (gras), 3/23
 *   (italique), 30-37/39 (couleur de texte), 90-97 (couleur vive) ;
 *   les autres paramètres SGR (faint, souligné, clignotement, inverse,
 *   caché, barré, couleurs de fond 40-47/49/100-107, 256 couleurs
 *   `38;5;n`, RGB `38;2;r;g;b`) sont consommés sans rendu ;
 * - **CSI non-SGR** (`ESC[…X`, X ≠ m : effacements, mouvements de
 *   curseur) : consommés ;
 * - **OSC** (`ESC]…BEL` ou `ESC]…ESC\` : titres, liens) : consommés ;
 * - **sélections de jeu de caractères** (`ESC( X`, `ESC) X`, `ESC# X`) :
 *   consommées ;
 * - **commandes à deux caractères** (`ESC c`, `ESC 7`…) : consommées ;
 *   un `ESC` isolé en fin de flux est purgé sans lever d'erreur.
 */
public object ParseurAnsiSgr {
    /** Caractère d'échappement ASCII. */
    private const val ESC = '\u001B'

    /** Cloche de terminaison OSC (BEL). */
    private const val BEL = '\u0007'

    /** Crochet ouvrant : introduit un CSI ou un OSC selon le suivant. */
    private const val CROCHET = '['

    /** Introduit un OSC après [CROCHET]. */
    private const val ACCOLADE = ']'

    /** Séparateur des paramètres SGR. */
    private const val POINT_VIRGULE = ';'

    /** Caractère final d'un SGR. */
    private const val FINAL_SGR = 'm'

    /** Contre-oblique : seconde moitié du terminateur ST (`ESC \`). */
    private const val CONTRE_OBLIQUE = '\\'

    /**
     * Analyse une ligne avec ses séquences SGR et rend les segments à
     * style homogène, dans l'ordre d'écriture. Une ligne sans échap-
     * pement rend un segment unique (ou rien si elle est vide) — le
     * cas le plus courant doit rester sans allocation inutile.
     */
    public fun analyser(texte: String): List<SegmentAnsi> {
        if (!contientEscape(texte)) {
            return if (texte.isEmpty()) emptyList() else listOf(SegmentAnsi(texte))
        }
        val segments = ArrayList<SegmentAnsi>()
        val enCours = StringBuilder()
        var style = QuadrupletStyle(gras = false, italique = false, couleur = null, vive = false)

        fun figer() {
            if (enCours.isNotEmpty()) {
                segments.add(
                    SegmentAnsi(
                        texte = enCours.toString(),
                        gras = style.gras,
                        italique = style.italique,
                        couleur = style.couleur,
                        couleurVive = style.vive,
                    ),
                )
                enCours.clear()
            }
        }

        var indice = 0
        val longueur = texte.length
        while (indice < longueur) {
            val caractere = texte[indice]
            if (caractere != ESC) {
                enCours.append(caractere)
                indice += 1
                continue
            }
            // Un escape : consommer la séquence, figer le segment en cours
            // si le style à venir change (égalité structurée — un SGR qui
            // ne change rien ne coupe pas le segment).
            val (indiceApres, styleApplique) = consommerSequence(texte, indice, style)
            if (styleApplique != null && styleApplique != style) {
                figer()
                style = styleApplique
            }
            indice = indiceApres
        }
        figer()
        return segments
    }

    /**
     * Purge TOUTES les séquences d'échappement (SGR, CSI, OSC, jeux de
     * caractères) : rend le texte nu, prêt pour un rendu sans ANSI
     * (réglage M13), un export ou une recherche.
     */
    public fun purger(texte: String): String {
        if (!contientEscape(texte)) {
            return texte
        }
        val nu = StringBuilder(texte.length)
        var indice = 0
        val longueur = texte.length
        while (indice < longueur) {
            val caractere = texte[indice]
            if (caractere != ESC) {
                nu.append(caractere)
                indice += 1
                continue
            }
            indice = indiceApresSequence(texte, indice)
        }
        return nu.toString()
    }

    /** La ligne contient-elle un caractère d'échappement ? */
    public fun contientEscape(texte: String): Boolean = texte.indexOf(ESC) >= 0

    /**
     * Consomme la séquence commençant à [indice] (qui EST un [ESC]) et
     * rend l'indice après la séquence, plus le style résultant si la
     * séquence était un SGR (`null` : séquence non-SGR, style inchangé).
     */
    private fun consommerSequence(texte: String, indice: Int, style: QuadrupletStyle): Pair<Int, QuadrupletStyle?> {
        val apres = indiceApresSequence(texte, indice)
        val sequence = texte.substring(indice, apres)
        if (!sequence.startsWith("$ESC$CROCHET") || !sequence.endsWith(FINAL_SGR)) {
            return Pair(apres, null)
        }
        return Pair(apres, appliquerSgr(sequence.substring(2, sequence.length - 1), style))
    }

    /** Indice APRÈS la séquence d'échappement qui commence à [indice]. */
    private fun indiceApresSequence(texte: String, indice: Int): Int {
        val longueur = texte.length
        val suivant = indice + 1
        if (suivant >= longueur) {
            return longueur
        }
        return when (texte[suivant]) {
            CROCHET -> indiceApresCsi(texte, suivant + 1)
            ACCOLADE -> indiceApresOsc(texte, suivant + 1)
            '(' -> minOf(suivant + 2, longueur)
            ')' -> minOf(suivant + 2, longueur)
            '#' -> minOf(suivant + 2, longueur)
            else -> suivant + 1
        }
    }

    /** Indice APRÈS un CSI (params 0x30-0x3F, intermédiaires 0x20-0x2F,
     *  octet final 0x40-0x7E) — sans octet final : la fin du texte. */
    private fun indiceApresCsi(texte: String, debut: Int): Int {
        var indice = debut
        val longueur = texte.length
        while (indice < longueur) {
            val code = texte[indice].code
            if (code in FINAL_MIN..FINAL_MAX) {
                return indice + 1
            }
            indice += 1
        }
        return longueur
    }

    /** Indice APRÈS un OSC (terminé par BEL ou ST `ESC \`). */
    private fun indiceApresOsc(texte: String, debut: Int): Int {
        var indice = debut
        var resultat = texte.length
        while (resultat == texte.length && indice < texte.length) {
            when (texte[indice]) {
                BEL -> resultat = indice + 1

                ESC -> if (indice + 1 < texte.length && texte[indice + 1] == CONTRE_OBLIQUE) {
                    resultat = indice + 2
                }
            }
            indice += 1
        }
        return resultat
    }

    /**
     * Applique les paramètres SGR au style courant et rend le style
     * résultant — pur : aucun effet de bord, le style courant est passé
     * en entrée. Chaque morceau se traite en une fois ([appliquerMorceau])
     * : le style reste une valeur immuable transportée d'un code au
     * suivant.
     */
    private fun appliquerSgr(parametres: String, style: QuadrupletStyle): QuadrupletStyle {
        val morceaux = parametres.split(POINT_VIRGULE)
        var courant = style
        var position = 0
        while (position < morceaux.size) {
            val (styleApres, saut) = appliquerMorceau(morceaux[position], morceaux.getOrNull(position + 1), courant)
            courant = styleApres
            position += saut + 1
        }
        return courant
    }

    /** Le résultat d'UN morceau de paramètres SGR : le style après le
     *  code et le nombre de paramètres SUPPLÉMENTAIRES consommés (formes
     *  38;5;n / 38;2;r;g;b et 48;…). */
    private fun appliquerMorceau(
        morceau: String,
        suivant: String?,
        style: QuadrupletStyle,
    ): Pair<QuadrupletStyle, Int> {
        val code = if (morceau.isEmpty()) CODE_RESET else morceau.toIntOrNull() ?: CODE_INCONNU
        return when (code) {
            CODE_RESET -> Pair(QuadrupletStyle(gras = false, italique = false, couleur = null, vive = false), 0)

            CODE_GRAS -> Pair(style.copy(gras = true), 0)

            CODE_ITALIQUE -> Pair(style.copy(italique = true), 0)

            CODE_GRAS_HORS -> Pair(style.copy(gras = false), 0)

            CODE_ITALIQUE_HORS -> Pair(style.copy(italique = false), 0)

            CODE_COULEUR_DEFAUT -> Pair(style.copy(couleur = null, vive = false), 0)

            in CODE_COULEUR_MIN..CODE_COULEUR_MAX ->
                Pair(style.copy(couleur = CouleurAnsi.entries[code - CODE_COULEUR_MIN], vive = false), 0)

            in CODE_COULEUR_VIVE_MIN..CODE_COULEUR_VIVE_MAX ->
                Pair(style.copy(couleur = CouleurAnsi.entries[code - CODE_COULEUR_VIVE_MIN], vive = true), 0)

            CODE_256 ->
                // 38;5;n / 38;2;r;g;b : consommés sans rendu (palette
                // minimale 8+8 — jamais d'interprétation hors périmètre).
                Pair(style.copy(couleur = null, vive = false), sautForme(suivant))

            CODE_FOND ->
                // 48;5;n / 48;2;r;g;b : fond, consommé sans rendu —
                // même comptage que le texte (38;…).
                Pair(style, sautForme(suivant))

            else -> Pair(style, 0)
        }
    }

    /** Le nombre de paramètres sautés quand la forme étendue 38;5;n (2)
     *  ou 38;2;r;g;b (4) est détectée au morceau suivant — sans ce saut,
     *  le dernier octet serait relu comme un code SGR autonome. */
    private fun sautForme(suivant: String?): Int = when (suivant?.toIntOrNull()) {
        FORME_PALETTE -> DEUX_SUPPLEMENTAIRES
        FORME_RGB -> QUATRE_SUPPLEMENTAIRES
        else -> ZERO_SUPPLEMENTAIRES
    }

    /** Style résultat d'un SGR : gras, italique, couleur, vive. */
    private data class QuadrupletStyle(
        val gras: Boolean,
        val italique: Boolean,
        val couleur: CouleurAnsi?,
        val vive: Boolean,
    )

    /*
     * Codes SGR — un domaine FERMÉ de constantes sémantiques du
     * protocole ANSI (même précédent que RetentionIa : la règle
     * MagicNumber ne s'applique pas à un domaine fermé).
     */
    @Suppress("MagicNumber")
    private const val CODE_RESET = 0

    @Suppress("MagicNumber")
    private const val CODE_GRAS = 1

    @Suppress("MagicNumber")
    private const val CODE_ITALIQUE = 3

    @Suppress("MagicNumber")
    private const val CODE_GRAS_HORS = 22

    @Suppress("MagicNumber")
    private const val CODE_ITALIQUE_HORS = 23

    @Suppress("MagicNumber")
    private const val CODE_COULEUR_MIN = 30

    @Suppress("MagicNumber")
    private const val CODE_COULEUR_MAX = 37

    @Suppress("MagicNumber")
    private const val CODE_COULEUR_DEFAUT = 39

    @Suppress("MagicNumber")
    private const val CODE_COULEUR_VIVE_MIN = 90

    @Suppress("MagicNumber")
    private const val CODE_COULEUR_VIVE_MAX = 97

    @Suppress("MagicNumber")
    private const val CODE_256 = 38

    @Suppress("MagicNumber")
    private const val CODE_FOND = 48

    @Suppress("MagicNumber")
    private const val CODE_INCONNU = -1

    @Suppress("MagicNumber")
    private const val FORME_PALETTE = 5

    @Suppress("MagicNumber")
    private const val FORME_RGB = 2

    @Suppress("MagicNumber")
    private const val DEUX_SUPPLEMENTAIRES = 2

    @Suppress("MagicNumber")
    private const val QUATRE_SUPPLEMENTAIRES = 4

    @Suppress("MagicNumber")
    private const val ZERO_SUPPLEMENTAIRES = 0

    /** Bornes de l'octet final d'un CSI (0x40-0x7E). */
    @Suppress("MagicNumber")
    private const val FINAL_MIN = 0x40

    @Suppress("MagicNumber")
    private const val FINAL_MAX = 0x7E
}
