package jo.codeide.core.domain

import java.util.regex.Pattern

/**
 * Une requête de recherche du lecteur — la dimension TEXTE du filtre
 * unifié ([FiltreSorties]) isolée pour la navigation « n/N » (spec
 * SORTIES § 3.4) : la recherche porte sur le TAMPON ENTIER (banc
 * SORTIES_BANC_MESURE S3 : « sur le tampon plein ») ; la visibilité
 * filtrée n'entre qu'au SAUT — le lecteur ne pose l'utilisateur que sur
 * une correspondance VISIBLE (les lignes masquées restent comptées dans
 * N, leur saut est sauté).
 *
 * @property texte motif de recherche (sous-chaîne ou regex).
 * @property regex `true` : [texte] est une expression régulière.
 * @property sensibleCasse `true` : la casse compte (défaut : insensible).
 * @property sensibleAccents `true` : les accents comptent (défaut : pliage
 *           NFD canonique, [PliageTexte]).
 * @property motEntier `true` : occurrence délimitée par des frontières de
 *           mot Unicode (hors regex — en regex, l'utilisateur écrit ses
 *           bornes).
 */
public data class RequeteRecherche(
    public val texte: String,
    public val regex: Boolean = false,
    public val sensibleCasse: Boolean = false,
    public val sensibleAccents: Boolean = false,
    public val motEntier: Boolean = false,
) {
    /** Requête vide : aucun état de recherche (l'interface masque le
     *  compteur). */
    public val estVide: Boolean
        get() = texte.isEmpty()
}

/**
 * Le résultat d'une recherche : positions dans le tampon (indices
 * 0-based ALIGNÉS sur l'état du tampon au moment de la dérivation — un
 * lot qui tronque la tête les décale, cf. [decaler]) et drapeaux
 * d'honnêteté.
 *
 * @property correspondances positions des lignes qui passent, croissantes.
 * @property motifInvalide `true` quand le motif regex est REJETÉ —
 *           l'interface l'affiche en ligne (repli « aucune
 *           correspondance », JAMAIS d'exception : héritage
 *           `FiltreLogcat`, ADR 0160).
 * @property interrompue `true` quand [RechercheSorties.rechercher] a été
 *           annulée en cours de balayage — le résultat partiel est livré
 *           tel quel (jamais de faux « aucune correspondance »).
 */
public data class ResultatRechercheSorties(
    public val correspondances: List<Int>,
    public val motifInvalide: Boolean = false,
    public val interrompue: Boolean = false,
) {
    /**
     * Décale des correspondances après une troncature de tête du tampon :
     * les lignes tombées de la fenêtre emportent leurs correspondances,
     * les autres glissent d'autant (l'identité par INDICE ne survit pas à
     * la fenêtre — le décalage est maintenu par l'appelant, jamais
     * recalculé par un re-scan).
     */
    public fun decaler(troncatureTete: Int): ResultatRechercheSorties = copy(
        correspondances = correspondances.mapNotNull { indice ->
            (indice - troncatureTete).takeIf { it >= 0 }
        },
    )
}

/**
 * Le moteur de recherche du lecteur (ADR 0159 décision 2 : « recherche
 * hors thread principal sur le tampon, jamais sur la vue », lot L2) —
 * pur JVM, annulable, ZÉRO exception : un motif regex invalide est
 * SIGNALÉ ([ResultatRechercheSorties.motifInvalide]) et tombe en repli « aucune
 * correspondance ».
 *
 * Deux chemins, MÊME sémantique que la dimension texte de
 * [FiltreSorties] (l'équivalence est testée) :
 * - **rapide** (sous-chaîne, insensible à la casse ET aux accents — le
 *   défaut) : scan du cache de pliage du tampon, une allocation
 *   d'aiguille au total — le budget « 100 000 lignes < 2 s » de la
 *   spec § 10.2 ;
 * - **général** (regex, mot entier, options de casse/accents) : même
 *   discipline de compilation que [FiltreSorties] — drapeaux de casse,
 *   bornes de mot Unicode, pliage de l'aiguille JAMAIS du motif.
 */
public object RechercheSorties {

    /**
     * Cherche [requete] sur le tampon représenté par [lignes] (textes
     * bruts — la regex lit le message SEUL, parité [FiltreSorties]) et
     * ses caches de pliage [messagesPlies]/[etiquettesPlies] (alignés).
     *
     * @param estAnnule sondage d'annulation, interrogé tous les
     *           [PAS_ANNULATION] lignes — le worker du lecteur y branche
     *           l'état vivant de sa coroutine.
     * @return les positions des correspondances, dans l'ordre.
     */
    public fun rechercher(
        lignes: List<LigneSortie>,
        messagesPlies: List<String>,
        etiquettesPlies: List<String?>,
        requete: RequeteRecherche,
        estAnnule: () -> Boolean = { false },
    ): ResultatRechercheSorties {
        if (requete.estVide) {
            return ResultatRechercheSorties(emptyList())
        }
        return when {
            requete.regex -> chercherRegex(lignes, requete, estAnnule)

            cheminRapide(requete) ->
                chercherRapide(messagesPlies, etiquettesPlies, requete, estAnnule)

            else -> chercherPlie(lignes, requete, estAnnule)
        }
    }

