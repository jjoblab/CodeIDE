package jo.codeide.preview.xml

import kotlin.math.max
import kotlin.math.min

/**
 * Insertion XML de la palette (PE7a, maquette
 * `docs/mockups/apercu-xml.html` § « Catalogue et insertion XML, en
 * fonctions pures ») — FONCTIONS PURES sur le TEXTE du document, sans
 * Android : « Dans l'application, le texte du document reste la seule
 * source : la palette écrit une édition dans la session, et le rendu
 * repart par le chemin normal. »
 *
 * Contrat [inserer] : le TEXTE courant + le span du conteneur cible
 * (spans de l'arbre rendu — [InfoVueRendu.debutElement]/[finElement])
 * + l'index entre frères + l'extrait → UNE édition
 * `(debut, fin, remplacement)` — atomique, un seul geste d'annulation
 * dans l'éditeur. L'édition dérive la position RÉELLE en re-marchant le
 * texte ([AligneurElementsXml.aligner], même parseur que LayoutLib) :
 * un span PÉRIMÉ (texte modifié depuis le rendu, include non résolu,
 * XML malformé) → `null`, JAMAIS d'écriture hasardeuse (philosophie X4).
 *
 * Mécanique reproduite (miroir du comportement maquette) :
 * - INDENTATION du parent (détection du style existant des enfants,
 *   repli parent + 4) ;
 * - balise AUTO-FERMANTE convertie en paire ouvrante/fermante ;
 * - `xmlns:app` ajouté à la RACINE si l'extrait l'utilise (édition
 *   unique — la plage s'étend de la balise racine au point d'insertion) ;
 * - dans un conteneur ConstraintLayout, le contrôleur façonne l'extrait
 *   avec [contraindre] (contraintes `parent` + marges en dp calculées
 *   avec la densité du profil) et pose l'identifiant libre avec
 *   [renumeroter] (miroir `nextId`).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — la surface de
 * dérivation (insertion, renumérotation, façonnage, chevrons, indentation)
 * vit au COMPLET dans ce seul objet : les fonctions portent le contrat
 * pur testé ([InsertionXmlTest]), le format ÉDITION est la sortie unique ;
 * ReturnCount — chaque garde de [inserer] est une issue DISTINCTE du
 * refus X4 (périme, malformation, include, bornes).
 */
@Suppress("TooManyFunctions", "ReturnCount")
public object InsertionXml {
    /**
     * UNE édition de texte atomique — posée dans la session par le port
     * `SourceApercu.remplacer(debut, fin, remplacement)` (une SEULE
     * entrée d'historique : contrat PE7d de la maquette).
     *
     * @property debut début de la plage remplacée (offset inclus).
     * @property fin fin de la plage remplacée (offset exclu).
     * @property remplacement texte posé sur la plage.
     */
    public data class EditionXml(val debut: Int, val fin: Int, val remplacement: String)

    /**
     * Le span source du CONTENEUR cible (spans de l'arbre rendu).
     *
     * @property debut offset du `<` ouvrant du conteneur.
     * @property fin offset après le `>` FERMANT du conteneur.
     */
    public data class SpanParent(val debut: Int, val fin: Int)

    /** Indentation par niveau (convention des layouts, miroir maquette `ind + 4`). */
    private const val PAS_INDENTATION: String = "    "

    /** Attribution `xmlns:app` complète (détection/ajout sur la balise racine). */
    private const val ATTRIBUT_XMLNS_APP: String = "xmlns:app=\"http://schemas.android.com/apk/res-auto\""

    /** Marqueur d'utilisation de `app:` dans un extrait (déclenche le xmlns). */
    private const val MARQUEUR_APP: String = "app:"

    /** Regex des identifiants RÉSERVÉS du document (`@+id/x` et `@id/x`). */
    private val IDS_UTILISES = Regex("""@\+?id/([\w.]+)""")

    /** Regex des identifiants POSÉS par un extrait. */
    private val ID_EXTRAIT = Regex("""@\+id/(\w+)""")

