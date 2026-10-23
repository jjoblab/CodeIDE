package jo.codeide.preview.xml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlin.math.min

/**
 * Alignement éléments XML ↔ vues inflatées (PE3, PREVIEW.md §3.1 P6 :
 * « clic sur une vue du bitmap → sélection dans l'éditeur ») — la moitié
 * SOURCE de la sélection bidirectionnelle.
 *
 * Principe : l'inflater LayoutLib crée EXACTEMENT UNE vue par élément
 * « producteur » du document, en pré-ordre (l'ordre du document), avec
 * les sémantiques suivantes (mirroir exact de `BridgeInflater`, vérifiées
 * sur son source) :
 *  - `<merge>` : aucun élément, ses enfants sont attachés au parent —
 *    l'ordre pré-order du document est préservé ;
 *  - `<include>` : l'élément lui-même ne produit rien, le FICHIER référencé
 *    est inflaté à sa place (vues du fichier inclus dans la séquence) —
 *    l'alignement RÉSOUT le même layout et marche SON texte (éléments
 *    marqués `externe` : offsets dans un AUTRE fichier, donc non
 *    sélectionnables dans le document édité — honnête, jamais un faux
 *    span) ;
 *  - `<requestFocus>`/`<blink>` : aucun élément, sous-arbre ignoré ;
 *  - tout autre élément : UNE vue.
 *
 * Si un `<include>` ne se laisse pas résoudre par l'aligneur (repli natif
 * sans `res/` du projet — l'inflater résout alors via `R.layout.*`, que
 * l'aligneur ne peut pas marcher), la séquence n'est plus garantie : tous
 * les éléments SUIVANTS perdent leur span (non sélectionnables) — la
 * sélection ne devine JAMAIS (philosophie X4).
 *
 * Spans : le parseur (KXmlParser, même configuration que LayoutLib)
 * rapporte au START_TAG et à l'END_TAG une position (ligne, colonne)
 * située JUSTE APRÈS le `>` du tag — sémantique ÉPINGLÉE par tests sur
 * vraies sorties. Le début du span se retrouve par recherche ARRIÈRE du
 * `<` (le caractère `<` est interdit dans les valeurs d'attribut XML :
 * le premier `<` en remontant est nécessairement l'ouverture du tag).
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — chaque ISSUE de
 * [traiterElement] (include, merge, balise sautée, élément producteur)
 * est une sémantique DISTINCTE de l'inflater, un retour par branche ;
 * TooManyFunctions — les aides ligne ↔ ligne de la feuille (PE6d) vivent
 * au même endroit que la sémantique des spans.
 */
@Suppress("ReturnCount", "TooManyFunctions")
internal object AligneurElementsXml {
    /**
     * Un élément producteur de vue : span source (offsets dans le TEXTE
     * porté — le document principal ou un fichier inclus).
     *
     * @property nomBalise nom local de la balise (ex. « TextView »).
     * @property idNom valeur de `android:id` si présente (ex. « @+id/titre »).
     * @property debut offset du `<` ouvrant (-1 = non sélectionnable).
     * @property fin offset juste après le `>` fermant (-1 = non sélectionnable).
     * @property externe élément d'un fichier `<include>` (offsets dans un
     *           autre document — non sélectionnable dans l'éditeur courant).
     */
    internal class ElementSource(
        val nomBalise: String,
        val idNom: String?,
        var debut: Int,
        var fin: Int,
        val externe: Boolean,
        /** Le VIEWCOOKIE du nœud (v0.173.0 — PE14 S8 : « site:ordinal », le
         * MÊME schéma que `BridgeInflater` — un appel de `parseElement`
         * par START_TAG, un ordinal consommé ; la chaîne des `<include>`
         * forme le site). L'appariement vue ↔ élément se fait par
         * IDENTITÉ dans `RenduLayoutLib.collecterVues` : un écart de
         * COMPTE (include résolu nativement, ViewStub) ne désarme plus
         * la suite du document. */
        val cookie: String = "",
    )

    /** Résolution d'un `<include layout="@layout/x">` — couture injectable. */
    internal fun interface ResolveurLayout {
        /** Contenu XML du layout référencé, ou null si introuvable. */
        fun layout(reference: String): String?
    }

    /** Débuts de ligne du texte courant (offset du premier caractère de chaque ligne). */
    private class LignesTexte(private val texte: String) {
        private val debuts: IntArray

