package jo.codeide.preview.xml

/**
 * Le porte-documents des VOLETS de la feuille (PE9a, maquette
 * `docs/mockups/apercu-xml.html` § « Feuille inférieure : arbre,
 * attributs, problèmes, source ») — FONCTIONS PURES sur l'état du
 * moteur (« alimentée par le même état que la vue »), SANS Android :
 * le fragment `FeuilleApercuFragment` de `:feature:preview` ne fait que
 * la mise en forme des modèles (miroir `renderTree` / `renderProbs` /
 * `renderSrc` de la maquette), la logique est testée ici. Les entrées
 * PURES (arbre, problèmes) et les plages de lignes (surlignage de la
 * vue sélectionnée, PE11k : plage insérée par une écriture) n'ont
 * AUCUNE vue — que des données.
 *
 * L'onglet ATTRIBUTS reste LECTURE SEULE en PE6d (l'édition d'attributs
 * est PE7d) : il affiche les DONNÉES MESURÉES du rendu — identité
 * (classe, id), disposition (dimensions, marges, padding, orientation),
 * mesures (position, cliquable, conteneur) — issues de l'[InfoVueRendu]
 * enrichie (PE6c) ; les valeurs des attributs du TEXTE (android:text…)
 * restent consultables dans l'onglet Source.
 *
 * Exemptions detekt ciblées (règle 16) : ReturnCount — chaque garde
 * (vue absente, spans absents, texte vide) est une issue DISTINCTE du
 * remplissage des volets (patron [SelectionPreview]) ; TooManyFunctions
 * — une fonction PAR morceau de la maquette, étendue en PE11k (plage
 * insérée) sans regrouper artificiellement des contrats distincts
 * (même exemption que le pipeline [RenduLayoutLib]).
 */
@Suppress("ReturnCount", "TooManyFunctions")
public object FeuilleApercu {
    /**
     * UNE ligne de l'ARBRE (miroir `renderTree`) : indentation par
     * profondeur, monogramme de classe, libellé « Classe · @+id/x »,
     * badge « ! n » (problèmes) sinon dimensions « w×h » en dp.
     *
     * @property indice indice de la vue dans l'arbre aplati (cible de la
     *           sélection croisée).
     * @property indentation indentation des rangées, en dp d'écran
     *           (miroir `8 + d * 16` px).
     * @property monogramme initiale de la classe (miroir `k`).
     * @property classe nom simple de la classe.
     * @property idNom nom d'entrée de la ressource id (sans préfixe).
     * @property badge badge de fin de rangée (problèmes ou dimensions).
     * @property aProblemes la rangée porte le badge de problèmes.
     * @property genre le GENRE de la rangée (PE14-S1, ADR 0139) : contenu
     *           sélectionnable, ou section « Système » — entête ou barre
     *           du décor, JAMAIS sélectionnable (aucune balise source).
     */
    public data class EntreeArbre(
        val indice: Int,
        val indentation: Int,
        val monogramme: String,
        val classe: String,
        val idNom: String,
        val badge: String,
        val aProblemes: Boolean,
        val genre: GenreRangee = GenreRangee.CONTENU,
    )

    /**
     * Le genre d'une rangée de l'arbre (PE14-S1, ADR 0139) : le contenu
     * de l'utilisateur (sélectionnable), l'ENTÊTE de la section
     * « Système », ou une BARRE du décor (non sélectionnable — miroir le
     * toast de la maquette : « Barre système : aucune balise XML à
     * sélectionner »).
     */
    public enum class GenreRangee {
        /** Une vue du document — sélection croisée au tap. */
        CONTENU,

        /** L'entête « Système » — un LIBELLÉ, pas une vue. */
        ENTETE_SYSTEME,

        /** Une barre du décor (status bar, navigation, action bar). */
        VUE_SYSTEME,
    }

    /** Indentation de base d'une rangée d'arbre, en dp (miroir `paddingLeft = 8 + …`). */
    private const val INDENTATION_BASE_DP: Int = 8

    /** Pas d'indentation par profondeur, en dp (miroir `d * 16`). */
    private const val INDENTATION_PAS_DP: Int = 16

    /** L'indice de l'ENTÊTE « Système » (jamais une cible de sélection). */
    private const val INDICE_ENTETE_SYSTEME: Int = -1