    /**
     * Compose l'édition d'insertion d'un extrait dans un conteneur.
     *
     * @param texte le document courant (état VIVANT de la session).
     * @param spanParent span source du conteneur cible (arbre rendu).
     * @param index position entre frères (0 = premier ; bornée au nombre
     *        d'enfants — miroir `Math.min(index, children.length)`).
     * @param extrait XML à insérer (multi-lignes accepté — chaque ligne
     *        reçoit l'indentation calculée).
     * @return l'édition unique, ou `null` si le texte n'ÉPAULE plus le
     *         span (périme, malformation, include non résolu) — l'écriture
     *         refuse plutôt que de deviner.
     */
    public fun inserer(texte: String, spanParent: SpanParent, index: Int, extrait: String): EditionXml? {
        if (extrait.isBlank() || texte.isEmpty()) return null
        if (spanParent.debut < 0 || spanParent.fin <= spanParent.debut) return null
        // La marche du texte VIVANT (peut être malformé au moment du dépôt :
        // une frappe a pu échapper) — toute exception du parseur est un
        // REFUS (null), jamais d'écriture hasardeuse (philosophie X4).
        val elements =
            try {
                AligneurElementsXml.aligner(texte, resolveur = null)
            } catch (_: Exception) {
                return null
            }
        val parent = elementAuSpan(elements, spanParent) ?: return null
        val enfants = enfantsDirects(elements, parent)

        val indentParent = indentationDe(texte, parent.debut, repli = "") ?: return null
        val indentEnfant = indentEnfantDetecte(texte, enfants) ?: (indentParent + PAS_INDENTATION)
        val extraitIndente = indenter(extrait, indentEnfant)

        // Point d'insertion : avant le frère visé, sinon en fin de
        // conteneur (append) — miroir targetAt/slotRect de la maquette.
        val indexBorne = index.coerceIn(0, enfants.size)
        val finEdition =
            if (indexBorne < enfants.size) {
                avantFrere(texte, enfants[indexBorne], extraitIndente) ?: return null
            } else {
                finConteneurOuPaire(texte, parent, indentParent, extraitIndente) ?: return null
            }
        return composerEdition(texte, elements, finEdition, extrait)
    }

    /**
     * Renumérote l'identifiant de l'extrait au premier suffixe LIBRE
     * (miroir `nextId` : les ids réservés du document — `@+id/` et
     * `@id/` — bloquent). Identifiant libre : la base reste telle quelle.
     */
    public fun renumeroter(extrait: String, texte: String): String {
        val bases =
            ID_EXTRAIT
                .findAll(extrait)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        if (bases.isEmpty()) return extrait
        val utilises = IDS_UTILISES.findAll(texte).map { it.groupValues[1] }.toSet()
        var resultat = extrait
        for (base in bases) {
            if (base !in utilises) continue
            var numero = 1
            var candidat = base + numero
            while (candidat in utilises) {
                numero += 1
                candidat = base + numero
            }
            resultat = resultat.replace("@+id/$base", "@+id/$candidat")
        }
        return resultat
    }

    /**
     * Façonne l'extrait pour un dépôt dans un conteneur ConstraintLayout
     * (maquette : « le point de dépôt donne `layout_constraintTop_toTopOf="parent"`
     * et des marges en dp, calculées avec la densité du profil ») : les
     * marges (dp) viennent de la position du dépôt convertie par le
     * contrôleur. Délègue l'injection à [ajouterAttributs].
     */
    public fun contraindre(extrait: String, margeHautDp: Int, margeStartDp: Int): String {
        val attributs =
            buildList {
                add("app:layout_constraintTop_toTopOf" to "parent")
                add("app:layout_constraintStart_toStartOf" to "parent")
                if (margeHautDp > 0) add("android:layout_marginTop" to "${margeHautDp}dp")
                if (margeStartDp > 0) add("android:layout_marginStart" to "${margeStartDp}dp")
            }
        return ajouterAttributs(extrait, attributs)
    }