        init {
            val positions = mutableListOf(0)
            for (indice in texte.indices) {
                if (texte[indice] == '\n') positions.add(indice + 1)
            }
            debuts = positions.toIntArray()
        }

        /** Offset (0-based) de la position (ligne 1-based, colonne 1-based). */
        fun offset(ligne: Int, colonne: Int): Int {
            val debutLigne = debuts[(ligne - 1).coerceIn(0, debuts.size - 1)]
            return debutLigne + (colonne - 1).coerceAtLeast(0)
        }
    }

    /** État de la marche d'UN document (texte + table de lignes + désalignement). */
    private class Marche(val texte: String, val lignes: LignesTexte)

    /** Résultat aplati en pré-ordre (parent avant enfants). */
    private val elements = mutableListOf<ElementSource>()

    /** Profondeur d'imbrication des includes (garde-fou anti-cycle). */
    private var profondeurInclude = 0

    /**
     * Le SITE cookie COURANT (v0.173.0 — "" = document principal ; la
     * chaîne des ordinaux d'{@code <include>} pour un document inclus).
     */
    private var siteCookie = ""

    /**
     * L'ORDINAL cookie courant du document en marche (v0.173.0 — le rang
     * du START_TAG, MÊME comptage que `BridgeInflater.parseElement`).
     */
    private var ordinalNoeud = 0

    /**
     * Aligne les éléments producteurs de vues du document [texte], en
     * pré-ordre (l'ordre exact de l'inflation par `BridgeInflater`).
     *
     * @param texte le document ORIGINAL de l'éditeur (spans dans CE texte).
     * @param resolveur résolution des `<include>` (null = tout include
     *        déclenche le désalignement).
     */
    internal fun aligner(texte: String, resolveur: ResolveurLayout?): List<ElementSource> {
        profondeurInclude = 0
        elements.clear()
        siteCookie = ""
        ordinalNoeud = 0
        marcherDocument(texte, externe = false, resolveur = resolveur)
        return elements.toList()
    }

    /** Marche un document entier (racine comprise) — pré-ordre complet. */
    private fun marcherDocument(texte: String, externe: Boolean, resolveur: ResolveurLayout?) {
        val parseur = parseurSur(texte) ?: return
        // Avance jusqu'au premier START_TAG (saute prolog/doctype).
        var evenement = parseur.eventType
        while (evenement != XmlPullParser.START_TAG && evenement != XmlPullParser.END_DOCUMENT) {
            evenement = parseur.next()
        }
        if (evenement == XmlPullParser.START_TAG) {
            val marche = Marche(texte, LignesTexte(texte))
            traiterElement(parseur, marche, externe, resolveur)
        }
    }

    /**
     * Traite l'élément sur lequel le parseur est positionné (START_TAG) ;
     * au retour le parseur est positionné sur SON END_TAG.
     *
     * v0.173.0 — chaque appel consomme UN ORDINAL cookie (le rang du
     * START_TAG — MÊME comptage que `BridgeInflater.parseElement`, y
     * compris pour les balises qui ne produisent RIEN : include, merge,
     * requestFocus, blink, fragment).
     *
     * @return l'élément source (null si l'élément ne produit pas de vue).
     */
    private fun traiterElement(
        parseur: XmlPullParser,
        marche: Marche,
        externe: Boolean,
        resolveur: ResolveurLayout?,
    ): ElementSource? {
        val ordinal = ordinalNoeud++
        val balise = parseur.name ?: ""
        val apresChevronOuvrant = marche.lignes.offset(parseur.lineNumber, parseur.columnNumber)
        val debut = dernierChevronAvant(marche.texte, apresChevronOuvrant)

        when (balise) {
            "include" -> {
                traiterInclude(parseur, resolveur, ordinal)
                return null
            }

            "merge" -> {
                // Le merge ne produit rien ; ses enfants suivent, en place.
                traiterEnfants(parseur, marche, externe, resolveur)
                return null
            }

            "requestFocus", "blink" -> {
                sauterEnfants(parseur)
                return null
            }

            // v0.173.0 — parité `parseFragmentPlaceholder` : un
            // `<fragment>` produit UNE vue de remplacement et son
            // sous-arbre est IGNORÉ par l'inflater (skipElement) — les
            // enfants ne comptaient pas à l'inflation, ils ne comptent
            // plus ici (l'ordinal du fragment, lui, est bien consommé).
            "fragment" -> {
                sauterEnfants(parseur)
                val element =
                    ajouterElement(balise, parseur, debut, externe, ordinal)
                return element
            }
        }

        // v0.173.0 — seul EXTERNE retire le span (offsets dans un AUTRE
        // document) ; le désalignement d'include n'en retire PLUS :
        // l'appariement par COOKIE rend les éléments suivants (leur
        // position d'ordre ne tient plus, leur IDENTITÉ tient).
        val etaitDesaligne = externe
        val element = ajouterElement(balise, parseur, debut, externe, ordinal)
        // Enfants (une balise auto-fermante retombe immédiatement sur son
        // propre END_TAG : la boucle ne fait rien, la position est déjà
        // la bonne).
        traiterEnfants(parseur, marche, externe, resolveur)
        // Le parseur est sur l'END_TAG de CET élément : position juste
        // après son `>`. Un include ÉCHEOUÉ pendant les enfants ne
        // remet PAS en cause le span du parent (sa position de séquence
        // est antérieure à la rupture) — seuls les éléments OUVERTS
        // après la rupture perdent leur span.
        element.fin =
            if (etaitDesaligne) {
                SANS_SPAN
            } else {
                marche.lignes.offset(parseur.lineNumber, parseur.columnNumber)
            }
        return element
    }

