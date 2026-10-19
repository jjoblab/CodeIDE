package jo.codeide.preview.xml

/**
 * La COLORATION des lignes de l'onglet Source (PE11k — brief
 * `docs/PE11-attributs-brief.md` § PE11k et `PREVIEW_UI_ECRANS.md` § 9 :
 * « balise = `apercuPrimaire`, attribut = `apercuRemplissage`, valeur =
 * `apercuMarge` » — le commentaire de `item_ligne_source.xml` de la
 * référence porte le même contrat).
 *
 * Module PUR (règle 1) : produit des RÔLES par plage de caractères —
 * l'UI traduit chaque rôle en `ForegroundColorSpan` lu dans les
 * attributs du thème (jamais de couleur en dur ici).
 *
 * La maquette v5 n'applique pas encore la coloration (`fmt` = échappement
 * seul) mais la SPÉCIFICATION l'exige : les cotes `tg`/`at`/`vl` de
 * `meas_ecrans_clair.json` mesurent ces trois rôles.
 *
 * Machine À ÉTATS sur le document ENTIER (une valeur entre guillemets
 * peut s'étendre sur plusieurs lignes — colorer chaque ligne seule
 * casserait sur ces documents). Les portions TEXTE ne sont pas émises
 * (le texte hors balise n'est pas coloré).
 */
public object ColorationSourceXml {
    /** Le rôle coloré d'une plage de caractères (uncoloré = TEXTE, non émis). */
    public enum class Role {
        /** Balise, nom d'élément et ponctuation de balise (primaire). */
        BALISE,

        /** Nom d'attribut (remplissage). */
        ATTRIBUT,

        /** Valeur d'attribut, entre guillemets (marge). */
        VALEUR,
    }

    /** UNE plage colorée : [debut, fin[ DANS SA LIGNE, avec son rôle. */
    public data class Portion(
        public val debut: Int,
        public val fin: Int,
        public val role: Role,
    )

    /**
     * Colore le document entier, PORTIONS PAR LIGNE : la coloration est
     * continue d'une ligne à l'autre — une portion qui traverse un saut
     * est DÉCOUPÉE, chaque tronçon garde son rôle.
     *
     * @return une liste par LIGNE (l'index 0 = ligne 1) ; une ligne sans
     *         portion colorée reçoit une liste VIDE.
     */
    public fun colorerParLigne(texte: String): List<List<Portion>> {
        val portions = colorerDocument(texte)
        val parLigne = Array(texte.count { it == '\n' } + 1) { mutableListOf<Portion>() }
        val debutsLignes = IntArray(parLigne.size)
        var numero = 0
        for (index in texte.indices) {
            if (texte[index] == '\n') {
                numero++
                debutsLignes[numero] = index + 1
            }
        }
        var ligne = 0
        for (portion in portions) {
            // Monte à la ligne du DÉBUT de la portion (les portions
            // sortent TRIÉES du scanner — le curseur ne redescend jamais).
            while (ligne + 1 < parLigne.size && debutsLignes[ligne + 1] <= portion.debut) {
                ligne++
            }
            var debutLigne = debutsLignes[ligne]
            var curseur = portion.debut
            while (curseur < portion.fin && ligne < parLigne.size) {
                val saut = texte.indexOf('\n', debutLigne)
                val finLigne = (if (saut < 0) texte.length else saut).coerceAtMost(portion.fin)
                if (finLigne > curseur) {
                    parLigne[ligne].add(
                        Portion(
                            debut = curseur - debutLigne,
                            fin = finLigne - debutLigne,
                            role = portion.role,
                        ),
                    )
                }
                if (finLigne >= portion.fin) break
                // La portion traverse le saut : tronçon sur la ligne suivante.
                ligne++
                debutLigne = debutsLignes[ligne]
                curseur = debutLigne
            }
        }
        return parLigne.toList()
    }

    /**
     * Les portions colorées du document, à plat (offsets GLOBAUX, triées
     * par début — invariant consommé par [colorerParLigne]).
     */
    private fun colorerDocument(texte: String): List<Portion> {
        val portions = mutableListOf<Portion>()
        var index = 0
        while (index < texte.length) {
            when {
                texte.startsWith(OUVERT_COMMENTAIRE, index) -> {
                    // Commentaire : ENTIER en balise (choix simple — aucun
                    // faux positif sur son contenu), jusqu'à « --> ».
                    val fin = finDeCommentaire(texte, index)
                    portions.add(Portion(index, fin, Role.BALISE))
                    index = fin
                }

                texte[index] == '<' -> {
                    index = colorerBalise(texte, index, portions)
                }

                else -> {
                    index++
                }
            }
        }
        return portions
    }