    /**
     * Injecte des ATTRIBUTS de position dans la première balise de
     * l'extrait (PE12d — généralisation de [contraindre], miroir
     * `applyPlan` : « le plan ne fait que choisir les attributs de
     * l'extrait avant l'appel », spec § 4.3) : les attributs s'insèrent
     * avant la fermeture de la PREMIÈRE balise, à l'indentation du
     * dernier attribut existant (multi-lignes) ou sur la ligne (balise
     * compacte), DANS L'ORDRE de la liste — les plans posent les MARGES
     * d'abord, puis les règles (convention du fichier d'or S1-S12). Les
     * attributs déjà présents ne sont PAS dupliqués (même nom → valeur
     * REMPLACÉE en place, un dépôt n'écrit jamais deux fois le même
     * attribut).
     */
    public fun ajouterAttributs(extrait: String, attributs: List<Pair<String, String>>): String {
        if (attributs.isEmpty()) return extrait
        val chevron = chevronOuvrant(extrait, 0) ?: return extrait
        val existants = attributsExistants(extrait, chevron)
        val aPoser = attributs.filter { (nom, _) -> existants[nom] == null }
        if (aPoser.isEmpty()) return remplacerValeurs(extrait, attributs, existants)
        val multiline = extrait.indexOf('\n') in 0 until chevron
        val indent = if (multiline) indentationDernierAttribut(extrait, chevron) else ""
        val injection =
            if (multiline) {
                // Le PREMIER attribut porte aussi l'indentation (joinToString
                // n'intercale pas de préfixe initial) — un attribut par ligne.
                "\n$indent" + aPoser.joinToString("\n$indent") { (nom, valeur) -> "$nom=\"$valeur\"" }
            } else {
                aPoser.joinToString(" ") { (nom, valeur) -> "$nom=\"$valeur\"" }
            }
        // Balise auto-fermante : les attributs précèdent le « / » ; paire
        // ouvrante/fermante : ils précèdent le chevron. Multi-lignes : un
        // espace sépare le dernier attribut de la fermeture.
        val slash = extrait.lastIndexOf('/', chevron - 1)
        val fermetureAuto = slash >= 0 && extrait.substring(slash + 1, chevron).all { it == ' ' }
        val position = if (fermetureAuto) slash else chevron
        val avantFermeture =
            if (multiline) {
                " "
            } else if (position > 0 && extrait[position - 1] != ' ') {
                " "
            } else {
                ""
            }
        return extrait.substring(0, position) + injection + avantFermeture + extrait.substring(position)
    }

    /** Les attributs DÉJÀ POSÉS sur la première balise : nom → plage de la valeur. */
    private fun attributsExistants(extrait: String, chevron: Int): Map<String, IntRange> {
        val balise = extrait.substring(0, chevron)
        val existants = LinkedHashMap<String, IntRange>()
        for (correspondance in REGEX_ATTRIBUT.findAll(balise)) {
            existants[correspondance.groupValues[1]] = correspondance.range
        }
        return existants
    }

    /** REMPLACE en place la valeur des attributs déjà posés (même nom, nouvelle valeur). */
    private fun remplacerValeurs(
        extrait: String,
        attributs: List<Pair<String, String>>,
        existants: Map<String, IntRange>,
    ): String {
        var resultat = extrait
        for ((nom, valeur) in attributs) {
            val plage = existants[nom] ?: continue
            resultat = resultat.replaceRange(plage, "$nom=\"$valeur\"")
        }
        return resultat
    }

