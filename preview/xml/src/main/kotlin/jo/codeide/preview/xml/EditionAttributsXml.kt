package jo.codeide.preview.xml

/**
 * ÉDITIONS D'ATTRIBUTS, DUPLICATION et SUPPRESSION (PE7d, maquette
 * `docs/mockups/apercu-xml.html` § « Édition d'attributs, annuler,
 * dupliquer, supprimer ») — FONCTIONS PURES sur le TEXTE du document,
 * sans Android : « Chaque action produit une édition de texte passée à
 * `SourceApercu.remplacer`, donc une seule entrée dans l'historique de
 * l'éditeur. »
 *
 * Contrat commun : le TEXTE courant + le span source de la vue éditée
 * ([InfoVueRendu.debutElement]/[finElement], porté par
 * [InsertionXml.SpanParent]) → UNE édition
 * [InsertionXml.EditionXml] `(debut, fin, remplacement)` — atomique,
 * un seul geste d'annulation dans l'éditeur. La position est dérivée en
 * re-marchant le texte VIVANT ([AligneurElementsXml.aligner], même
 * parseur que LayoutLib) : un span PÉRIMÉ (texte modifié depuis le
 * rendu, XML malformé) → `null`, JAMAIS d'écriture hasardeuse
 * (philosophie X4 — miroir [InsertionXml.inserer]).
 *
 * Mécanique reproduite (miroir `renderEditor` de la maquette) :
 * - `text`/`hint` LITTÉRAUX éditables (les `@string` restent en lecture
 *   seule — la valeur de référence se lit avec [valeurAttribut]) ;
 * - largeur/hauteur en modes wrap/match/fixe ([ModeDimension]) — le
 *   mode fixe porte la valeur en dp ;
 * - [dupliquer] clône l'élément, RENUMÉROTE ses identifiants
 *   ([InsertionXml.renumeroter], miroir `nextId`) et l'insère à la
 *   suite — l'élément doit posséder sa ligne (document indenté) ;
 * - [supprimer] retire le bloc de l'élément avec SA ligne.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions + ReturnCount +
 * LargeClass — la surface de dérivation (localisation, chevron,
 * attribut, valeur) vit au COMPLET dans ce seul objet (« un seul chemin
 * d'édition », PE7e) ; l'éclater exposerait ses internes ou les
 * dupliquerait. Chaque garde est une issue DISTINCTE du refus X4.
 */
@Suppress("TooManyFunctions", "ReturnCount", "LargeClass")
public object EditionAttributsXml {
    /** Axe d'une dimension éditée (miroir `largeur`/`hauteur` du volet). */
    public enum class Axe {
        LARGEUR,
        HAUTEUR,
    }

    /** Mode de dimension (miroir `seg2` : wrap | match | fixe). */
    public enum class ModeDimension {
        WRAP,
        MATCH,
        FIXE,
    }

    /** Noms d'attributs de dimension (le reste passe par [ecrireAttribut]). */
    private const val ATTR_LARGEUR: String = "android:layout_width"
    private const val ATTR_HAUTEUR: String = "android:layout_height"

    /** PE11g — minimum de la cible tactile (correctif « Passer à 48 dp »). */
    public const val CIBLE_TACTILE_DP: Int = 48

    /** Noms des minimums de taille (correctif de cible — PE11g). */
    private const val ATTR_LARGEUR_MINIMALE: String = "android:minWidth"
    private const val ATTR_HAUTEUR_MINIMALE: String = "android:minHeight"

    /** PE11h — l'attribut posé par « Remplir l'espace restant ». */
    private const val ATTR_POIDS: String = "android:layout_weight"

    /** PE11h — la valeur `0dp` (match_constraint) de la dimension calculée. */
    private const val ZERO_DP: String = "0dp"

    /** Les DEUX axes du correctif de cible : dimension → minimum (PE11g). */
    private val AXES_CIBLE: List<Pair<String, String>> =
        listOf(ATTR_LARGEUR to ATTR_LARGEUR_MINIMALE, ATTR_HAUTEUR to ATTR_HAUTEUR_MINIMALE)

    /** Regex d'une valeur de dimension FIXE en dp (44dp, 44.5dp — aussi 0dp). */
    private val REGEX_DIMENSION_DP: Regex = Regex("""^(\d+(?:\.\d+)?)dp$""")

    /** Valeurs littérales des modes (miroir `attrsOf` de la maquette). */
    private const val VALEUR_WRAP: String = "wrap_content"
    private const val VALEUR_MATCH: String = "match_parent"

    /** Valeur minimale d'une dimension fixe en dp (miroir `Math.max(1, …)`). */
    private const val DIMENSION_MIN_DP: Int = 1

    /** Indentation de repli quand la balise est compacte (une seule ligne). */
    private const val PAS_INDENTATION: String = "    "

