package jo.codeide.preview.xml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * LECTEUR D'ATTRIBUTS DU DOCUMENT XML (PE12b — `docs/PREVIEW_PARENTS.md`
 * § 4.1) : la moitié TEXTE des relations et des problèmes de familles —
 * contrairement au rendu ([InfoVueRendu]), ce que lit CET objet reste
 * disponible quand le rendu ÉCHOUE (spec § 9 : les problèmes de famille
 * se calculent sur le texte, dernier rendu valide ou pas).
 *
 * Pour chaque ÉLÉMENT producteur de vue, dans l'ordre du document
 * (pré-ordre, comme l'inflation) : balise, identifiant, paramètres de
 * layout, span source, indice du parent. Mêmes sémantiques d'éléments
 * que [AligneurElementsXml] (miroir de l'inflater, vérifiées sur son
 * source) :
 *  - `<merge>` : transparent — aucun nœud, ses enfants se rattachent au
 *    parent du merge ;
 *  - `<include>` : AUCUN nœud — les paramètres du fichier inclus vivent
 *    dans un AUTRE texte, non lu ici (limitation assumée : un cycle ou
 *    un orphelin DANS un include n'est pas vu par les calculs de texte) ;
 *  - `<requestFocus>`/`<blink>` : aucun nœud, sous-arbre ignoré ;
 *  - tout autre élément : UN nœud.
 *
 * ATTRIBUTS : uniquement les paramètres de layout (nom local en
 * `layout_*`), dans les namespaces `android` OU `app` — les CLÉS sont
 * canoniquement qualifiées (`android:layout_below`,
 * `app:layout_anchor`), QUEL QUE SOIT le préfixe du document : deux
 * fichiers qui déclarent `xmlns:a="…android"` et `android:` portent la
 * MÊME sémantique, la clé est le namespace, pas l'orthographe du
 * préfixe. Tout autre attribut (`android:text`, `tools:…`, `app:title`)
 * reste à [EditionAttributsXml.lire], qui lit par span.
 *
 * TOLÉRANT aux XML incomplets (spec § 4.1) : un document interrompu en
 * cours de frappe ne lève JAMAIS d'exception — on fige le préfixe déjà
 * lu ; l'élément dont la fermeture manque garde [Noeud.fin] à -1.
 *
 * Spans : la MÊME sémantique épinglée que l'aligneur (KXmlParser
 * configuré comme LayoutLib) — au START_TAG et à l'END_TAG, la position
 * (ligne, colonne) est juste APRÈS le `>` du tag ; le début du span se
 * retrouve par recherche ARRIÈRE du `<` (interdit dans les valeurs
 * d'attribut : le premier `<` en remontant est nécessairement
 * l'ouverture du tag).
 *
 * FONCTIONS PURES, SANS ANDROID : `XmlPullParser` sur le texte, comme
 * [AligneurElementsXml].
 */
public object ArbreAttributsXml {
    /**
     * Un élément producteur de vue, lu dans le texte.
     *
     * @property balise nom local de la balise (ex. « RelativeLayout »).
     * @property id NOM de l'identifiant (« bio », sans `@id/`), ou null
     *           si absent ou de forme exotique (pas `@id/x` ni `@+id/x`).
     * @property attributs paramètres de layout SEULEMENT, clés
     *           canoniquement qualifiées, valeurs brutes, dans l'ordre
     *           du document.
     * @property debut offset du `<` ouvrant dans le texte.
     * @property fin offset juste après le `>` fermant ; -1 si l'élément
     *           n'est jamais fermé (document interrompu).
     * @property indiceParent indice du parent dans la liste aplatie
     *           retournée par [lire] ; -1 = premier niveau (racine, ou
     *           enfant direct d'un `<merge>` racine).
     * @property enfants sous-arbre, dans l'ordre du document.
     *
     * Exemption detekt ciblée (règle 16) : LongParameterList — un
     * CHAMP = un paramètre, le nœud est le miroir de l'élément XML du
     * texte (même patron qu'[InfoVueRendu] sur la frontière AIDL).
     */
    @Suppress("LongParameterList")
    public class Noeud(
        public val balise: String,
        public val id: String?,
        public val attributs: Map<String, String>,
        public val debut: Int,
        public val fin: Int,
        public val indiceParent: Int,
        public val enfants: List<Noeud>,
    )

    /**
     * Le NOM d'une référence d'identifiant (« @id/bio » ou « @+id/bio »
     * → « bio »), ou null si la valeur n'est pas une référence simple —
     * même règle que la maquette (`refId`) : `@android:id/…` et
     * `@string/…` ne sont PAS des identifiants de vues.
     */
    internal fun nomId(reference: String?): String? =
        reference?.let { REGEX_REFERENCE_ID.find(it)?.groupValues?.get(1) }

    /**
     * Lit le document [texte] : TOUS les éléments producteurs, aplatis
     * en PRÉ-ORDRE (l'ordre du document, parent avant enfants). La
     * liste et les liens [Noeud.indiceParent] / [Noeud.enfants]
     * décrivent le même arbre ; un document vide, étranger au XML ou
     * interrompu avant le premier élément donne une liste VIDE, jamais
     * une exception.
     */
    public fun lire(texte: String): List<Noeud> {
        val parseur = parseurSur(texte) ?: return emptyList()
        val marche = Marche(texte, LignesTexte(texte))
        return try {
            var evenement = parseur.eventType
            while (evenement != XmlPullParser.START_TAG && evenement != XmlPullParser.END_DOCUMENT) {
                evenement = parseur.next()
            }
            if (evenement == XmlPullParser.START_TAG) {
                traiterElement(parseur, marche)
            }
            figer(marche.racines)
        } catch (_: Exception) {
            // Document tronqué ou mal formé (frappe en cours) : on fige
            // le préfixe déjà lu — tolérance § 4.1, jamais d'exception
            // au lecteur.
            figer(marche.racines)
        }
    }

    /** État de la marche : texte, lignes, pile d'ancêtres, racines. */
    private class Marche(val texte: String, val lignes: LignesTexte) {
        /** Nœuds de premier niveau (racine, ou enfants d'un merge racine). */
        val racines = mutableListOf<NoeudMutable>()

        /** Ancêtres ouverts (le dernier est le parent courant). */
        val pile = mutableListOf<NoeudMutable>()

        /** Indice pré-ordre du PROCHAIN nœud créé. */
        var compteur: Int = 0
    }

    /**
     * Nœud en construction (gelé en [Noeud] à la fin de la marche) —
     * [fin] reste à -1 tant que l'END_TAG n'est pas rencontré.
     */
    private class NoeudMutable(
        val balise: String,
        val id: String?,
        val attributs: Map<String, String>,
        val debut: Int,
        val indice: Int,
        val indiceParent: Int,
    ) {
        var fin: Int = SANS_SPAN
        val enfants = mutableListOf<NoeudMutable>()
    }

    /** Débuts de ligne du texte (offset du premier caractère de chaque ligne). */
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

    /**
     * Traite l'élément sur lequel le parseur est positionné (START_TAG) ;
     * au retour le parseur est positionné sur SON END_TAG.
     */
    private fun traiterElement(parseur: XmlPullParser, marche: Marche) {
        val balise = parseur.name ?: ""
        when (balise) {
            // L'include ne produit RIEN dans CE texte (le fichier inclus
            // se lit ailleurs) ; ses enfants éventuels sont ignorés de
            // la même façon par l'inflater.
            "include", "requestFocus", "blink" -> {
                sauterEnfants(parseur)
                return
            }

            "merge" -> {
                // Le merge ne produit rien ; ses enfants suivent, en place.
                traiterEnfants(parseur, marche)
                return
            }
        }
        val apresChevronOuvrant = marche.lignes.offset(parseur.lineNumber, parseur.columnNumber)
        val parent = marche.pile.lastOrNull()
        val noeud =
            NoeudMutable(
                balise = balise,
                id = idDe(parseur),
                attributs = attributsDe(parseur),
                debut =
                marche.texte.lastIndexOf(
                    '<',
                    (apresChevronOuvrant - 1).coerceAtMost(marche.texte.length - 1),
                ),
                indice = marche.compteur,
                indiceParent = parent?.indice ?: SANS_PARENT,
            )
        marche.compteur += 1
        if (parent == null) {
            marche.racines.add(noeud)
        } else {
            parent.enfants.add(noeud)
        }
        marche.pile.add(noeud)
        traiterEnfants(parseur, marche)
        marche.pile.removeAt(marche.pile.lastIndex)
        // Le parseur est sur l'END_TAG de CET élément : position juste
        // après son `>` (une exception pendant les enfants interrompt
        // la marche AVANT ici — fin reste -1, l'élément reste lu).
        noeud.fin = marche.lignes.offset(parseur.lineNumber, parseur.columnNumber)
    }

    /** Enfants de l'élément courant (parseur juste après son START_TAG). */
    private fun traiterEnfants(parseur: XmlPullParser, marche: Marche) {
        var evenement = parseur.next()
        while (evenement != XmlPullParser.END_TAG) {
            if (evenement == XmlPullParser.START_TAG) {
                traiterElement(parseur, marche)
            }
            evenement = parseur.next()
        }
    }

    /** Consomme le sous-arbre sans produire de nœuds (retour : END_TAG). */
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

    /** L'identifiant `android:id` de l'élément, sous forme de NOM (ou null). */
    private fun idDe(parseur: XmlPullParser): String? = nomId(parseur.getAttributeValue(NAMESPACE_ANDROID, "id"))

    /**
     * Les paramètres de LAYOUT de l'élément : nom local en `layout_*`
     * dans le namespace `android` ou `app`, clé canoniquement qualifiée
     * (le NAMESPACE fait la clé, pas l'orthographe du préfixe), dans
     * l'ordre du document. Tout autre attribut (`android:text`,
     * `tools:…`, `app:title`) reste à [EditionAttributsXml.lire], qui
     * lit par span.
     */
    private fun attributsDe(parseur: XmlPullParser): Map<String, String> {
        val attributs = LinkedHashMap<String, String>()
        for (indice in 0 until parseur.attributeCount) {
            val nomLocal = parseur.getAttributeName(indice)
            if (nomLocal != null && nomLocal.startsWith(PREFIXE_LAYOUT)) {
                val prefixe =
                    when (parseur.getAttributeNamespace(indice)) {
                        NAMESPACE_ANDROID -> "android:"
                        NAMESPACE_APPLICATION -> "app:"
                        else -> null
                    }
                val valeur = parseur.getAttributeValue(indice)
                if (prefixe != null && valeur != null) {
                    attributs["$prefixe$nomLocal"] = valeur
                }
            }
        }
        return attributs
    }

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

    /** Gel récursif d'un nœud mutable (les enfants d'abord). */
    private fun geler(noeud: NoeudMutable): Noeud = Noeud(
        balise = noeud.balise,
        id = noeud.id,
        attributs = noeud.attributs,
        debut = noeud.debut,
        fin = noeud.fin,
        indiceParent = noeud.indiceParent,
        enfants = noeud.enfants.map(::geler),
    )

    /** Racines gelées, aplaties en PRÉ-ORDRE — l'ordre du document. */
    private fun figer(racines: List<NoeudMutable>): List<Noeud> {
        val aplaties = mutableListOf<Noeud>()

        fun ajouter(noeud: Noeud) {
            aplaties.add(noeud)
            noeud.enfants.forEach(::ajouter)
        }
        racines.map(::geler).forEach(::ajouter)
        return aplaties
    }

    /** Référence d'identifiant simple : `@id/nom` ou `@+id/nom` (groupe 1 = nom). */
    private val REGEX_REFERENCE_ID: Regex = Regex("""^@\+?id/(\w+)$""")

    /** Namespace `android` (résolution d'attributs, même convention que LayoutLib). */
    private const val NAMESPACE_ANDROID: String = "http://schemas.android.com/apk/res/android"

    /** Namespace `app` (attributs de bibliothèques, `layout_anchor`, `layout_behavior`…). */
    private const val NAMESPACE_APPLICATION: String = "http://schemas.android.com/apk/res/auto"

    /** Préfixe commun des paramètres de layout (nom local). */
    private const val PREFIXE_LAYOUT: String = "layout_"

    /** Indice d'un nœud sans parent dans la liste aplatie. */
    private const val SANS_PARENT: Int = -1

    /** Span inconnu (élément jamais fermé). */
    private const val SANS_SPAN: Int = -1
}
