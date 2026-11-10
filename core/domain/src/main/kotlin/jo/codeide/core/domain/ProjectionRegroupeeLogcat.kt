package jo.codeide.core.domain

/**
 * UNE rangée de la vue REGROUPÉE du Logcat (spec SORTIES § 5.4 M5, lot
 * L5) — soit une ligne de tampon, soit l'en-tête SYNTHÉTIQUE d'un groupe
 * (run ×N ou trace repliée — jamais une ligne : le tampon reste unique,
 * l'en-tête est une dérivation de l'index, ADR 0078). L'indice de groupe
 * porte le repli ; la liste entière est re-dérivée à chaque bascule.
 */
public sealed interface RangeeRegroupeeLogcat {

    /** Une ligne du tampon, à sa position (l'index de projection). */
    public data class Ligne(public val position: Int) : RangeeRegroupeeLogcat

    /**
     * L'en-tête d'un groupe (répétition ×N ou trace repliée).
     *
     * @property indiceGroupe indice du groupe dans l'état dérivé
     *           ([EtatRegroupementLogcat.groupes]) — la clé du repli.
     * @property groupe le groupe dérivé (bornes, compteurs).
     * @property position la POSITION de la ligne REPRÉSENTÉE par
     *           l'en-tête — la PREMIÈRE visible du groupe (jamais une
     *           ligne masquée par le filtre : l'en-tête dit la vérité du
     *           contenu affiché). Cette ligne n'est PAS ré-émise
     *           (jamais deux fois — critère § 10.1.1).
     * @property lignesVisibles lignes VISIBLES du groupe (après filtre) —
     *           le compteur honnête du badge « ×N » (les masquées restent
     *           comptées par le bandeau du filtre, spec SORTIES § 3.5).
     * @property causesVisibles causes VISIBLES restées affichées à repli
     *           fermé (trace seule — zéro pour une répétition).
     */
    public data class EnteteLogcat(
        public val indiceGroupe: Int,
        public val groupe: GroupeLogcat,
        public val position: Int,
        public val lignesVisibles: Int,
        public val causesVisibles: Int,
    ) : RangeeRegroupeeLogcat
}

/**
 * La projection REGROUPÉE du Logcat (lot L5) — assemble la liste unique
 * affichée depuis la dérivation [RegroupementLogcat] et l'index de
 * projection FILTRÉ (seules les lignes admises par le filtre paraissent).
 * Discipline d'assemblage (le pendant Logcat de [ProjectionRegroupee]) :
 *
 * 1. un groupe VISIBLE annonce son en-tête devant sa première ligne
 *    visible — l'en-tête la REPRÉSENTE (jamais ré-émise) ;
 * 2. un groupe REPLIÉ ne montre que son en-tête — SAUF les CAUSES d'une
 *    trace (le « Caused by » reste affiché, spec § 5.4 : le repli cache
 *    les cadres, jamais le diagnostic) ;
 * 3. un groupe SANS ligne visible n'existe pas (jamais un en-tête qui
 *    promet du contenu invisible — règle 14) ;
 * 4. tout le reste reste CHRONOLOGIQUE.
 *
 * Pur, sans état, sans allocation de ligne : la projection porte des
 * POSITIONS. Une passe monotone sur l'index filtré (groupes séquentiels
 * et disjoints) : O(n + g).
 */
public object ProjectionRegroupeeLogcat {

    /**
     * Assemble la liste regroupée du Logcat.
     *
     * @param visibles positions de tampon admises par le filtre
     *        (croissantes — sortie de [ProjectionSorties]).
     * @param regroupement dérivation [RegroupementLogcat.deriver] — elle
     *        porte TOUTES les informations d'assemblage (bornes, causes),
     *        l'assemblage ne re-analyse aucun texte.
     * @param replis indices de groupes REPLIÉS (ensemble vide = tout
     *        déplié).
     */
    public fun deriver(
        visibles: List<Int>,
        regroupement: EtatRegroupementLogcat,
        replis: Set<Int>,
    ): List<RangeeRegroupeeLogcat> {
        val groupes = regroupement.groupes
        val causesParGroupe = HashMap<Int, Set<Int>>(groupes.size)
        groupes.forEachIndexed { indice, groupe ->
            if (groupe is GroupeLogcat.Trace) {
                causesParGroupe[indice] = groupe.positionsCauses.toHashSet()
            }
        }
        val liste = ArrayList<RangeeRegroupeeLogcat>(visibles.size)
        var curseur = 0
        var enteteEmise = false
        visibles.forEachIndexed { indiceVisible, position ->
            // Les groupes dépassés quittent le curseur (l'en-tête aussi).
            while (curseur < groupes.size && groupes[curseur].fin < position) {
                curseur++
                enteteEmise = false
            }
            val courant = groupes.getOrNull(curseur)
            val dansGroupe = courant != null && position >= courant.debut
            // L'en-tête s'émet devant la PREMIÈRE visible du groupe et la
            // REPRÉSENTE (jamais ré-émise — critère § 10.1.1).
            var representee = false
            if (dansGroupe && !enteteEmise) {
                enteteEmise = true
                representee = true
                liste.add(
                    RangeeRegroupeeLogcat.EnteteLogcat(
                        indiceGroupe = curseur,
                        groupe = courant!!,
                        position = position,
                        lignesVisibles = compterVisibles(visibles, indiceVisible, courant.fin),
                        causesVisibles = compterCausesVisibles(
                            visibles,
                            indiceVisible,
                            courant.fin,
                            causesParGroupe[curseur],
                        ),
                    ),
                )
            }
            val replie = dansGroupe && replis.contains(curseur)
            // À repli fermé, seules les CAUSES d'une trace accompagnent
            // encore l'en-tête (les cadres disparaissent de CETTE
            // projection — jamais du tampon).
            val causeVisibleRepliee = replie &&
                causesParGroupe[curseur]?.contains(position) == true
            if (!representee && (!replie || causeVisibleRepliee)) {
                liste.add(RangeeRegroupeeLogcat.Ligne(position))
            }
        }
        return liste
    }

    /** Compte les lignes visibles d'un groupe (scan borné par sa borne
     *  finale — les groupes sont séquentiels). */
    private fun compterVisibles(visibles: List<Int>, depart: Int, borneFin: Int): Int {
        var compteur = 0
        var i = depart
        while (i < visibles.size && visibles[i] <= borneFin) {
            compteur++
            i++
        }
        return compteur
    }

    /** Compte les CAUSES visibles d'une trace (mêmes bornes). */
    private fun compterCausesVisibles(visibles: List<Int>, depart: Int, borneFin: Int, causes: Set<Int>?): Int {
        if (causes.isNullOrEmpty()) return 0
        var compteur = 0
        var i = depart
        while (i < visibles.size && visibles[i] <= borneFin) {
            if (visibles[i] in causes) compteur++
            i++
        }
        return compteur
    }
}