    /**
     * Pose l'élément producteur avec SON cookie « site:ordinal »
     * (v0.173.0 — MÊME schéma que `BridgeInflater.poserCookie` : l'ordinal
     * a DÉJÀ été consommé par [traiterElement], miroir du wrapper
     * `parseElement` — la balise qui ne produit rien ne pose RIEN).
     */
    private fun ajouterElement(
        balise: String,
        parseur: XmlPullParser,
        debut: Int,
        externe: Boolean,
        ordinal: Int,
    ): ElementSource {
        val element =
            ElementSource(
                nomBalise = balise,
                idNom = idDe(parseur),
                debut = if (externe) SANS_SPAN else debut,
                fin = SANS_SPAN,
                externe = externe,
                cookie = "$siteCookie:$ordinal",
            )
        // Pré-ordre CONTRACTUEL : le parent précède ses enfants dans la
        // séquence aplatie — c'est l'ordre exact de l'inflation (une vue
        // par élément producteur), l'indice i du tableau correspond à la
        // i-ème vue du ViewInfoCollector.
        elements.add(element)
        return element
    }

    /**
     * `<include layout="@layout/x">` : résout le layout référencé et marche
     * SON texte (éléments externes), puis saute le sous-arbre de l'include
     * (normalement vide — l'inflater l'ignore de la même façon).
     *
     * v0.173.0 — le document inclus vit dans un SITE cookie BRANCHÉ
     * (la chaîne des ordinaux d'include — miroir `siteDe` de
     * `BridgeInflater`) : DEUX inclusions du MÊME fichier produisent des
     * cookies DISTINCTS, et les ordinaux du document inclus REPARTENT de
     * zéro (l'inflater enfant est NEUF — miroir exact).
     */
    private fun traiterInclude(parseur: XmlPullParser, resolveur: ResolveurLayout?, ordinal: Int) {
        val reference = attributLayoutDe(parseur) ?: ""
        val contenu =
            if (profondeurInclude < LIMITE_INCLUDES) {
                resolveur?.layout(reference)
            } else {
                null
            }
        if (contenu != null) {
            profondeurInclude += 1
            val siteSauve = siteCookie
            val ordinalSauve = ordinalNoeud
            siteCookie = siteDe(ordinal)
            ordinalNoeud = 0
            try {
                marcherDocument(contenu, externe = true, resolveur = resolveur)
            } finally {
                siteCookie = siteSauve
                ordinalNoeud = ordinalSauve
            }
            profondeurInclude -= 1
        } else {
            // L'inflater résoudra peut-être nativement (R.layout.*) : des
            // vues sans éléments connus vont apparaître — l'appariement
            // PAR ORDRE sautera (comptes divergents, X4 : jamais de
            // devinette), mais les éléments du document GARDENT leurs
            // spans : le COOKIE les rendra (v0.173.0 — S8, identité).
        }
        sauterEnfants(parseur)
    }

    /**
     * Le SITE d'un include d'ordinal [ordinal] (miroir exact de
     * `BridgeInflater.siteDe`) : la chaîne courante étendue du rang de
     * l'include — vide pour le document principal.
     */
    private fun siteDe(ordinal: Int): String = if (siteCookie.isEmpty()) ordinal.toString() else "$siteCookie>$ordinal"