    /** Le chemin rapide s'applique : sous-chaîne, options par défaut. */
    private fun cheminRapide(requete: RequeteRecherche): Boolean = !requete.regex &&
        !requete.motEntier &&
        !requete.sensibleCasse &&
        !requete.sensibleAccents

    /**
     * Chemin rapide : scan du cache de pliage (aiguille pliée UNE fois) —
     * sous-chaîne sur le message OU l'étiquette, même testeur que
     * [FiltreSorties.textePliePasse].
     */
    private fun chercherRapide(
        messagesPlies: List<String>,
        etiquettesPlies: List<String?>,
        requete: RequeteRecherche,
        estAnnule: () -> Boolean,
    ): ResultatRechercheSorties {
        val aiguille = PliageTexte.plier(
            requete.texte,
            sensibleCasse = false,
            sensibleAccents = false,
        )
        return balayer(messagesPlies.size, estAnnule) { indice ->
            messagesPlies[indice].contains(aiguille) ||
                etiquettesPlies[indice]?.contains(aiguille) == true
        }
    }

    /**
     * Chemin général plié (sous-chaîne ou mot entier avec options) :
     * l'aiguille est pliée selon les options, le motif « mot entier » est
     * compilé avec les mêmes drapeaux que [FiltreSorties.motifMotEntier].
     */
    private fun chercherPlie(
        lignes: List<LigneSortie>,
        requete: RequeteRecherche,
        estAnnule: () -> Boolean,
    ): ResultatRechercheSorties {
        val aiguillePliee = PliageTexte.plier(
            requete.texte,
            requete.sensibleCasse,
            requete.sensibleAccents,
        )
        val testeur: (String) -> Boolean
        if (requete.motEntier) {
            val motif = runCatching {
                Pattern.compile(
                    "(?<![\\p{L}\\p{Nd}])" + Pattern.quote(aiguillePliee) + "(?![\\p{L}\\p{Nd}])",
                    drapeauxDe(requete.sensibleCasse),
                )
            }.getOrNull()
            if (motif == null) {
                return ResultatRechercheSorties(emptyList(), motifInvalide = true)
            }
            testeur = { plie -> motif.matcher(plie).find() }
        } else {
            testeur = { plie -> plie.contains(aiguillePliee) }
        }
        return balayer(lignes.size, estAnnule) { indice ->
            val ligne = lignes[indice]
            val etiquette = ligne.etiquette
            testeur(
                PliageTexte.plier(ligne.message, requete.sensibleCasse, requete.sensibleAccents),
            ) || (
                etiquette != null && testeur(
                    PliageTexte.plier(etiquette, requete.sensibleCasse, requete.sensibleAccents),
                )
                )
        }
    }

    /**
     * Chemin regex : motif compilé sur le message SEUL (parité
     * [FiltreSorties] — plier un motif changerait sa sémantique), repli
     * signalé sans exception.
     */
    private fun chercherRegex(
        lignes: List<LigneSortie>,
        requete: RequeteRecherche,
        estAnnule: () -> Boolean,
    ): ResultatRechercheSorties {
        val motif = runCatching {
            Pattern.compile(requete.texte, drapeauxDe(requete.sensibleCasse))
        }.getOrNull()
        if (motif == null) {
            return ResultatRechercheSorties(emptyList(), motifInvalide = true)
        }
        return balayer(lignes.size, estAnnule) { indice ->
            motif.matcher(lignes[indice].message).find()
        }
    }

    /**
     * Balayage commun : annulation interrogée tous les [PAS_ANNULATION]
     * éléments, résultat partiel livré avec [ResultatRechercheSorties.interrompue]
     * (jamais de faux négatif silencieux).
     */
    private inline fun balayer(
        nbElements: Int,
        estAnnule: () -> Boolean,
        teste: (Int) -> Boolean,
    ): ResultatRechercheSorties {
        val correspondances = ArrayList<Int>()
        for (indice in 0 until nbElements) {
            if (indice % PAS_ANNULATION == 0 && estAnnule()) {
                return ResultatRechercheSorties(correspondances, interrompue = true)
            }
            if (teste(indice)) {
                correspondances.add(indice)
            }
        }
        return ResultatRechercheSorties(correspondances)
    }

    /** Drapeaux de compilation selon l'option de casse (parité
     *  [FiltreSorties.drapeauxRegex]). */
    private fun drapeauxDe(sensibleCasse: Boolean): Int = if (sensibleCasse) {
        0
    } else {
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    }

    /** Fréquence du sondage d'annulation (lignes) — assez grande pour ne
     *  pas plomber le balayage, assez petite pour rester réactive. */
    private const val PAS_ANNULATION = 2048
}