    /** Regex d'un attribut avec PLAGES : nom (groupe 1), valeur (groupe 2). */
    private val REGEX_ATTRIBUT: Regex = Regex("""([\w:.\-]+)\s*=\s*"([^"]*)"""")

    // ── Seaux de l'ordre conventionnel des attributs ([ordreAttribut]) ──
    private const val SEAU_IDENTIFIANT: Int = 1
    private const val SEAU_LARGEUR: Int = 2
    private const val SEAU_HAUTEUR: Int = 3
    private const val SEAU_LAYOUT: Int = 4
    private const val SEAU_RESTE: Int = 5

    // ── Lectures ─────────────────────────────────────────────────────────

    /**
     * La valeur est-elle un LITTÉRAL éditable (PE11g) : non vide, sans
     * référence `@…` ni `?attr/…` — la garde « codé en dur » du moteur
     * ([ProblemesRendu.texteCodeDur]) ET de l'extraction
     * (`PreviewViewModel.extractionPossible`) : UNE seule définition.
     */
    public fun estLitteral(valeur: String?): Boolean =
        valeur != null && valeur.isNotEmpty() && !valeur.startsWith("@") && !valeur.startsWith("?")

    /**
     * La valeur ACTUELLE d'un attribut de la vue au span (lecture dans le
     * texte VIVANT — le volet Attributs s'en alimente). `null` = attribut
     * absent, span périmé ou XML illisible.
     */
    public fun valeurAttribut(
        texte: String,
        span: InsertionXml.SpanParent,
        nomAttribut: String,
    ): String? {
        val balise = baliseOuvrante(texte, span) ?: return null
        val match = regexAttribut(nomAttribut).find(balise) ?: return null
        return match.groupValues[1]
    }

    // ── Écritures ────────────────────────────────────────────────────────

    /**
     * Pose `nom="valeur"` sur la vue au span : l'attribut EXISTANT voit sa
     * valeur remplacée (plage de la valeur seule) ; l'attribut ABSENT est
     * ajouté avant la fermeture de la balise ouvrante — sur sa PROPRE
     * ligne à l'indentation du dernier attribut (balise multi-lignes),
     * sinon en ligne (miroir [InsertionXml.contraindre]).
     */
    public fun ecrireAttribut(
        texte: String,
        span: InsertionXml.SpanParent,
        nomAttribut: String,
        valeur: String,
    ): EditionAttributsXmlResultat? {
        val localise = localiser(texte, span) ?: return null
        val balise = texte.substring(localise.debut, localise.chevron + 1)
        val injection = attributInjection(nomAttribut, valeur)
        val existant = regexAttribut(nomAttribut).find(balise)
        if (existant != null) {
            // Remplacement de la VALEUR seule (groupe 1) — le nom et les
            // guillemets restent en place, l'alignement du document bouge
            // le moins possible.
            val debutValeur = localise.debut + existant.groups[1]!!.range.first
            val finValeur = localise.debut + existant.groups[1]!!.range.last + 1
            if (valeur == existant.groupValues[1]) return null
            return EditionAttributsXmlResultat(debutValeur, finValeur, echapper(valeur))
        }
        // Ajout : position = avant le « / » d'une auto-fermante, sinon le
        // chevron (miroir [InsertionXml.contraindre]).
        val slash = texte.lastIndexOf('/', (localise.chevron - 1).coerceAtLeast(localise.debut))
        val fermetureAuto =
            slash > localise.debut && texte.substring(slash + 1, localise.chevron).all { it == ' ' }
        val position = if (fermetureAuto) slash else localise.chevron
        val multiLignes = balise.contains('\n')
        val texteInsere =
            if (multiLignes) {
                val indent = indentationDernierAttribut(balise)
                "\n$indent$injection "
            } else if (position > localise.debut && texte[position - 1] != ' ') {
                " $injection"
            } else {
                injection
            }
        return EditionAttributsXmlResultat(position, position, texteInsere)
    }

    /**
     * Pose la dimension [axe] au [ModeDimension] demandé (miroir `seg2` du
     * volet) : WRAP → `wrap_content`, MATCH → `match_parent`, FIXE → la
     * valeur dp (bornée à 1 au minimum, miroir maquette). FIXE sans
     * valeur → `null` (jamais de dimension devinée).
     */
    public fun ecrireDimension(
        texte: String,
        span: InsertionXml.SpanParent,
        axe: Axe,
        mode: ModeDimension,
        valeurDp: Int?,
    ): EditionAttributsXmlResultat? {
        val nom = if (axe == Axe.LARGEUR) ATTR_LARGEUR else ATTR_HAUTEUR
        val valeur =
            when (mode) {
                ModeDimension.WRAP -> VALEUR_WRAP
                ModeDimension.MATCH -> VALEUR_MATCH
                ModeDimension.FIXE -> "${(valeurDp ?: return null).coerceAtLeast(DIMENSION_MIN_DP)}dp"
            }
        return ecrireAttribut(texte, span, nom, valeur)
    }

    /**
     * Les AJUSTEMENTS PAR-LAYOUT du relâchement d'une poignée (PE14-S2
     * C3, ADR 0138) : ce que le geste écrit AU-DELÀ des deux axes pour
     * que la vue reste SUR PLACE pendant que sa taille change — miroir
     * les « targets par layout » du designer de la référence
     * (`RelativeResizeTarget`, `ConstraintResizeTarget`).
     *
     * @property retirerPoids un `layout_weight` non nul CONTREDIT la
     *           taille fixe écrite (parent LINEAR/APPBAR — il absorbait
     *           l'espace restant PAR-DESSUS : « rien ne bougeait »).
     * @property marges les marges à réécrire (côté → dp) — enfant de
     *           RelativeLayout, le bord tenu suit le doigt par SA marge.
     * @property positionOutilsX/Y les positions absolues du dialecte
     *           `tools:` (dp, enfant de ConstraintLayout) — posées SEULEMENT
     *           si le document déclare déjà le dialecte (arbitrage D3).
     */
    public data class AjustementsRedimension(
        public val retirerPoids: Boolean = false,
        public val marges: List<Pair<Cote, Int>> = emptyList(),
        public val positionOutilsX: Int? = null,
        public val positionOutilsY: Int? = null,
    )

    /** La base des marges de disposition (miroir les 4 champs `.mrow`). */
    private const val BASE_MARGE: String = "android:layout_margin"

    /** Les positions absolues du dialecte `tools:` de ConstraintLayout (C3). */
    private const val ATTR_OUTIL_X: String = "tools:layout_editor_absoluteX"
    private const val ATTR_OUTIL_Y: String = "tools:layout_editor_absoluteY"

    /**
     * Pose les DEUX dimensions FIXES + les AJUSTEMENTS PAR-LAYOUT en UNE
     * SEULE ÉDITION (PE11l + PE14-S2 : redimensionnement par POIGNÉE,
     * miroir le `mutate` groupé de la maquette — un geste = une entrée
     * d'historique) : chaque écriture est composée sur le texte remanié
     * (chaque étape décale le span de son propre delta), puis la balise
     * ouvrante ENTIÈRE est remplacée par sa version remaniée —
     * [SourceApercu.remplacer] n'accepte qu'une plage, l'édition reste
     * ATOMIQUE.
     *
     * `null` si RIEN ne change (les deux valeurs déjà posées, aucun
     * ajustement effectif) ou span périmé (jamais d'exception, règle 6).
     */
    public fun ecrireRedimension(
        texte: String,
        span: InsertionXml.SpanParent,
        largeurDp: Int,
        hauteurDp: Int,
        ajustements: AjustementsRedimension = AjustementsRedimension(),
    ): EditionAttributsXmlResultat? {
        val localise = localiser(texte, span) ?: return null
        var ecrits = false
        var texteCourant = texte
        var deltaCumule = 0

        fun etape(edition: EditionAttributsXmlResultat?) {
            if (edition == null) return
            ecrits = true
            deltaCumule += delta(edition)
            texteCourant = appliquer(texteCourant, edition)
        }
        // 1) LA LARGEUR puis LA HAUTEUR sur le texte remanié (le span
        // suit le décalage de chaque écriture — l'édition vit DANS la balise).
        etape(ecrireDimension(texte, span, Axe.LARGEUR, ModeDimension.FIXE, largeurDp))
        etape(
            ecrireDimension(
                texteCourant,
                spanDecale(span, deltaCumule),
                Axe.HAUTEUR,
                ModeDimension.FIXE,
                hauteurDp,
            ),
        )
        // 2) Le POIDS qui contredit la taille fixe (C3 cas A).
        if (ajustements.retirerPoids) {
            etape(retirerAttributs(texteCourant, spanDecale(span, deltaCumule), listOf(ATTR_POIDS)))
        }
        // 3) Les MARGES du bord tenu (C3 — [ecrireCote] éclate le
        // raccourci couvrant au besoin) ; si les DEUX variantes
        // Start/Left du côté coexistent au source, la SECONDE est
        // réalignée dans la MÊME édition (le pré-processeur du frère
        // renomme marginStart→marginLeft : une variante oubliée
        // l'emporterait silencieusement au rendu).
        for ((cote, valeurDp) in ajustements.marges) {
            etape(ecrireCote(texteCourant, spanDecale(span, deltaCumule), BASE_MARGE, cote, valeurDp))
            val margeEgale = "${valeurDp}dp"
            for (nom in nomsSpecifiquesCote(BASE_MARGE, cote)) {
                val valeur = valeurAttribut(texteCourant, spanDecale(span, deltaCumule), nom)
                if (valeur != null && valeur != margeEgale) {
                    etape(definir(texteCourant, spanDecale(span, deltaCumule), nom, margeEgale))
                }
            }
        }
        // 4) Les positions du dialecte `tools:` (C3 — jamais posées si
        // le document ne déclare pas déjà le dialecte, arbitrage D3).
        ajustements.positionOutilsX?.let { x ->
            etape(definir(texteCourant, spanDecale(span, deltaCumule), ATTR_OUTIL_X, "${x}dp"))
        }
        ajustements.positionOutilsY?.let { y ->
            etape(definir(texteCourant, spanDecale(span, deltaCumule), ATTR_OUTIL_Y, "${y}dp"))
        }
        if (!ecrits) return null
        // 5) UNE édition : la balise originale remplacée par la remaniée
        // (le texte courant contient toutes les étapes appliquées).
        val finBaliseOriginale = localise.chevron + 1
        val finBaliseRemaniee = finBaliseOriginale + deltaCumule
        if (finBaliseRemaniee > texteCourant.length) return null
        val baliseRemaniee = texteCourant.substring(localise.debut, finBaliseRemaniee)
        return EditionAttributsXmlResultat(localise.debut, finBaliseOriginale, baliseRemaniee)
    }

    /** Le span décalé du delta cumulé des écritures précédentes. */
    private fun spanDecale(
        span: InsertionXml.SpanParent,
        delta: Int,
    ): InsertionXml.SpanParent = InsertionXml.SpanParent(span.debut, span.fin + delta)

    /**
     * CORRECTION RAPIDE « Passer à 48 dp » (PE11g — miroir `quickFixes`
     * de la maquette : `v.w = Math.max(v.w, 48)` sur une dimension FIXE,
     * `setEx(minWidth, '48dp')` sinon), les DEUX axes en UNE SEULE
     * édition (patron [ecrireDimensions] : composition sur la balise,
     * puis remplacement ATOMIQUE de la balise ouvrante).
     *
     * Gardes : `0dp` (match_constraint) n'est PAS une dimension fixe — il
     * suit la voie du MINIMUM, jamais réécrit en 48 dp ; un minimum déjà
     * ≥ 48 dp n'est jamais ABAISSÉ (jamais de régression de cible).
     *
     * `null` si RIEN ne change (déjà ≥ 48 dp partout) ou span périmé
     * (jamais d'exception, règle 6).
     */
    public fun elargirCibleTactile(
        texte: String,
        span: InsertionXml.SpanParent,
    ): EditionAttributsXmlResultat? {
        val localise = localiser(texte, span) ?: return null
        var texteCourant = texte
        var deltaCumule = 0
        var ecrit = false
        for ((nom, valeur) in ecrituresCible(texte, span)) {
            val edition =
                ecrireAttribut(
                    texteCourant,
                    InsertionXml.SpanParent(span.debut, span.fin + deltaCumule),
                    nom,
                    valeur,
                ) ?: continue
            texteCourant = appliquer(texteCourant, edition)
            deltaCumule += delta(edition)
            ecrit = true
        }
        // Rien n'a changé (déjà ≥ 48 dp partout) : pas d'édition — le
        // DRAPEAU décide, pas le delta (deux remplacements de MÊME
        // longueur, 44dp → 48dp, laissent un delta cumulé nul).
        if (!ecrit) return null
        // UNE édition : la balise ouvrante originale remplacée par la remaniée.
        val finBaliseOriginale = localise.chevron + 1
        val baliseRemaniee = texteCourant.substring(localise.debut, finBaliseOriginale + deltaCumule)
        return EditionAttributsXmlResultat(localise.debut, finBaliseOriginale, baliseRemaniee)
    }

    /**
     * ACTION RAPIDE « Remplir l'espace restant » (PE11h — miroir l'`items`
     * du `quick` linéaire de la maquette : `setEx(layout_weight, '1')` +
     * `v.w/v.h = 0dp` selon l'orientation du parent) : pose
     * `android:layout_weight="1"` ET passe la dimension de l'[axe] à
     * `0dp` (match_constraint) en UNE SEULE édition (patron
     * [elargirCibleTactile] : composition des écritures sur le texte
     * remanié, puis remplacement ATOMIQUE de la balise ouvrante — un
     * geste = une entrée d'historique).
     *
     * `null` si RIEN ne change (poids déjà 1 ET dimension déjà `0dp`) ou
     * span périmé (jamais d'exception, règle 6).
     */
    public fun remplirEspaceRestant(
        texte: String,
        span: InsertionXml.SpanParent,
        axe: Axe,
    ): EditionAttributsXmlResultat? {
        val localise = localiser(texte, span) ?: return null
        val dimension = if (axe == Axe.LARGEUR) ATTR_LARGEUR else ATTR_HAUTEUR
        var texteCourant = texte
        var deltaCumule = 0
        var ecrit = false
        for ((nom, valeur) in listOf(ATTR_POIDS to "1", dimension to ZERO_DP)) {
            val edition =
                ecrireAttribut(
                    texteCourant,
                    InsertionXml.SpanParent(span.debut, span.fin + deltaCumule),
                    nom,
                    valeur,
                ) ?: continue
            texteCourant = appliquer(texteCourant, edition)
            deltaCumule += delta(edition)
            ecrit = true
        }
        // Déjà rempli (poids 1 + dimension 0dp) : pas d'édition — le
        // DRAPEAU décide, pas le delta (deux remplacements de MÊME
        // longueur laisseraient un delta nul).
        if (!ecrit) return null
        // UNE édition : la balise ouvrante originale remplacée par la remaniée.
        val finBaliseOriginale = localise.chevron + 1
        val baliseRemaniee = texteCourant.substring(localise.debut, finBaliseOriginale + deltaCumule)
        return EditionAttributsXmlResultat(localise.debut, finBaliseOriginale, baliseRemaniee)
    }

    /**
     * Les écritures du correctif de cible (pures, sur le texte ORIGINAL —
     * chaque attribut lu ne dépend pas des écritures précédentes) :
     * dimension fixe < 48 dp → 48 dp ; sinon (wrap, match, 0dp, @dimen,
     * absent) → minimum 48 dp s'il n'est pas déjà posé ≥ 48 dp.
     */
    private fun ecrituresCible(
        texte: String,
        span: InsertionXml.SpanParent,
    ): List<Pair<String, String>> =
        buildList {
            for ((dimension, minimum) in AXES_CIBLE) {
                val dpDimension = valeurAttribut(texte, span, dimension)?.let(::dpFixes)
                when {
                    dpDimension != null && dpDimension > 0 -> {
                        if (dpDimension < CIBLE_TACTILE_DP) add(dimension to "${CIBLE_TACTILE_DP}dp")
                    }

                    else -> {
                        val dpMinimum = valeurAttribut(texte, span, minimum)?.let(::dpFixes) ?: 0.0
                        if (dpMinimum < CIBLE_TACTILE_DP) add(minimum to "${CIBLE_TACTILE_DP}dp")
                    }
                }
            }
        }

    /** La valeur FIXE en dp d'une dimension (null si wrap/match/absente). */
    private fun dpFixes(valeur: String): Double? =
        REGEX_DIMENSION_DP
            .matchEntire(valeur)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()

    /** Applique une édition à un texte (composition pure, hors port). */
    private fun appliquer(
        texte: String,
        edition: EditionAttributsXmlResultat,
    ): String = texte.substring(0, edition.debut) + edition.remplacement + texte.substring(edition.fin)

    /** Le décalage de longueur d'une édition (0 si absente). */
    private fun delta(edition: EditionAttributsXmlResultat?): Int =
        edition?.let { it.remplacement.length - (it.fin - it.debut) } ?: 0

    // ── PE7e : lecture, définition générale, côtés ───────────────────────

    /**
     * UN attribut du source : nom qualifié, valeur BRUTE (ce qui est
     * écrit — une référence `@string/…` reste une référence) et plage du
     * NOM (debut inclus, fin exclue — la plage de retrait).
     */
    public data class AttributSource(
        val nom: String,
        val valeur: String,
        val debut: Int,
        val fin: Int,
    )

    /** UN côté d'une marge ou d'un padding ([ecrireCote]/[valeurCote]). */
    public enum class Cote {
        HAUT,
        BAS,
        DEBUT,
        FIN,
    }

    /**
     * Les attributs de l'élément au span, DANS L'ORDRE DU SOURCE (miroir
     * `attrsOf` de la maquette) : le volet Attributs s'en alimente
     * (lecture seule — contraintes, valeurs courantes). `null` = span
     * périmé ou XML illisible (jamais d'exception).
     */
    public fun lire(
        texte: String,
        span: InsertionXml.SpanParent,
    ): List<AttributSource>? {
        val localise = localiser(texte, span) ?: return null
        val balise = texte.substring(localise.debut, localise.chevron + 1)
        return analyserAttributs(balise, localise.debut)
    }

    /**
     * DÉFINIT `nom="valeur"` sur la vue au span (miroir `setEx` de la
     * maquette PE7e) — LA voie d'écriture générale des attributs :
     * - valeur NON VIDE et attribut présent → la valeur seule est
     *   remplacée (le document bouge le moins possible) ;
     * - attribut ABSENT → ajouté à la position CONVENTIONNELLE des
     *   layouts ([ordreAttribut] : id, largeur, hauteur, autres attributs
     *   layout_ (android ou app), reste) à l'indentation détectée ;
     * - valeur VIDE → l'attribut est RETIRÉ avec son séparateur (pas de
     *   ligne vide résiduelle) ;
     * - un préfixe `app:`/`tools:` sans `xmlns` sur la racine ajoute la
     *   déclaration DANS LA MÊME ÉDITION ([composerEdition] — plage
     *   englobante, une seule entrée d'historique).
     *
     * `null` = span périmé, XML illisible ou valeur identique (refus X4 —
     * jamais d'écriture hasardeuse).
     */
    public fun definir(
        texte: String,
        span: InsertionXml.SpanParent,
        nom: String,
        valeur: String,
    ): EditionAttributsXmlResultat? = definirInterne(texte, span, nom, valeur, echappee = true)

    /**
     * DÉFINIT une RÉFÉRENCE (`@string/x`, `@color/y`… — PE8c) : identique
     * à [definir] SANS l'échappement des littéraux — le `@` initial reste
     * une référence au lieu d'être neutralisé en `\@`.
     */
    public fun definirReference(
        texte: String,
        span: InsertionXml.SpanParent,
        nom: String,
        valeur: String,
    ): EditionAttributsXmlResultat? = definirInterne(texte, span, nom, valeur, echappee = false)

    /** Le CORPS commun de [definir]/[definirReference] (échappement au choix). */
    private fun definirInterne(
        texte: String,
        span: InsertionXml.SpanParent,
        nom: String,
        valeur: String,
        echappee: Boolean,
    ): EditionAttributsXmlResultat? {
        val valeurFinale = if (echappee) echapper(valeur) else valeur
        val localise = localiser(texte, span) ?: return null
        val balise = texte.substring(localise.debut, localise.chevron + 1)
        val attrs = analyserAttributs(balise, localise.debut)
        val existant = attrs.firstOrNull { it.nom == nom }
        return when {
            valeur.isEmpty() -> {
                if (existant == null) {
                    null
                } else {
                    retirerAttribut(texte, existant)
                }
            }

            existant != null -> {
                if (valeur == existant.valeur) return null
                remplacerValeur(balise, localise, existant, valeurFinale)
            }

            else -> {
                ajouterAttribut(texte, localise, nom, valeurFinale, attrs)
            }
        }
    }

    /**
     * La valeur COURANTE d'un côté ([Cote]) parmi les attributs lus
     * ([lire]) : l'attribut SPÉCIFIQUE gagne (Start avant Left, miroir la
     * résolution Android), puis les raccourcis (Horizontal/Vertical, puis
     * plein). `null` = aucun attribut ne couvre ce côté.
     */
    public fun valeurCote(
        attributs: List<AttributSource>,
        base: String,
        cote: Cote,
    ): String? {
        val specifiques =
            when (cote) {
                Cote.DEBUT -> listOf(base + "Start", base + "Left")
                Cote.FIN -> listOf(base + "End", base + "Right")
                Cote.HAUT -> listOf(base + "Top")
                Cote.BAS -> listOf(base + "Bottom")
            }
        val raccourcis =
            when (cote) {
                Cote.DEBUT, Cote.FIN -> listOf(base + "Horizontal", base)
                Cote.HAUT, Cote.BAS -> listOf(base + "Vertical", base)
            }
        for (nom in specifiques + raccourcis) {
            val attr = attributs.firstOrNull { it.nom == nom }
            if (attr != null && attr.valeur.isNotEmpty()) return attr.valeur
        }
        return null
    }

    /**
     * Écrit UN CÔTÉ d'une marge ou d'un padding (miroir les 4 champs
     * `.mrow` de la maquette PE7e) : l'attribut spécifique présent est
     * modifié ; sinon le RACCOURCI couvrant est ÉCLATÉ en attributs
     * explicites (les autres côtés conservent la valeur du raccourci),
     * DANS LA MÊME ÉDITION ; sinon le côté est ajouté (Start/End —
     * supportsRtl est actif). `null` = span périmé ou valeur identique.
     */
    public fun ecrireCote(
        texte: String,
        span: InsertionXml.SpanParent,
        base: String,
        cote: Cote,
        valeurDp: Int,
    ): EditionAttributsXmlResultat? {
        if (valeurDp < 0) return null
        val localise = localiser(texte, span) ?: return null
        val balise = texte.substring(localise.debut, localise.chevron + 1)
        val attrs = analyserAttributs(balise, localise.debut)
        val valeur = "${valeurDp}dp"
        val nomCible = nomCote(base, cote)
        val specifique = attrs.firstOrNull { it.nom in nomsSpecifiquesCote(base, cote) }
        if (specifique != null) {
            if (specifique.valeur == valeur) return null
            return remplacerValeur(balise, localise, specifique, echapper(valeur))
        }
        val raccourci =
            attrs.firstOrNull {
                it.nom in nomsRaccourcisCote(base, cote) && it.valeur.isNotEmpty()
            }
        if (raccourci != null) {
            // Le raccourci couvre DÉJÀ la valeur demandée : no-op (idempotence).
            if (raccourci.valeur == valeur) return null
            return eclaterRaccourci(texte, raccourci, nomCible, valeur)
        }
        return ajouterAttribut(texte, localise, nomCible, echapper(valeur), attrs)
    }

    // ── Contraintes (PE8b — l'édition des ancres du ConstraintLayout) ────

    /**
     * La CIBLE d'une ancre de contrainte : le parent, une vue FRÈRE par
     * son identifiant, ou AUCUNE (l'ancre se retire).
     */
    public sealed class CibleContrainte {
        /** Le parent du ConstraintLayout (`"parent"` — valeur de plateforme). */
        public object Parent : CibleContrainte()

        /** Un frère par son identifiant (sans préfixe). */
        public data class Vue(
            val nom: String,
        ) : CibleContrainte()

        /** Aucune — l'attribut de cible part avec sa marge si demandé. */
        public object Aucune : CibleContrainte()
    }

    /** L'état LU d'une ancre (cible courante + marge courante). */
    public data class ContrainteLue(
        val cote: Cote,
        /** `"parent"`, `"@id/x"` ou `null` (ancre absente). */
        val cible: String?,
        /** La marge de l'ancre en dp (l'attribut absent → `null`). */
        val margeDp: Int?,
    )

    /**
     * Lit les QUATRE ancres de la vue au span (toujours 4 entrées, dans
     * l'ordre Début/Fin/Haut/Bas — miroir `.crow` de la maquette) :
     * cible courante + marge courante, `null` quand l'attribut est absent.
     * `null` = span périmé ou XML illisible.
     */
    public fun lireContraintes(
        texte: String,
        span: InsertionXml.SpanParent,
    ): List<ContrainteLue>? {
        val localise = localiser(texte, span) ?: return null
        val balise = texte.substring(localise.debut, localise.chevron + 1)
        val attrs = analyserAttributs(balise, localise.debut)
        return listOf(Cote.DEBUT, Cote.FIN, Cote.HAUT, Cote.BAS).map { cote ->
            val ancre = nomAncre(cote)
            // N'IMPORTE QUELLE variante cible (`Top_toBottomOf` licite —
            // la cible peut viser un côté différent de l'ancre).
            val cible =
                attrs.firstOrNull { regexCibleContrainte(ancre).matches(it.nom) }?.valeur
            val marge =
                attrs
                    .firstOrNull { it.nom == nomMargeContrainte(ancre) }
                    ?.valeur
                    ?.removeSuffix("dp")
                    ?.toIntOrNull()
            ContrainteLue(cote, cible, marge)
        }
    }

    /**
     * Écrit l'ANCRE [cote] de la vue au span : la [cible] et/ou la
     * [margeDp] — UNE ÉDITION même quand les deux attributs bougent
     * ([composerEdition], xmlns:app posé au besoin). `null` en paramètre
     * = l'aspect n'est pas touché ; `Aucune` retire l'attribut de cible ;
     * marge `0` retire l'attribut (défaut plateforme). Une cible posée
     * sur une ancre DÉJÀ contrainte remplace la VALEUR (le CÔTÉ visé
     * reste — topologie minimale) ; une ancre neuve se pose même-côté
     * (`Top_toTopOf`). `null` en retour = refus (span périmé, nom de
     * frère invalide, auto-contrainte, aucune modification, marge
     * négative — X4, jamais d'écriture hasardeuse).
     */
    public fun ecrireContrainte(
        texte: String,
        span: InsertionXml.SpanParent,
        cote: Cote,
        cible: CibleContrainte?,
        margeDp: Int?,
    ): EditionAttributsXmlResultat? {
        if (cible == null && margeDp == null) return null
        if (margeDp != null && margeDp < 0) return null
        val localise = localiser(texte, span) ?: return null
        val ancre = nomAncre(cote)
        val nomCible = nomCibleContrainte(ancre)
        val nomMarge = nomMargeContrainte(ancre)
        val balise = texte.substring(localise.debut, localise.chevron + 1)
        val contexte =
            ContexteContrainte(
                texte = texte,
                balise = balise,
                localise = localise,
                attrs = analyserAttributs(balise, localise.debut),
            )
        val plan = PlanContrainte()
        if (cible != null) {
            val valeurDemandee = valeurCible(cible, contexte.texte, span) ?: return null
            if (!planifierCible(contexte, ancre, nomCible, valeurDemandee, plan)) return null
        }
        if (margeDp != null) planifierMarge(contexte, nomMarge, margeDp, plan)
        if (plan.vide) return null
        if (plan.insertions.isNotEmpty()) {
            plan.edits += insertionContrainte(contexte.texte, localise, contexte.attrs, plan.insertions)
        }
        if (plan.xmlnsRequis) xmlnsManquant(texte, nomCible)?.let { plan.edits += it }
        return composerEdition(texte, plan.edits)
    }

    /** Le contexte d'UNE contrainte : texte, balise ouvrante, localisation, attributs. */
    private class ContexteContrainte(
        val texte: String,
        val balise: String,
        val localise: ElementLocalise,
        val attrs: List<AttributSource>,
    )

    /** Le collecteur des éditions partielles d'UNE contrainte (PE8b). */
    private class PlanContrainte {
        val edits = mutableListOf<EditionPartielle>()
        val insertions = mutableListOf<Pair<String, String>>()

        var xmlnsRequis: Boolean = false

        val vide: Boolean
            get() = edits.isEmpty() && insertions.isEmpty()
    }

    /**
     * Planifie l'aspect CIBLE : valeur vide = retrait de TOUTES les
     * variantes (`Aucune`) ; ancre neuve = insertion même-côté (xmlns
     * requis) ; ancre existante = remplacement de la VALEUR (le côté
     * visé reste — topologie minimale). `false` = refus défensif.
     */
    private fun planifierCible(
        contexte: ContexteContrainte,
        ancre: String,
        nomCible: String,
        valeur: String,
        plan: PlanContrainte,
    ): Boolean {
        val variantes = contexte.attrs.filter { regexCibleContrainte(ancre).matches(it.nom) }
        return when {
            valeur.isEmpty() -> {
                // Aucune : TOUTES les variantes cible de l'ancre partent.
                variantes.forEach { attr -> plan.edits += versPartielle(retirerAttribut(contexte.texte, attr)) }
                true
            }

            variantes.isEmpty() -> {
                // Ancre neuve : insertion même-côté, xmlns requis.
                plan.xmlnsRequis = true
                plan.insertions += nomCible to valeur
                true
            }

            variantes.any { it.valeur == valeur } -> {
                // Déjà lié à cette cible : no-op (idempotence).
                true
            }

            else -> {
                // La CIBLE change, le côté visé reste (topologie minimale).
                val remplacee = remplacerValeur(contexte.balise, contexte.localise, variantes.first(), valeur)
                if (remplacee == null) return false
                plan.edits += versPartielle(remplacee)
                true
            }
        }
    }

    /** Planifie l'aspect MARGE : `0` = retrait, sinon écriture idempotente. */
    private fun planifierMarge(
        contexte: ContexteContrainte,
        nomMarge: String,
        margeDp: Int,
        plan: PlanContrainte,
    ) {
        val valeur = if (margeDp == 0) "" else "${margeDp}dp"
        val existant = contexte.attrs.firstOrNull { it.nom == nomMarge }
        when {
            existant == null && valeur.isEmpty() -> {
                // Rien à retirer : déjà absent (no-op pour cet aspect).
            }

            existant == null -> {
                plan.xmlnsRequis = true
                plan.insertions += nomMarge to valeur
            }

            valeur.isEmpty() -> {
                plan.edits += versPartielle(retirerAttribut(contexte.texte, existant))
            }

            existant.valeur == valeur -> {
                // Identique : no-op pour cet aspect (idempotence).
            }

            else -> {
                val remplacee = remplacerValeur(contexte.balise, contexte.localise, existant, valeur)
                if (remplacee != null) plan.edits += versPartielle(remplacee)
            }
        }
    }

    /** La valeur de cible (`parent`, `@id/x`) — refus défensifs en `null`. */
    private fun valeurCible(
        cible: CibleContrainte,
        texte: String,
        span: InsertionXml.SpanParent,
    ): String? {
        return when (cible) {
            is CibleContrainte.Parent -> {
                "parent"
            }

            is CibleContrainte.Vue -> {
                // Le nom de ressource suit la règle aapt (une seule voie
                // avec [RenommageIdentifiant.nomValide]) ; se contraindre
                // SOI-MÊME n'a aucun sens.
                if (!RenommageIdentifiant.nomValide(cible.nom)) return null
                if (cible.nom == identifiantDe(texte, span)) return null
                "@id/${cible.nom}"
            }

            is CibleContrainte.Aucune -> {
                ""
            }
        }
    }

    /** L'identifiant PROPRE de la vue au span (sans préfixe), `null` sinon. */
    private fun identifiantDe(
        texte: String,
        span: InsertionXml.SpanParent,
    ): String? {
        val brut = valeurAttribut(texte, span, "android:id") ?: return null
        return when {
            brut.startsWith("@+id/") -> brut.removePrefix("@+id/")
            brut.startsWith("@id/") -> brut.removePrefix("@id/")
            else -> null
        }
    }

    /**
     * L'INSERTION des attributs de contrainte demandés (un ou deux) —
     * après le dernier attribut du seau layout ([ordreAttribut]), sinon
     * avant le premier ; propre ligne en balise multi-lignes. Deux
     * attributs s'empilent dans la MÊME partielle (cible d'abord —
     * l'ordre conventionnel).
     */
    private fun insertionContrainte(
        texte: String,
        localise: ElementLocalise,
        attrs: List<AttributSource>,
        insertions: List<Pair<String, String>>,
    ): EditionPartielle {
        val multiLignes = texte.substring(localise.debut, localise.chevron).contains('\n')
        val ancre = attrs.lastOrNull { ordreAttribut(it.nom) <= SEAU_LAYOUT }
        val injections = insertions.map { (nom, valeur) -> "$nom=\"$valeur\"" }
        val injection =
            if (multiLignes) {
                val indent =
                    (ancre ?: attrs.firstOrNull())
                        ?.let { indentationDeLigne(texte, it.debut) }
                        ?: PAS_INDENTATION
                injections.joinToString("\n$indent")
            } else {
                injections.joinToString(" ")
            }
        return when {
            ancre != null -> {
                EditionPartielle(
                    ancre.fin,
                    ancre.fin,
                    if (multiLignes) {
                        "\n${indentationDeLigne(texte, ancre.debut)}$injection"
                    } else {
                        " $injection"
                    },
                )
            }

            attrs.isNotEmpty() -> {
                // Aucun attribut de seau layout : AVANT le premier —
                // propre ligne en multi-lignes (miroir [insererAttributPartielle]).
                EditionPartielle(
                    attrs.first().debut,
                    attrs.first().debut,
                    if (multiLignes) {
                        "$injection\n${indentationDeLigne(texte, attrs.first().debut)}"
                    } else {
                        "$injection "
                    },
                )
            }

            else -> {
                val position = positionInsertion(texte, localise)
                EditionPartielle(position, position, texteInsertionSimple(texte, position, injection))
            }
        }
    }

    /** `app:layout_constraint{Ancre}_to{Ancre}Of` — l'attribut de cible
     * MÊME-CÔTÉ (l'insertion d'une ancre neuve). */
    private fun nomCibleContrainte(ancre: String): String = "app:layout_constraint${ancre}_to${ancre}Of"

    /** TOUTE variante cible d'une ancre (`Top_toTopOf`, `Top_toBottomOf`,
     * `Top_toBaselineOf`… — la cible peut viser un autre côté). */
    private fun regexCibleContrainte(ancre: String): Regex = Regex("app:layout_constraint${ancre}_to[A-Za-z]+Of")

    /** `app:layout_constraint{Ancre}_margin` — l'attribut de marge. */
    private fun nomMargeContrainte(ancre: String): String = "app:layout_constraint${ancre}_margin"

    /** Le nom d'ANCRE d'un côté (Start/End/Top/Bottom — même règle que les marges). */
    private fun nomAncre(cote: Cote): String =
        when (cote) {
            Cote.DEBUT -> "Start"
            Cote.FIN -> "End"
            Cote.HAUT -> "Top"
            Cote.BAS -> "Bottom"
        }

    /** Une édition composée redevient une partielle (même forme). */
    private fun versPartielle(edition: EditionAttributsXmlResultat): EditionPartielle =
        EditionPartielle(edition.debut, edition.fin, edition.remplacement)

    // ── Détails PE7e (purs, privés) ──────────────────────────────────────

    /** Le nom canonique d'un côté (Start/End préféré — RTL actif). */
    private fun nomCote(
        base: String,
        cote: Cote,
    ): String =
        when (cote) {
            Cote.DEBUT -> base + "Start"
            Cote.FIN -> base + "End"
            Cote.HAUT -> base + "Top"
            Cote.BAS -> base + "Bottom"
        }

    /** Les noms SPÉCIFIQUES d'un côté (Start avant Left — miroir Android). */
    private fun nomsSpecifiquesCote(
        base: String,
        cote: Cote,
    ): List<String> =
        when (cote) {
            Cote.DEBUT -> listOf(base + "Start", base + "Left")
            Cote.FIN -> listOf(base + "End", base + "Right")
            Cote.HAUT -> listOf(base + "Top")
            Cote.BAS -> listOf(base + "Bottom")
        }

    /** Les RACCOURCIS couvrant un côté (directionnel avant plein). */
    private fun nomsRaccourcisCote(
        base: String,
        cote: Cote,
    ): List<String> =
        when (cote) {
            Cote.DEBUT, Cote.FIN -> listOf(base + "Horizontal", base)
            Cote.HAUT, Cote.BAS -> listOf(base + "Vertical", base)
        }

    /**
     * L'ORDRE conventionnel des attributs d'un layout (miroir Android
     * Studio) : id, largeur, hauteur, autres attributs layout_ (android
     * ou app), reste — l'ajout se place au BOUT de son seau.
     */
    private fun ordreAttribut(nom: String): Int =
        when {
            nom == "android:id" -> SEAU_IDENTIFIANT
            nom == "android:layout_width" -> SEAU_LARGEUR
            nom == "android:layout_height" -> SEAU_HAUTEUR
            nom.startsWith("android:layout_") || nom.startsWith("app:layout_") -> SEAU_LAYOUT
            else -> SEAU_RESTE
        }

    /** Les attributs d'une balise ouvrante (plages ABSOLUES couvrant
     * l'ATTRIBUT COMPLET : nom + « = » + valeur + guillemets — PE8b : les
     * insertions après ancre et l'éclatement des raccourcis exigent la
     * fin RÉELLE de l'attribut, pas celle du nom). */
    private fun analyserAttributs(
        balise: String,
        base: Int,
    ): List<AttributSource> {
        val resultat = mutableListOf<AttributSource>()
        for (match in REGEX_ATTRIBUT.findAll(balise)) {
            resultat +=
                AttributSource(
                    nom = match.groupValues[1],
                    valeur = match.groupValues[2],
                    debut = base + match.range.first,
                    fin = base + match.range.last + 1,
                )
        }
        return resultat
    }

    /** Le remplacement de la VALEUR seule (le nom et les guillemets restent). */
    private fun remplacerValeur(
        balise: String,
        localise: ElementLocalise,
        existant: AttributSource,
        valeur: String,
    ): EditionAttributsXmlResultat? {
        val plageValeur = plageValeur(balise, localise.debut, existant.nom) ?: return null
        return EditionAttributsXmlResultat(plageValeur.first, plageValeur.second, valeur)
    }

    /** La plage ABSOLUE de la valeur d'un attribut (groupe 2 du regex). */
    private fun plageValeur(
        balise: String,
        base: Int,
        nom: String,
    ): Pair<Int, Int>? {
        for (match in REGEX_ATTRIBUT.findAll(balise)) {
            if (match.groupValues[1] == nom) {
                return base + match.groups[2]!!.range.first to
                    base + match.groups[2]!!.range.last + 1
            }
        }
        return null
    }

    /**
     * Le retrait PROPRE : l'attribut COMPLET (nom, « = », valeur et
     * guillemets — la plage d'[AttributSource] le couvre depuis PE8b)
     * part ; seul sur sa ligne, il part AVEC elle ; en ligne, un
     * séparateur part avec lui (pas de `="x"` résiduel).
     */
    private fun retirerAttribut(
        texte: String,
        attr: AttributSource,
    ): EditionAttributsXmlResultat {
        val finComplete = attr.fin.coerceAtMost(texte.length)
        val debutLigne = texte.lastIndexOf('\n', (attr.debut - 1).coerceAtLeast(0)) + 1
        val finLigne = texte.indexOf('\n', finComplete).let { if (it < 0) texte.length else it }
        val seulSurLigne =
            texte.substring(debutLigne, attr.debut).all { it == ' ' || it == '\t' } &&
                texte.substring(finComplete, finLigne).all { it == ' ' || it == '\t' }
        if (seulSurLigne) {
            // La ligne entière part, retour compris (pas de vide résiduel).
            val finAvecRetour = if (finLigne < texte.length) finLigne + 1 else finLigne
            return EditionAttributsXmlResultat(debutLigne, finAvecRetour, "")
        }
        // En ligne : un séparateur part avec lui (avant ou après).
        if (finComplete < texte.length && texte[finComplete] == ' ') {
            return EditionAttributsXmlResultat(attr.debut, finComplete + 1, "")
        }
        return EditionAttributsXmlResultat((attr.debut - 1).coerceAtLeast(0), finComplete, "")
    }

    /**
     * RETIRE plusieurs attributs de la vue au span en UNE SEULE ÉDITION
     * (PE12c — le bouton ↺ d'un groupe réinitialise TOUS ses attributs,
     * un seul annuler : miroir le `names.forEach(… setEx(… ''))` du
     * `fieldBox` de la maquette). Les attributs ABSENTS du document sont
     * ignorés ; `null` si AUCUN n'est défini (rien à écrire) ou si le
     * span ne localise pas l'élément. Les plages des retraits se
     * FUSIONNENT quand elles partagent un séparateur (un espace ne part
     * qu'une fois — le retrait isolé le préfère APRÈS, au plus près).
     */
    public fun retirerAttributs(
        texte: String,
        span: InsertionXml.SpanParent,
        noms: List<String>,
    ): EditionAttributsXmlResultat? {
        val localise = localiser(texte, span) ?: return null
        val balise = texte.substring(localise.debut, localise.chevron + 1)
        val attrs = analyserAttributs(balise, localise.debut)
        val retraits =
            noms
                .distinct()
                .mapNotNull { nom -> attrs.firstOrNull { it.nom == nom } }
                .map { retirerAttribut(texte, it) }
                .sortedBy { it.debut }
        if (retraits.isEmpty()) return null
        // Les plages à supprimer, FUSIONNÉES par chevauchement : tout est
        // suppression, l'union des caractères suffit (deux retraits qui se
        // partagent le séparateur ne l'emportent qu'une fois).
        val plages = mutableListOf(retraits.first().debut..retraits.first().fin)
        for (retrait in retraits.drop(1)) {
            val plage = retrait.debut..retrait.fin
            val derniere = plages.last()
            if (plage.first <= derniere.last) {
                plages[plages.size - 1] = derniere.first..maxOf(derniere.last, plage.last)
            } else {
                plages += plage
            }
        }
        val debut = plages.first().first
        val fin = plages.last().last
        val sb = StringBuilder()
        var curseur = debut
        for (plage in plages) {
            sb.append(texte.substring(curseur, plage.first))
            curseur = plage.last
        }
        sb.append(texte.substring(curseur, fin))
        return EditionAttributsXmlResultat(debut, fin, sb.toString())
    }

    /**
     * L'AJOUT à la position conventionnelle : après le dernier attribut
     * du MÊME SEAU ([ordreAttribut]), sinon AVANT le premier attribut de
     * seau supérieur (id d'abord — miroir la convention) — propre ligne à
     * l'indentation de l'ancre en balise multi-lignes, en ligne sinon.
     */
    private fun ajouterAttribut(
        texte: String,
        localise: ElementLocalise,
        nom: String,
        valeur: String,
        attrs: List<AttributSource>,
    ): EditionAttributsXmlResultat? {
        val edits = mutableListOf(insererAttributPartielle(texte, localise, nom, valeur, attrs))
        val xmlns = xmlnsManquant(texte, nom)
        if (xmlns != null) edits += xmlns
        return composerEdition(texte, edits)
    }

    /**
     * Le CŒUR d'insertion d'un attribut (sans composition xmlns — PE8b
     * compose plusieurs attributs lui-même) : à la position
     * conventionnelle ([ordreAttribut]), propre ligne en balise
     * multi-lignes, en ligne sinon.
     */
    private fun insererAttributPartielle(
        texte: String,
        localise: ElementLocalise,
        nom: String,
        valeur: String,
        attrs: List<AttributSource>,
    ): EditionPartielle {
        val injection = "$nom=\"$valeur\""
        val seau = ordreAttribut(nom)
        val ancre = attrs.lastOrNull { ordreAttribut(it.nom) <= seau }
        val multiLignes = texte.substring(localise.debut, localise.chevron).contains('\n')
        return when {
            ancre != null -> {
                // Après l'ancre : propre ligne en multi-lignes (miroir
                // [ecrireAttribut]), en ligne sinon.
                EditionPartielle(
                    ancre.fin,
                    ancre.fin,
                    if (multiLignes) {
                        "\n${indentationDeLigne(texte, ancre.debut)}$injection"
                    } else {
                        " $injection"
                    },
                )
            }

            attrs.isEmpty() -> {
                val position = positionInsertion(texte, localise)
                EditionPartielle(position, position, texteInsertionSimple(texte, position, injection))
            }

            else -> {
                // Aucun attribut de seau inférieur (id sans id…) : AVANT
                // le premier attribut — propre ligne en multi-lignes.
                EditionPartielle(
                    attrs.first().debut,
                    attrs.first().debut,
                    if (multiLignes) {
                        "$injection\n${indentationDeLigne(texte, attrs.first().debut)}"
                    } else {
                        "$injection "
                    },
                )
            }
        }
    }

    /** La position d'insertion sans ancre (avant le `/` auto-fermant, sinon le chevron). */
    private fun positionInsertion(
        texte: String,
        localise: ElementLocalise,
    ): Int {
        val slash = texte.lastIndexOf('/', (localise.chevron - 1).coerceAtLeast(localise.debut))
        val fermetureAuto =
            slash > localise.debut && texte.substring(slash + 1, localise.chevron).all { it == ' ' }
        return if (fermetureAuto) slash else localise.chevron
    }

    /** Le texte d'insertion en ligne (espace gardé propre) à [position]. */
    private fun texteInsertionSimple(
        texte: String,
        position: Int,
        injection: String,
    ): String =
        if (position > 0 && texte[position - 1] != ' ' && texte[position - 1] != '\n') {
            " $injection"
        } else {
            injection
        }

    /**
     * Le `xmlns` MANQUANT pour le préfixe de [nom] (app/tools uniquement —
     * miroir `fusionRacineAutoFermante`), posé sur la RACINE du document.
     * `null` = rien à faire.
     */
    private fun xmlnsManquant(
        texte: String,
        nom: String,
    ): EditionPartielle? {
        val prefixe = nom.substringBefore(':', "")
        val uri =
            when (prefixe) {
                "app" -> "http://schemas.android.com/apk/res-auto"
                "tools" -> "http://schemas.android.com/tools"
                else -> return null
            }
        if (texte.contains("xmlns:$prefixe")) return null
        val elements =
            try {
                AligneurElementsXml.aligner(texte, resolveur = null)
            } catch (_: Exception) {
                return null
            }
        val racine = elements.firstOrNull() ?: return null
        val chevron = chevronOuvrant(texte, racine.debut) ?: return null
        val declaration = "xmlns:$prefixe=\"$uri\""
        val avantChevron = texte.substring(racine.debut, chevron)
        return if (avantChevron.endsWith(" ") || avantChevron.endsWith("\n")) {
            EditionPartielle(chevron, chevron, declaration)
        } else {
            EditionPartielle(chevron, chevron, " $declaration")
        }
    }

    /** Une édition partielle (plage + remplacement) à composer. */
    private class EditionPartielle(
        val debut: Int,
        val fin: Int,
        val remplacement: String,
    )

    /**
     * COMPOSE des éditions partielles non chevauchantes en UNE seule
     * (plage englobante — miroir [InsertionXml.composerEdition]) : le
     * texte entre les plages reste intact.
     */
    private fun composerEdition(
        texte: String,
        edits: List<EditionPartielle>,
    ): EditionAttributsXmlResultat? {
        if (edits.isEmpty()) return null
        val triees = edits.sortedBy { it.debut }
        for (i in 1 until triees.size) {
            if (triees[i].debut < triees[i - 1].fin) return null
        }
        val debut = triees.first().debut
        val fin = triees.last().fin
        val sb = StringBuilder()
        var curseur = debut
        for (edit in triees) {
            sb.append(texte.substring(curseur, edit.debut))
            sb.append(edit.remplacement)
            curseur = edit.fin
        }
        sb.append(texte.substring(curseur, fin))
        return EditionAttributsXmlResultat(debut, fin, sb.toString())
    }

    /**
     * ÉCLATE un raccourci couvrant en attributs explicites (miroir § 4.3
     * du prompt PE7e) : le côté édité prend [valeur], les autres côtés
     * conservent la valeur du raccourci — la MÊME ÉDITION remplace le
     * raccourci (propre ligne → les côtés s'empilent à son indentation,
     * en ligne → séparés d'espaces).
     */
    private fun eclaterRaccourci(
        texte: String,
        raccourci: AttributSource,
        nomCible: String,
        valeur: String,
    ): EditionAttributsXmlResultat? {
        val cotesRaccourci = cotesCouvertes(raccourci.nom, baseDe(raccourci.nom))
        if (cotesRaccourci == null) return null
        val base = baseDe(raccourci.nom)
        val nouveaux =
            cotesRaccourci.map { cote ->
                val nom = nomCote(base, cote)
                val v = if (nom == nomCible) valeur else raccourci.valeur
                "$nom=\"$v\""
            }
        val debutLigne = texte.lastIndexOf('\n', (raccourci.debut - 1).coerceAtLeast(0)) + 1
        val seulSurLigne =
            texte.substring(debutLigne, raccourci.debut).all { it == ' ' || it == '\t' } &&
                texte.substring(raccourci.fin, finDeLigne(texte, raccourci.fin)).all { it == ' ' || it == '\t' }
        val remplacement =
            if (seulSurLigne) {
                val indent = indentationDeLigne(texte, raccourci.debut)
                nouveaux.joinToString("\n$indent")
            } else {
                nouveaux.joinToString(" ")
            }
        return EditionAttributsXmlResultat(raccourci.debut, raccourci.fin, remplacement)
    }

    /** La BASE d'un nom de côté (`android:paddingStart` → `android:padding`). */
    private fun baseDe(nomRaccourci: String): String =
        when {
            nomRaccourci.endsWith("Horizontal") || nomRaccourci.endsWith("Vertical") -> {
                nomRaccourci.dropLast("Horizontal".length)
            }

            nomRaccourci.endsWith("Start") || nomRaccourci.endsWith("End") -> {
                nomRaccourci.dropLast("Start".length)
            }

            nomRaccourci.endsWith("Left") || nomRaccourci.endsWith("Right") -> {
                nomRaccourci.dropLast("Left".length)
            }

            nomRaccourci.endsWith("Top") || nomRaccourci.endsWith("Bottom") -> {
                nomRaccourci.dropLast("Top".length)
            }

            else -> {
                nomRaccourci
            }
        }

    /** Les côtés COUVERTS par un raccourci (H : début/fin, V : haut/bas, plein : les 4). */
    private fun cotesCouvertes(
        nomRaccourci: String,
        base: String,
    ): List<Cote>? =
        when (nomRaccourci) {
            base + "Horizontal" -> listOf(Cote.DEBUT, Cote.FIN)
            base + "Vertical" -> listOf(Cote.HAUT, Cote.BAS)
            base -> listOf(Cote.HAUT, Cote.FIN, Cote.BAS, Cote.DEBUT)
            else -> null
        }

    /** La fin de la ligne contenant [offset] (excluant le retour). */
    private fun finDeLigne(
        texte: String,
        offset: Int,
    ): Int = texte.indexOf('\n', offset).let { if (it < 0) texte.length else it }

    /** L'indentation de la ligne contenant [offset]. */
    private fun indentationDeLigne(
        texte: String,
        offset: Int,
    ): String {
        val debutLigne = texte.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
        return texte.substring(debutLigne, offset).takeWhile { it == ' ' || it == '\t' }
    }

    // ── Duplication et suppression ───────────────────────────────────────

    /**
     * DUPLIQUE la vue au span (miroir `dupSel` : clône renuméroté inséré
     * à la suite) : l'élément doit POSSEDER sa ligne (préfixe d'espaces
     * seul — un document compact se refuse, jamais de copie déplacée) ;
     * la copie reprend l'indentation de l'original, ses identifiants
     * renumérotés ([InsertionXml.renumeroter]). L'ÉDITION insère
     * `"\n" + indent + copie` à la fin du bloc : le retour à la ligne
     * QUI SUIT l'original reste partagé.
     */
    public fun dupliquer(
        texte: String,
        span: InsertionXml.SpanParent,
    ): EditionAttributsXmlResultat? {
        val localise = localiser(texte, span) ?: return null
        val indent = indentationPropre(texte, localise.debut) ?: return null
        val original = texte.substring(localise.debut, localise.fin)
        val copie = InsertionXml.renumeroter(original, texte)
        return EditionAttributsXmlResultat(localise.fin, localise.fin, "\n$indent$copie")
    }

    /**
     * SUPPRIME la vue au span (miroir `delSel`) : l'élément possédant sa
     * ligne, la ligne ENTIÈRE disparaît avec son retour ; en document
     * compact, le bloc seul part. La RACINE se refuse au contrôleur
     * (miroir `!f.p` de la maquette — ici la garde est le span sans
     * parent connu).
     */
    public fun supprimer(
        texte: String,
        span: InsertionXml.SpanParent,
    ): EditionAttributsXmlResultat? {
        val localise = localiser(texte, span) ?: return null
        val debutLigne = texte.lastIndexOf('\n', (localise.debut - 1).coerceAtLeast(0)) + 1
        val prefixe = texte.substring(debutLigne, localise.debut)
        if (prefixe.isNotEmpty() && !prefixe.all { it == ' ' || it == '\t' }) {
            // Compact : le bloc seul part, la ligne reste.
            return EditionAttributsXmlResultat(localise.debut, localise.fin, "")
        }
        var finSuppression = localise.fin
        if (finSuppression < texte.length && texte[finSuppression] == '\n') finSuppression += 1
        return EditionAttributsXmlResultat(debutLigne, finSuppression, "")
    }

    // ── Résultat ─────────────────────────────────────────────────────────

    /**
     * UNE édition atomique (miroir [InsertionXml.EditionXml] — même
     * contrat, type distinct pour la lisibilité des signatures PE7d).
     */
    public data class EditionAttributsXmlResultat(
        val debut: Int,
        val fin: Int,
        val remplacement: String,
    ) {
        /** L'édition équivalente du moteur d'insertion (réutilisation du format unique). */
        public fun versEditionXml(): InsertionXml.EditionXml = InsertionXml.EditionXml(debut, fin, remplacement)
    }

    // ── Détails de la localisation (purs, privés) ────────────────────────

    /** L'élément du texte VIVANT dont le span ÉGALISE le span rendu. */
    private fun localiser(
        texte: String,
        span: InsertionXml.SpanParent,
    ): ElementLocalise? {
        if (texte.isEmpty()) return null
        if (span.debut < 0 || span.fin <= span.debut || span.fin > texte.length) return null
        val elements =
            try {
                AligneurElementsXml.aligner(texte, resolveur = null)
            } catch (_: Exception) {
                return null
            }
        val element =
            elements.lastOrNull { it.debut == span.debut && it.fin == span.fin } ?: return null
        if (element.debut < 0 || element.fin <= element.debut) return null
        val chevron = chevronOuvrant(texte, element.debut) ?: return null
        return ElementLocalise(debut = element.debut, fin = element.fin, chevron = chevron)
    }

    /** La balise OUVRANTE de l'élément au span (lecture directe, sans marche). */
    private fun baliseOuvrante(
        texte: String,
        span: InsertionXml.SpanParent,
    ): String? {
        val localise = localiser(texte, span) ?: return null
        return texte.substring(localise.debut, localise.chevron + 1)
    }

    /** L'élément localisé : span + chevron de la balise ouvrante. */
    private class ElementLocalise(
        val debut: Int,
        val fin: Int,
        val chevron: Int,
    )

    /** Regex d'un attribut par nom — la valeur (groupe 1) est la plage de remplacement. */
    private fun regexAttribut(nom: String): Regex = Regex("""\b${Regex.escape(nom)}\s*=\s*"([^"]*)"""")

    /** L'attribution complète à insérer (`nom="valeur"`). */
    private fun attributInjection(
        nom: String,
        valeur: String,
    ): String = "$nom=\"${echapper(valeur)}\""

    /**
     * Échappement XML des littéraux posés en valeur d'attribut : entités,
     * retour saisi → `\n` littéral, `@`/`?` INITIALS échappés (ils
     * deviendraient une référence — miroir § 4.2 du prompt PE7e).
     */
    private fun echapper(valeur: String): String {
        var echappee =
            valeur
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "\\n")
        if (echappee.startsWith("@") || echappee.startsWith("?")) {
            echappee = "\\" + echappee
        }
        return echappee
    }

    /** L'offset du chevron `>` fermant la balise ouvrante (état guillemets). */
    private fun chevronOuvrant(
        texte: String,
        debut: Int,
    ): Int? {
        var i = debut
        var guillemet: Char? = null
        while (i < texte.length) {
            val c = texte[i]
            val guillemetCourant = guillemet
            when {
                guillemetCourant != null && c == guillemetCourant -> guillemet = null
                guillemetCourant == null && (c == '"' || c == '\'') -> guillemet = c
                guillemetCourant == null && c == '>' -> return i
            }
            i += 1
        }
        return null
    }

    /** L'indentation du dernier attribut d'une balise multi-lignes (repli 4 espaces). */
    private fun indentationDernierAttribut(balise: String): String {
        val chevron = balise.length - 1
        val debutLigne = balise.lastIndexOf('\n', chevron - 1) + 1
        val prefixe = balise.substring(debutLigne, chevron)
        val indent = prefixe.takeWhile { it == ' ' || it == '\t' }
        return indent.ifEmpty { PAS_INDENTATION }
    }

    /**
     * L'indentation PROPRE de l'élément : le préfixe de SA ligne quand il
     * n'a que des espaces/tabulations — `null` sinon (document compact :
     * la duplication se refuse, X4).
     */
    private fun indentationPropre(
        texte: String,
        offset: Int,
    ): String? {
        val debutLigne = texte.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
        val prefixe = texte.substring(debutLigne, offset)
        return if (prefixe.all { it == ' ' || it == '\t' }) prefixe else null
    }
}