    /**
     * Colore UNE balise à partir de son « < » : le nom et la ponctuation
     * en BALISE, chaque nom d'attribut en ATTRIBUT, chaque valeur entre
     * guillemets en VALEUR — CHAQUE run est ÉMISE au CHANGEMENT de rôle
     * (jamais perdue). Retourne l'offset APRÈS le « > » fermant (ou la
     * fin d'une balise jamais fermée — frappe en cours).
     *
     * Exemption detekt ciblée (règle 16) : CyclomaticComplexMethod — une
     * machine À ÉTATS : chaque branche du `when` EST une transition du
     * scanner (fermeture, valeur, attribut, indentation, ponctuation) ;
     * la découper dupliquerait l'état partagé (index, rôle, début de
     * run) entre des fragments qui se rappellent — moins lisible que le
     * flot continu (même patron que le pipeline [RenduLayoutLib.rendre]).
     */
    @Suppress("CyclomaticComplexMethod")
    private fun colorerBalise(
        texte: String,
        debutBalise: Int,
        portions: MutableList<Portion>,
    ): Int {
        var index = debutBalise
        var role = Role.BALISE
        var debutPortion = debutBalise
        var fermee = false
        while (index < texte.length && !fermee) {
            val caractere = texte[index]
            when {
                caractere == '>' -> {
                    fermerBalise(texte, portions, debutPortion, index, role)
                    fermee = true
                    index++
                }

                caractere == '"' -> {
                    // La valeur : jusqu'au guillemet fermant (jamais fermé —
                    // frappe en cours : jusqu'à la fin du document).
                    emettreAttribut(texte, portions, debutPortion, index, role)
                    index = ouvrirValeur(texte, index, portions)
                    debutPortion = index
                }

                caractere == '=' && role == Role.ATTRIBUT -> {
                    // Fin d'un nom d'attribut : la run part, la suite
                    // reprend après le « = ».
                    emettreAttribut(texte, portions, debutPortion, index, role)
                    index++
                    debutPortion = index
                }

                caractere.isWhitespace() && role == Role.BALISE -> {
                    // Fin du NOM de balise : la run BALISE est ÉMISE, le
                    // prochain mot devient ATTRIBUT.
                    portions.add(Portion(debutPortion, index, Role.BALISE))
                    role = Role.ATTRIBUT
                    index++
                    debutPortion = index
                }

                caractere.isWhitespace() && role == Role.ATTRIBUT && portionVide(texte, debutPortion, index) -> {
                    // Espaces D'INDENTATION avant un nom d'attribut : la
                    // run démarre au premier caractère significatif —
                    // jamais d'indentation colorée en attribut.
                    index++
                    debutPortion = index
                }

                (caractere == '/' || caractere == '?') && role == Role.ATTRIBUT -> {
                    // Ponctuation de fermeture (« /> », « ?> ») : une
                    // éventuelle run d'attribut part D'ABORD, la fermeture
                    // redevient BALISE en démarrant À la ponctuation —
                    // jamais un attribut vert, jamais d'espace dans la run.
                    emettreAttribut(texte, portions, debutPortion, index, role)
                    role = Role.BALISE
                    debutPortion = index
                    index++
                }

                else -> {
                    index++
                }
            }
        }
        // Balise jamais fermée (frappe en cours) : la portion en cours
        // part telle quelle — jamais de couleur orpheline au liage suivant.
        if (runTronquee(texte, fermee, debutPortion)) {
            portions.add(Portion(debutPortion, texte.length, role))
        }
        return index
    }

    /** Reste-t-il une run NON VIDE à émettre pour une balise jamais fermée ? */
    private fun runTronquee(
        texte: String,
        fermee: Boolean,
        debutPortion: Int,
    ): Boolean {
        if (fermee || debutPortion < 0 || texte.length <= debutPortion) return false
        return !portionVide(texte, debutPortion, texte.length)
    }

    /** Émet la run d'ATTRIBUT en cours si elle porte du contenu (rôle lu — jamais les autres). */
    private fun emettreAttribut(
        texte: String,
        portions: MutableList<Portion>,
        debut: Int,
        fin: Int,
        role: Role,
    ) {
        if (role == Role.ATTRIBUT && !portionVide(texte, debut, fin)) {
            portions.add(Portion(debut, fin, Role.ATTRIBUT))
        }
    }

    /** Émet la VALEUR du guillemet ouvrant au fermant ; renvoie l'offset APRÈS la fermeture. */
    private fun ouvrirValeur(
        texte: String,
        guillemet: Int,
        portions: MutableList<Portion>,
    ): Int {
        val fermeture = texte.indexOf('"', guillemet + 1).let { if (it < 0) texte.length else it }
        if (fermeture > guillemet + 1) {
            portions.add(Portion(guillemet + 1, fermeture, Role.VALEUR))
        }
        return fermeture + 1
    }

    /** Émet la fermeture de la balise : la run en cours + le chevron, chacun à SON rôle. */
    private fun fermerBalise(
        texte: String,
        portions: MutableList<Portion>,
        debutPortion: Int,
        chevron: Int,
        role: Role,
    ) {
        if (role == Role.ATTRIBUT) {
            // Attribut sans valeur (« <Button foo> ») : la run part en
            // ATTRIBUT, le « > » reste en BALISE.
            if (!portionVide(texte, debutPortion, chevron)) {
                portions.add(Portion(debutPortion, chevron, Role.ATTRIBUT))
            }
            portions.add(Portion(chevron, chevron + 1, Role.BALISE))
        } else {
            portions.add(Portion(debutPortion, chevron + 1, role))
        }
    }

    /** La fin d'un commentaire « --> » (frappe en cours : fin du document). */
    private fun finDeCommentaire(
        texte: String,
        debut: Int,
    ): Int {
        val fermeture = texte.indexOf(FERMET_COMMENTAIRE, debut + OUVERT_COMMENTAIRE.length)
        return if (fermeture < 0) texte.length else fermeture + FERMET_COMMENTAIRE.length
    }

    /** La run [debut, fin[ ne porte-t-elle QUE des blancs (rien à colorer) ? */
    private fun portionVide(
        texte: String,
        debut: Int,
        fin: Int,
    ): Boolean = texte.substring(debut, fin).isBlank()

    /** L'ouverture d'un commentaire XML. */
    private const val OUVERT_COMMENTAIRE: String = "<!--"

    /** La fermeture d'un commentaire XML. */
    private const val FERMET_COMMENTAIRE: String = "-->"
}