    /** Un attribut de la première balise : nom = « valeur » (le groupe 1 porte le nom, la plage matche la valeur). */
    private val REGEX_ATTRIBUT: Regex = Regex("""([\w:]+)="([^"]*)"""")

    // ── Détails de la dérivation (purs, privés) ─────────────────────────

    /**
     * La fin de l'édition : une plage `(debut, fin)` du texte remplacée
     * par [remplacement] — le [point] est l'offset utile au composer
     * (ordre racine → insertion).
     */
    private class FinEdition(val point: Int, val debut: Int, val fin: Int, val remplacement: String)

    private fun AligneurElementsXml.ElementSource.aSpan(): Boolean = debut >= 0 && fin > debut

    /** L'élément du aligner() frais dont le span ÉGALISE le span rendu. */
    private fun elementAuSpan(
        elements: List<AligneurElementsXml.ElementSource>,
        spanParent: SpanParent,
    ): AligneurElementsXml.ElementSource? = elements.lastOrNull { element ->
        element.aSpan() && element.debut == spanParent.debut && element.fin == spanParent.fin
    }

    /**
     * Les enfants DIRECTS (reconduction de parenté par PILE sur les spans :
     * un ancêtre se ferme APRÈS son enfant — on dépile quand le span
     * courant commence après la fermeture du sommet ; le SOMMET de la pile
     * est alors le parent de l'élément).
     */
    private fun enfantsDirects(
        elements: List<AligneurElementsXml.ElementSource>,
        parent: AligneurElementsXml.ElementSource,
    ): List<AligneurElementsXml.ElementSource> {
        val enfants = mutableListOf<AligneurElementsXml.ElementSource>()
        val pile = mutableListOf<AligneurElementsXml.ElementSource>()
        for (element in elements) {
            if (!element.aSpan()) continue
            while (pile.isNotEmpty() && pile.last().fin <= element.debut) pile.removeAt(pile.size - 1)
            if (pile.isNotEmpty() && pile.last() === parent) enfants.add(element)
            pile.add(element)
        }
        return enfants
    }

    /** Le conteneur se ferme-t-il en `/>` (aucun enfant possible) ? */
    private fun autoFermant(texte: String, parent: AligneurElementsXml.ElementSource): Boolean {
        val slash = texte.lastIndexOf('/', (parent.fin - 2).coerceAtLeast(parent.debut + 1))
        return slash > parent.debut && texte.substring(slash + 1, parent.fin).all { it == ' ' || it == '>' }
    }

    /** Le nom de balise du parent (lecture directe du texte, après le `<`). */
    private fun nomBaliseDe(texte: String, parent: AligneurElementsXml.ElementSource): String {
        var fin = parent.debut + 1
        while (fin < texte.length && (texte[fin].isLetterOrDigit() || texte[fin] in "._-")) {
            fin += 1
        }
        return texte.substring(parent.debut + 1, fin)
    }

    /**
     * L'insertion AVANT un frère : la ligne du frère déjà indentée →
     * l'extrait la REPREND (le frère suit à son indentation conservée) ;
     * frère en milieu de ligne (document compact) → l'extrait s'ouvre sur
     * ses propres lignes.
     */
    private fun avantFrere(
        texte: String,
        frere: AligneurElementsXml.ElementSource,
        extraitIndente: String,
    ): FinEdition? {
        if (!frere.aSpan()) return null
        val debutLigne = texte.lastIndexOf('\n', (frere.debut - 1).coerceAtLeast(0)) + 1
        val prefixe = texte.substring(debutLigne, frere.debut)
        return if (prefixe.isBlank()) {
            FinEdition(
                point = frere.debut,
                debut = debutLigne,
                fin = frere.debut,
                remplacement = "$extraitIndente\n$prefixe",
            )
        } else {
            FinEdition(
                point = frere.debut,
                debut = frere.debut,
                fin = frere.debut,
                remplacement = "\n$extraitIndente\n",
            )
        }
    }

    /** L'offset du `<` de la balise fermante du parent (conteneur ouvert). */
    private fun debutFermante(texte: String, parent: AligneurElementsXml.ElementSource): Int? {
        if (autoFermant(texte, parent)) return null
        val position = texte.lastIndexOf('<', parent.fin - 1)
        return if (position > parent.debut) position else null
    }

    /**
     * L'insertion EN FIN de conteneur : conteneur OUVERT → l'extrait
     * s'insère avant la fermante (la ligne de la fermante, si elle n'a
     * que son indentation, est CONSOMMÉE pour servir à l'extrait) ;
     * conteneur AUTO-FERMANT → conversion en paire ouvrante/fermante
     * (miroir maquette : « balise auto-fermante convertie en paire
     * ouvrante et fermante »).
     */
    private fun finConteneurOuPaire(
        texte: String,
        parent: AligneurElementsXml.ElementSource,
        indentParent: String,
        extraitIndente: String,
    ): FinEdition? {
        if (autoFermant(texte, parent)) {
            val slash = texte.lastIndexOf('/', (parent.fin - 2).coerceAtLeast(parent.debut + 1))
            if (slash <= parent.debut) return null
            // La plage couvre « / » SEUL : le « > » de l'auto-fermante RESTE
            // (il sert de fermeture à la balise ouverte) — miroir maquette.
            val fermeture = "\n$extraitIndente\n$indentParent</${nomBaliseDe(texte, parent)}>"
            return FinEdition(point = slash, debut = slash, fin = parent.fin, remplacement = ">$fermeture")
        }
        val fermante = debutFermante(texte, parent) ?: return null
        val debutLigne = texte.lastIndexOf('\n', (fermante - 1).coerceAtLeast(0)) + 1
        val prefixe = texte.substring(debutLigne, fermante)
        return if (prefixe.isBlank()) {
            FinEdition(point = fermante, debut = debutLigne, fin = fermante, remplacement = "$extraitIndente\n$prefixe")
        } else {
            FinEdition(point = fermante, debut = fermante, fin = fermante, remplacement = "\n$extraitIndente\n")
        }
    }

    /**
     * Compose l'ÉDITION UNIQUE : `xmlns:app` sur la balise racine si
     * l'extrait l'utilise et que la racine ne le porte pas (la plage
     * s'étend du chevron racine au point d'insertion — un seul appel de
     * `remplacer`, un seul annuler) ; sinon l'insertion seule. Quand
     * l'insertion TOUCHE la balise racine (racine auto-fermante convertie
     * en paire — la conversion remplace son `/>`), les deux modifications
     * FUSIONNENT sur une plage commune, sinon l'édition refuse (jamais de
     * chevauchement deviné).
     */
    private fun composerEdition(
        texte: String,
        elements: List<AligneurElementsXml.ElementSource>,
        fin: FinEdition,
        extrait: String,
    ): EditionXml? {
        if (fin.debut < 0 || fin.fin < fin.debut || fin.fin > texte.length) return null
        val racine = elements.firstOrNull() ?: return null
        if (!racine.aSpan()) return null
        val chevronRacine = chevronOuvrant(texte, racine.debut) ?: return null

        val dejaLa = texte.substring(racine.debut, chevronRacine).contains("xmlns:app")
        val appRequis = extrait.contains(MARQUEUR_APP) && !dejaLa
        if (!appRequis) {
            return EditionXml(debut = fin.debut, fin = fin.fin, remplacement = fin.remplacement)
        }

        if (chevronRacine >= fin.debut) return fusionRacineAutoFermante(texte, fin, chevronRacine, racine)
        // Le xmlns s'insère AVANT le chevron ouvrant racine (ou avant le
        // « / » d'une racine auto-fermante non touchée) — miroir [contraindre].
        val slash = texte.lastIndexOf('/', chevronRacine - 1)
        val fermetureAutoRacine =
            slash > racine.debut && texte.substring(slash + 1, chevronRacine).all { it == ' ' || it == '>' }
        val position = if (fermetureAutoRacine) slash else chevronRacine
        val remplacementA =
            texte.substring(chevronRacine, position) + " $ATTRIBUT_XMLNS_APP" +
                texte.substring(position, chevronRacine + 1)
        return EditionXml(
            debut = min(position, fin.debut),
            fin = max(chevronRacine + 1, fin.fin),
            remplacement = remplacementA + texte.substring(chevronRacine + 1, fin.debut) + fin.remplacement,
        )
    }

    /**
     * Le CHEVAUCHEMENT : uniquement la racine auto-fermante dont la
     * conversion en paire (fin.remplacement débutant par « > ») couvre
     * [slash, racine.fin) — les deux modifications (xmlns:app + paire)
     * FUSIONNENT sur une plage commune, sinon l'édition refuse (jamais
     * de chevauchement deviné).
     */
    private fun fusionRacineAutoFermante(
        texte: String,
        fin: FinEdition,
        chevronRacine: Int,
        racine: AligneurElementsXml.ElementSource,
    ): EditionXml? {
        val slash = texte.lastIndexOf('/', chevronRacine - 1)
        val fusionValide =
            slash >= 0 && fin.debut == slash && fin.fin == racine.fin && fin.remplacement.startsWith(">")
        if (!fusionValide) return null
        return EditionXml(
            debut = slash,
            fin = fin.fin,
            remplacement = " $ATTRIBUT_XMLNS_APP" + fin.remplacement,
        )
    }

    /**
     * L'indentation de l'élément : le préfixe de SA ligne s'il n'a que
     * des espaces/tabulations (identifiant inclus), sinon [repli] — un
     * repli `null` signifie « document compact, style indéduisible ».
     */
    private fun indentationDe(texte: String, offset: Int, repli: String?): String? {
        if (offset <= 0 || offset > texte.length) return repli
        val debutLigne = texte.lastIndexOf('\n', offset - 1) + 1
        val prefixe = texte.substring(debutLigne, offset)
        return if (prefixe.all { it == ' ' || it == '\t' }) prefixe else repli
    }

    /** L'indentation des enfants : le style du premier enfant détectable, sinon `null`. */
    private fun indentEnfantDetecte(texte: String, enfants: List<AligneurElementsXml.ElementSource>): String? {
        for (enfant in enfants) {
            if (!enfant.aSpan()) continue
            val detectee = indentationDe(texte, enfant.debut, repli = null)
            if (detectee != null) return detectee
        }
        return null
    }

    /** Préfixe chaque ligne de l'extrait (lignes vides exceptées). */
    private fun indenter(extrait: String, indent: String): String = extrait
        .split('\n')
        .joinToString("\n") { ligne -> if (ligne.isBlank()) ligne else indent + ligne }

    /**
     * L'offset du chevron `>` fermant la BALISE OUVRANTE qui commence à
     * [debut] (état guillemets : un `>` dans une valeur d'attribut ne
     * ferme pas).
     */
    private fun chevronOuvrant(texte: String, debut: Int): Int? {
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
    private fun indentationDernierAttribut(balise: String, chevron: Int): String {
        val debutLigne = balise.lastIndexOf('\n', chevron - 1) + 1
        val prefixe = balise.substring(debutLigne, chevron)
        val indent = prefixe.takeWhile { it == ' ' || it == '\t' }
        return indent.ifEmpty { PAS_INDENTATION }
    }
}