    /**
     * Les rangées de la SECTION « Système » de l'arbre (PE14-S1, ADR
     * 0139 — les barres du décor, collecte duale LayoutLib 3.5.0) : une
     * ENTÊTE puis une rangée par BARRE de premier rang — libellé fourni
     * par l'UI (chaîne résolue du [InfoVueSysteme.type], repli le nom de
     * classe), monogramme de la CLASSE (les barres partagent des
     * initiales de libellé — « S » de « StatusBar » se lit mieux que le
     * « B » de « Barre »), badge des dimensions au profil. Vide sans
     * décor — l'arbre reste celui du contenu.
     *
     * @param vuesSysteme les barres du décor du DERNIER rendu.
     * @param densiteDpi la densité du PROFIL (badge dp — 0 : repli px).
     * @param entete le LIBELLÉ de l'entête de section (chaîne de l'UI).
     * @param libelle résout le LIBELLÉ affichable d'une barre (pur : la
     *        couche UI fournit les chaînes du thème de l'app).
     */
    public fun entreesSysteme(
        vuesSysteme: List<InfoVueSysteme>,
        densiteDpi: Int,
        entete: String,
        libelle: (InfoVueSysteme) -> String,
    ): List<EntreeArbre> {
        if (vuesSysteme.isEmpty()) return emptyList()
        val rangees = mutableListOf<EntreeArbre>()
        rangees +=
            EntreeArbre(
                // L'entête : indice DÉDIÉ (les barres suivent à partir de
                // −2) — la clé DiffUtil reste UNIQUE par rangée.
                indice = INDICE_ENTETE_SYSTEME,
                indentation = INDENTATION_BASE_DP,
                monogramme = "",
                classe = entete,
                idNom = "",
                badge = "",
                aProblemes = false,
                genre = GenreRangee.ENTETE_SYSTEME,
            )
        vuesSysteme.forEachIndexed { position, vue ->
            rangees +=
                EntreeArbre(
                    // Indices NÉGATIFS distincts (l'entête occupe −1) : les
                    // rangées système ne sont JAMAIS des cibles de
                    // sélection, la clé DiffUtil reste UNIQUE.
                    indice = -(position + 2),
                    indentation = INDENTATION_BASE_DP,
                    monogramme = monogramme(vue.classe),
                    classe = libelle(vue),
                    idNom = "",
                    badge = badgeDimensionsSysteme(vue, densiteDpi),
                    aProblemes = false,
                    genre = GenreRangee.VUE_SYSTEME,
                )
        }
        return rangees
    }

    /**
     * Les rangées de l'arbre (miroir `renderTree` : parcours PRÉ-ORDRE de
     * l'arbre aplati — la racine comprise, comme la maquette). Le badge
     * « ! n » compte les problèmes de la vue ; sans problème, les
     * dimensions en dp du PROFIL (densité ≤ 0 → pas de badge).
     */
    public fun entreesArbre(
        vues: List<InfoVueRendu>,
        problemes: List<ProblemesPreview.ProblemeApercu>,
        densiteDpi: Int,
    ): List<EntreeArbre> {
        if (vues.isEmpty()) return emptyList()
        val comptes = problemeParVue(problemes)
        return vues.mapIndexed { indice, vue ->
            val nombre = comptes[indice] ?: 0
            val badge =
                if (nombre > 0) {
                    "! $nombre"
                } else {
                    badgeDimensions(vue, densiteDpi)
                }
            EntreeArbre(
                indice = indice,
                indentation = INDENTATION_BASE_DP + vue.profondeur * INDENTATION_PAS_DP,
                monogramme = monogramme(vue.classe),
                classe = vue.classe,
                idNom = vue.idRes.substringAfterLast('/'),
                badge = badge,
                aProblemes = nombre > 0,
            )
        }
    }

    /**
     * UNE paire d'attributs affichée (libellé, valeur) — miroir des
     * groupes `dl.kv` de la maquette.
     */
    public data class EntreeAttribut(val libelle: String, val valeur: String)

    /** UN groupe d'attributs de l'onglet Attributs (miroir `groups`). */
    public data class GroupeAttributs(val titre: String, val entrees: List<EntreeAttribut>)

