package jo.codeide.core.domain

/**
 * Un lot ingéré par [TamponSorties] — tout ce que la dérivation
 * incrémentale ([ProjectionSorties.apresLot]) doit savoir du chemin chaud :
 * l'état du tampon APRÈS lot et la troncature de tête qui l'a formé.
 *
 * @property troncatureTete lignes tombées de la tête par le lot (retour
 *           de [TamponSorties.ajouterLot]).
 * @property tailleAvant taille du tampon AVANT le lot.
 * @property etat état du tampon APRÈS le lot (lignes et pliés alignés).
 */
public data class LotTampon(
    public val troncatureTete: Int,
    public val tailleAvant: Int,
    public val etat: EtatTamponSorties,
) {
    /** Indice du PREMIER ligne NOUVELLE dans l'état après lot — les
     *  indices < [depart] sont déjà projetés. Un lot plus GRAND que la
     *  fenêtre d'un tampon vide (première rafale, réforme de fenêtre du
     *  lot L6) fait tomber plus de tête qu'il n'y avait de lignes
     *  projetées : le départ ne peut pas être négatif — TOUTES les
     *  lignes retenues sont nouvelles. */
    public val depart: Int
        get() = (tailleAvant - troncatureTete).coerceAtLeast(0)
}

/**
 * Une dérivation de projection : les indices VISIBLES (lignes admises par
 * le filtre) et le drapeau d'honnêteté.
 *
 * @property visibles positions des lignes à afficher, croissantes, dans
 *           l'état du tampon au moment de la dérivation.
 * @property interrompue `true` quand la dérivation a été annulée en
 *           cours — la projection partielle est livrée telle quelle et
 *           l'appelant redérive (jamais de liste faussement complète).
 */
public data class EtatProjection(public val visibles: List<Int>, public val interrompue: Boolean = false)

/**
 * L'index de projection du lecteur (ADR 0159 décision 2 : « un index de
 * projection brut/regroupé — le tampon est unique, la projection est une
 * dérivation bornée », lot L2 : la dérivation FILTRÉE). Les lignes
 * masquées RESTENT dans le tampon et un compteur les rend visibles
 * (spec SORTIES § 3.5) — la projection est une liste d'INDICES, jamais
 * une copie du contenu. La projection regroupée Gradle (L3) dérivera du
 * même tampon — jamais une seconde source de vérité (ADR 0078).
 *
 * Pur JVM, annulable, deux chemins de MÊME sémantique que
 * [FiltreSorties.accepte] (l'équivalence est testée) :
 * - **rapide** : la dimension texte lit le pliage EN CACHE du tampon
 *   (sous-chaîne par défaut — une allocation d'aiguille au total), les
 *   autres dimensions passent par un filtre allégé `copy(texte = "")` ;
 * - **général** : [FiltreSorties.accepte] teste chaque ligne entière
 *   (regex, mot entier, options de casse/accents).
 */
public object ProjectionSorties {

    /**
     * Dérivation ENTIÈRE — changement de filtre, reprise de pause,
     * redémarrage de vue : chaque ligne du tampon est testée.
     *
     * @param lignes lignes du tampon (état figé par l'appelant).
     * @param messagesPlies cache de pliage des messages (aligné).
     * @param etiquettesPlies cache de pliage des étiquettes (aligné).
     * @param filtre filtre unifié courant (ADR 0160).
     * @param estAnnule sondage d'annulation — le worker du lecteur y
     *           branche l'état vivant de sa coroutine.
     */
    public fun deriver(
        lignes: List<LigneSortie>,
        messagesPlies: List<String>,
        etiquettesPlies: List<String?>,
        filtre: FiltreSorties,
        estAnnule: () -> Boolean = { false },
    ): EtatProjection {
        val testeur = testeurFiltre(filtre, messagesPlies, etiquettesPlies)
        val visibles = ArrayList<Int>()
        for (indice in lignes.indices) {
            if (indice % PAS_ANNULATION == 0 && estAnnule()) {
                return EtatProjection(visibles, interrompue = true)
            }
            if (testeur(indice, lignes[indice])) {
                visibles.add(indice)
            }
        }
        return EtatProjection(visibles)
    }

    /**
     * Dérivation INCRÉMENTALE après un lot — le chemin chaud d'ingestion
     * (héritage ADR 0074 : par lot, JAMAIS de re-scan complet par lot).
     * Les lignes déjà projetées gardent leur verdict : la troncature de
     * tête les DÉCALE (leurs indices glissent, les tombées sont
     * abandonnées), seules les lignes nouvelles ([LotTampon.depart]) sont
     * testées et appendues.
     *
     * @param visiblesConnus projection avant le lot (indices AVANT
     *           décalage), croissants.
     * @param lot le lot ingéré (état après lot + troncature de tête).
     * @param filtre filtre unifié courant.
     * @param estAnnule sondage d'annulation.
     */
    public fun apresLot(
        visiblesConnus: List<Int>,
        lot: LotTampon,
        filtre: FiltreSorties,
        estAnnule: () -> Boolean = { false },
    ): EtatProjection {
        val visibles = ArrayList<Int>(visiblesConnus.size + CAPACITE_INCREMENT)
        for (indice in visiblesConnus) {
            val decale = indice - lot.troncatureTete
            if (decale >= 0) {
                visibles.add(decale)
            }
        }
        val testeur = testeurFiltre(filtre, lot.etat.messagesPlies, lot.etat.etiquettesPlies)
        for (indice in lot.depart until lot.etat.lignes.size) {
            if (indice % PAS_ANNULATION == 0 && estAnnule()) {
                return EtatProjection(visibles, interrompue = true)
            }
            if (testeur(indice, lot.etat.lignes[indice])) {
                visibles.add(indice)
            }
        }
        return EtatProjection(visibles)
    }

    /**
     * Le testeur d'une ligne : chemin rapide (pliage en cache + filtre
     * allégé) quand la dimension texte est une sous-chaîne aux options
     * par défaut, [FiltreSorties.accepte] sinon — MÊME verdict garanti
     * (testé).
     */
    private fun testeurFiltre(
        filtre: FiltreSorties,
        messagesPlies: List<String>,
        etiquettesPlies: List<String?>,
    ): (Int, LigneSortie) -> Boolean {
        val rapide = filtre.texte.isNotEmpty() &&
            !filtre.regex &&
            !filtre.motEntier &&
            !filtre.sensibleCasse &&
            !filtre.sensibleAccents
        if (rapide) {
            val filtreSansTexte = filtre.copy(texte = "")
            val aiguille = PliageTexte.plier(
                filtre.texte,
                sensibleCasse = false,
                sensibleAccents = false,
            )
            return { indice, ligne ->
                filtreSansTexte.accepte(ligne) &&
                    (
                        messagesPlies[indice].contains(aiguille) ||
                            etiquettesPlies[indice]?.contains(aiguille) == true
                        )
            }
        }
        return { _, ligne -> filtre.accepte(ligne) }
    }

    /** Fréquence du sondage d'annulation (lignes) — même discipline que
     *  [RechercheSorties]. */
    private const val PAS_ANNULATION = 2048

    /** Capacité d'appoint d'une liste de projection (lignes visibles
     *  supplémentaires attendues par lot). */
    private const val CAPACITE_INCREMENT = 16
}