    /** Enfants de l'élément courant (parseur juste après son START_TAG). */
    private fun traiterEnfants(parseur: XmlPullParser, marche: Marche, externe: Boolean, resolveur: ResolveurLayout?) {
        var evenement = parseur.next()
        while (evenement != XmlPullParser.END_TAG) {
            if (evenement == XmlPullParser.START_TAG) {
                traiterElement(parseur, marche, externe, resolveur)
            }
            evenement = parseur.next()
        }
    }

    /** Consomme le sous-arbre sans produire d'éléments (retour : END_TAG). */
    private fun sauterEnfants(parseur: XmlPullParser) {
        var profondeur = 1
        while (profondeur > 0) {
            when (parseur.next()) {
                XmlPullParser.START_TAG -> profondeur += 1
                XmlPullParser.END_TAG -> profondeur -= 1
                XmlPullParser.END_DOCUMENT -> return
                else -> Unit
            }
        }
    }

    /** Attribut `layout` d'un `<include>` (namespace android ou sans préfixe). */
    private fun attributLayoutDe(parseur: XmlPullParser): String? =
        parseur.getAttributeValue(NAMESPACE_ANDROID, "layout")
            ?: parseur.getAttributeValue(null, "layout")

    /** Attribut `android:id` si présent (libellé de sélection). */
    private fun idDe(parseur: XmlPullParser): String? = parseur.getAttributeValue(NAMESPACE_ANDROID, "id")

    /** Parseur KXmlParser sur le texte, configuré comme LayoutLib ; null si impossible. */
    private fun parseurSur(texte: String): XmlPullParser? = try {
        val fabrique = XmlPullParserFactory.newInstance()
        fabrique.isNamespaceAware = true
        val parseur = fabrique.newPullParser()
        parseur.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parseur.setInput(StringReader(texte))
        parseur
    } catch (_: Exception) {
        null
    }

    /**
     * Recherche ARRIÈRE du `<` ouvrant depuis la position juste après le
     * `>` du START_TAG : le `<` est interdit dans les valeurs d'attribut,
     * le premier rencontré en remontant est l'ouverture du tag.
     */
    private fun dernierChevronAvant(texte: String, apresChevron: Int): Int =
        texte.lastIndexOf('<', (apresChevron - 1).coerceAtMost(texte.length - 1))

    private const val SANS_SPAN: Int = -1

    /** Namespace android (résolution d'attributs, même convention que LayoutLib). */
    private const val NAMESPACE_ANDROID: String = "http://schemas.android.com/apk/res/android"

    /** Profondeur maximale d'imbrication des includes (garde-fou anti-cycle). */
    private const val LIMITE_INCLUDES: Int = 8

    // ── Aides de la feuille inférieure (PE6d, maquette § « Feuille
    //    inférieure : arbre, attributs, problèmes, source » : l'onglet
    //    Source « surligne les lignes d'après les spans » — la conversion
    //    offset ↔ ligne vient ICI, au même endroit que la sémantique des
    //    spans, pour que la feuille et la sélection partagent LA MÊME
    //    définition d'une ligne) ──

    /**
     * Le numéro de ligne (1-based) d'un offset dans [texte] — l'onglet
     * Source de la feuille convertit les spans (offsets) en lignes
     * affichées. Sémantique : `1 + nombre de '\n' avant l'offset` (offset
     * exclu). Offset borné (≤ 0 → ligne 1, au-delà du texte → la ligne
     * qui suivrait la dernière) : jamais d'exception, le surlignage reste
     * affichable.
     */
    public fun numeroLigne(texte: String, offset: Int): Int {
        if (offset <= 0) return 1
        var ligne = 1
        val fin = min(offset, texte.length)
        for (i in 0 until fin) {
            if (texte[i] == '\n') ligne += 1
        }
        return ligne
    }

    /**
     * L'offset du PREMIER caractère de la ligne [ligne] (1-based) — le
     * défilement de l'onglet Source cible le début du span sélectionné.
     * Ligne bornée au document (≤ 1 → 0 ; au-delà → la fin du texte),
     * jamais d'exception.
     */
    public fun debutLigne(texte: String, ligne: Int): Int {
        if (ligne <= 1) return 0
        var numero = 1
        for ((offset, caractere) in texte.withIndex()) {
            if (caractere != '\n') continue
            numero += 1
            if (numero == ligne) return offset + 1
        }
        return texte.length
    }
}