    /**
     * Les groupes d'attributs de la vue sélectionnée, LECTURE SEULE
     * (miroir `renderAttrs`, sans `renderEditor` — PE7d) : Identité,
     * Disposition, Mesuré — composés depuis les données MESURÉES
     * ([InfoVueRendu] enrichie PE6c), pas les attributs du texte. Les
     * conversions px → dp se font par la densité du PROFIL (repli px si
     * densité inconnue — jamais une valeur inventée).
     */
    public fun groupesAttributs(vue: InfoVueRendu, densiteDpi: Int): List<GroupeAttributs> {
        val idNom = vue.idRes.substringAfterLast('/')
        val identite =
            buildList {
                add(EntreeAttribut("classe", vue.classe))
                if (idNom.isNotEmpty()) add(EntreeAttribut("id", "@+id/$idNom"))
                add(EntreeAttribut("conteneur", if (vue.conteneur) "oui" else "non"))
            }
        val disposition =
            buildList {
                add(EntreeAttribut("layout_width", dpOuPx(vue.largeurPx, densiteDpi)))
                add(EntreeAttribut("layout_height", dpOuPx(vue.hauteurPx, densiteDpi)))
                if (vue.orientation != InfoVueRendu.ORIENTATION_SANS_OBJET) {
                    add(
                        EntreeAttribut(
                            "orientation",
                            if (vue.orientation == InfoVueRendu.ORIENTATION_HORIZONTALE) "horizontal" else "vertical",
                        ),
                    )
                }
                bande(vue.margesGauche, densiteDpi)?.let { add(EntreeAttribut("layout_marginStart", it)) }
                bande(vue.margesHaut, densiteDpi)?.let { add(EntreeAttribut("layout_marginTop", it)) }
                bande(vue.margesDroite, densiteDpi)?.let { add(EntreeAttribut("layout_marginEnd", it)) }
                bande(vue.margesBas, densiteDpi)?.let { add(EntreeAttribut("layout_marginBottom", it)) }
                bande(vue.paddingGauche, densiteDpi)?.let { add(EntreeAttribut("paddingStart", it)) }
                bande(vue.paddingHaut, densiteDpi)?.let { add(EntreeAttribut("paddingTop", it)) }
                bande(vue.paddingDroite, densiteDpi)?.let { add(EntreeAttribut("paddingEnd", it)) }
                bande(vue.paddingBas, densiteDpi)?.let { add(EntreeAttribut("paddingBottom", it)) }
            }
        val mesure =
            buildList {
                add(EntreeAttribut("position", "${dpOuPx(vue.gauche, densiteDpi)}, ${dpOuPx(vue.haut, densiteDpi)}"))
                add(EntreeAttribut("cliquable", if (vue.cliquable) "oui" else "non"))
            }
        return listOf(
            GroupeAttributs("Identité", identite),
            GroupeAttributs("Disposition", disposition),
            GroupeAttributs("Mesuré", mesure),
        )
    }

    /**
     * UN problème de l'onglet Problèmes (miroir `renderProbs`) : les
     * DONNÉES du problème sont pures ([ProblemesPreview.ProblemeApercu]),
     * les libellés viennent des ressources de l'UI.
     *
     * @property indiceVue la vue fautive (cible de la sélection croisée).
     * @property erreur pastille rouge (sinon ambre).
     */
    public data class EntreeProbleme(
        val indiceVue: Int,
        val erreur: Boolean,
        val genre: ProblemesPreview.GenreProbleme,
    )

    /** Les problèmes AFFICHABLES de l'onglet (ordre du moteur, rattachés à leur vue). */
    public fun entreesProblemes(problemes: List<ProblemesPreview.ProblemeApercu>): List<EntreeProbleme> =
        problemes.map { probleme ->
            EntreeProbleme(
                indiceVue = probleme.indiceVue,
                erreur = probleme.severite == ProblemesPreview.Severite.ERREUR,
                genre = probleme.genre,
            )
        }

    /**
     * La PLAGE de lignes (1-based, bornée) à surligner dans l'onglet
     * Source pour le span de la vue sélectionnée — miroir `renderSrc` :
     * « Lignes X à Y ». Sans vue (sélection nulle, racine non vue, spans
     * absents) → `null` (le document entier reste affiché sans
     * surlignage — la sélection ne devine JAMAIS).
     *
     * @param texte le document affiché (TEXTE COURANT de la session).
     * @param vue la vue sélectionnée (spans du DERNIER rendu valide —
     *        un texte modifié depuis peut décaler les lignes : comportement
     *        assumé, le rendu reste la référence affichée).
     */
    public fun plageSource(texte: String, vue: InfoVueRendu?): IntRange? {
        if (vue == null || !vue.selectable || texte.isEmpty()) return null
        val debut = AligneurElementsXml.numeroLigne(texte, vue.debutElement)
        val fin = AligneurElementsXml.numeroLigne(texte, (vue.finElement - 1).coerceAtLeast(vue.debutElement))
        return if (fin < debut) null else debut..fin
    }

    /**
     * Les NUMÉROS DE LIGNES (1-based, bornée) couvrant la plage
     * [debut, fin[ du texte (PE11k — surlignage « inséré » de la
     * Source : la plage posée par une écriture d'insertion, dépôt ou
     * placement). Null si la plage est vide ou hors texte.
     */
    public fun lignesDePlage(texte: String, debut: Int, fin: Int): IntRange? {
        if (texte.isEmpty() || fin <= debut) return null
        val premiere = AligneurElementsXml.numeroLigne(texte, debut.coerceIn(0, texte.length - 1))
        val derniere =
            AligneurElementsXml.numeroLigne(
                texte,
                (fin - 1).coerceIn(0, texte.length - 1),
            )
        return if (derniere < premiere) null else premiere..derniere
    }

    /**
     * La vue PROPRIÉTAIRE d'une ligne du document (miroir `ownerOf` :
     * « le plus PETIT span contenant » — c'est
     * [SelectionPreview.indiceVuePourSpan], branché sur le DERNIER
     * caractère de la ligne : les spans sont des offsets d'ÉLÉMENT (du
     * `<` au `>`), la ligne d'un élément s'achève sur lui — le clic
     * sélectionne la vue la plus interne présente sur la ligne, jamais la
     * simple indentation qui la précède).
     */
    public fun vueDeLigne(vues: List<InfoVueRendu>, texte: String, ligne: Int): Int {
        if (ligne < 1 || vues.isEmpty() || texte.isEmpty()) return -1
        // Ligne INEXISTANTE (au-delà du document) : aucune vue (jamais la
        // dernière ligne du document à sa place).
        if (ligne > AligneurElementsXml.numeroLigne(texte, texte.length)) return -1
        val debut = AligneurElementsXml.debutLigne(texte, ligne)
        val suivante = AligneurElementsXml.debutLigne(texte, ligne + 1)
        // Le dernier caractère de la ligne : avant le saut (ou la fin du
        // document pour la dernière ligne).
        val dernier = if (suivante > debut) suivante - 2 else texte.length - 1
        return SelectionPreview.indiceVuePourSpan(vues, dernier.coerceAtLeast(debut))
    }

    /** Le compte de problèmes PAR VUE (compteur de badge et pastilles). */
    private fun problemeParVue(problemes: List<ProblemesPreview.ProblemeApercu>): Map<Int, Int> = problemes
        .groupingBy { it.indiceVue }
        .eachCount()

    /** Le badge de dimensions « w×h » en dp (miroir `r0(q.w) + '×' + r0(q.h)`). */
    private fun badgeDimensions(vue: InfoVueRendu, densiteDpi: Int): String {
        if (vue.droite <= vue.gauche || vue.bas <= vue.haut) return ""
        if (densiteDpi <= 0) return "${vue.largeurPx}×${vue.hauteurPx}"
        val largeur = CalquesApercu.pxVersDp(vue.largeurPx, densiteDpi)
        val hauteur = CalquesApercu.pxVersDp(vue.hauteurPx, densiteDpi)
        return "$largeur×$hauteur"
    }

    /**
     * Le badge de dimensions d'une BARRE SYSTÈME (PE14-S1, ADR 0139 — même
     * convention que [badgeDimensions], sur les bornes du décor).
     */
    private fun badgeDimensionsSysteme(vue: InfoVueSysteme, densiteDpi: Int): String {
        if (vue.droite <= vue.gauche || vue.bas <= vue.haut) return ""
        if (densiteDpi <= 0) return "${vue.largeurPx}×${vue.hauteurPx}px"
        val largeur = CalquesApercu.pxVersDp(vue.largeurPx, densiteDpi)
        val hauteur = CalquesApercu.pxVersDp(vue.hauteurPx, densiteDpi)
        return "$largeur×$hauteur dp"
    }

    /** Une bande de marge/padding affichée (0 dp → masquée, miroir maquette `if (mt)`). */
    private fun bande(px: Int, densiteDpi: Int): String? {
        if (px <= 0) return null
        return if (densiteDpi > 0) "${CalquesApercu.pxVersDp(px, densiteDpi)}dp" else "${px}px"
    }

    /** Une dimension affichée (dp du profil, repli px si densité inconnue). */
    private fun dpOuPx(px: Int, densiteDpi: Int): String =
        if (densiteDpi > 0) "${CalquesApercu.pxVersDp(px, densiteDpi)}dp" else "${px}px"

    /** Le monogramme d'une rangée d'arbre (miroir `k` — initiale de la classe). */
    private fun monogramme(classe: String): String = classe.take(1).uppercase()
}
