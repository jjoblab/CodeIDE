package jo.codeide.core.domain

/**
 * UNE rangée de la vue REGROUPÉE (spec SORTIES § 4.2, lot L3) — soit une
 * ligne de tampon (rattachée à son groupe), soit l'en-tête synthétique
 * d'un groupe (jamais une ligne : le tampon reste unique, l'en-tête est
 * une dérivation). L'indice de groupe porte le repli ; la liste entière
 * est re-dérivée à chaque bascule de repli (S5 du banc : « une seule
 * liste re-projetée », ADR 0078).
 */
public sealed interface RangeeRegroupee {

    /** Une ligne du tampon, à sa position (l'index de projection). */
    public data class Ligne(public val position: Int) : RangeeRegroupee

    /**
     * L'en-tête d'une rangée de tâche.
     *
     * @property indiceGroupe indice du groupe dans l'état dérivé
     *           ([EtatRegroupement.groupes]) — la clé du repli.
     * @property groupe le groupe dérivé (chemin, statut, durée, bornes).
     * @property lignesVisibles lignes VISIBLES du groupe (après filtre) —
     *           le compteur honnête de l'en-tête (les masquées restent
     *           comptées par le filtre, spec SORTIES § 3.5).
     */
    public data class EnteteTache(
        public val indiceGroupe: Int,
        public val groupe: GroupeTache,
        public val lignesVisibles: Int,
    ) : RangeeRegroupee
}

/**
 * La projection REGROUPÉE du lecteur (lot L3) — assemble la liste unique
 * affichée depuis la dérivation [RegroupementSorties] et l'index de
 * projection FILTRÉ ([ProjectionSorties] : seules les lignes admises par
 * le filtre apparaissent). Discipline d'assemblage :
 *
 * 1. le **bloc d'échec** est épinglé EN TÊTE (spec SORTIES § 4.3) — ses
 *    positions quittent leur place chronologique SANS être dupliquées ;
 * 2. le reste reste CHRONOLOGIQUE — chaque groupe annonce son en-tête
 *    devant sa première ligne visible ;
 * 3. un groupe REPLIÉ ne montre que son en-tête (ses lignes visibles
 *    sont omises de CETTE projection — jamais du tampon) ;
 * 4. un groupe SANS ligne visible n'existe pas (jamais un en-tête qui
 *    promet du contenu invisible — règle 14).
 *
 * Pur, sans état, sans allocation de ligne : la projection porte des
 * POSITIONS, jamais des copies du contenu. Une passe monotone sur
 * l'index filtré (les groupes sont séquentiels et disjoints) : O(n + g).
 */
public object ProjectionRegroupee {

    /**
     * Assemble la liste regroupée.
     *
     * @param visibles positions de tampon admises par le filtre
     *        (croissantes — sortie de [ProjectionSorties]).
     * @param regroupement dérivation [RegroupementSorties.deriver] — elle
     *        porte TOUTES les informations d'assemblage (bornes de
     *        groupes, bloc d'échec), l'assemblage ne re-analyse aucun
     *        texte.
     * @param replis indices de groupes REPLIÉS (ensemble vide = tout
     *        déplié).
     */
    // Exemption detekt ciblée (règle 16) : la complexité est celle de la
    // STRUCTURE elle-même (lignes hors groupes, en-têtes, bloc d'échec
    // épinglé, replis) — une ramification par branche du contrat ADR 0078,
    // jamais deux branches pour la même décision.
    @Suppress("CyclomaticComplexMethod")
    public fun deriver(visibles: List<Int>, regroupement: EtatRegroupement, replis: Set<Int>): List<RangeeRegroupee> {
        val bloc = regroupement.blocEchec.toHashSet()
        val groupes = regroupement.groupes.withIndex().sortedBy { (_, groupe) -> groupe.debut }
        val liste = ArrayList<RangeeRegroupee>(visibles.size)

        // 1. Bloc d'échec épinglé (positions admises par le filtre).
        regroupement.blocEchec.forEach { position ->
            if (visibles.contains(position)) liste.add(RangeeRegroupee.Ligne(position))
        }

        // 2. Le reste, chronologique (lignes de bloc exclues — déjà épinglées).
        var curseur = 0
        var enteteEmise = false
        visibles.forEachIndexed { indiceVisible, position ->
            if (bloc.contains(position)) return@forEachIndexed
            // Les groupes dépassés quittent le curseur (l'en-tête aussi).
            while (curseur < groupes.size && groupes[curseur].value.fin < position) {
                curseur++
                enteteEmise = false
            }
            val courant = groupes.getOrNull(curseur)
            val dansGroupe = courant != null && position >= courant.value.debut
            if (dansGroupe && !enteteEmise) {
                enteteEmise = true
                val (indice, groupe) = courant!!
                // Le compteur honnête : les lignes AFFICHÉES sous
                // l'en-tête — la ligne d'en-tête elle-même (remplacée par
                // la rangée d'en-tête quand l'ancre texte l'a ouverte)
                // n'est pas comptée une seconde fois (règle 14 : jamais
                // deux fois).
                val visiblesGroupe = compterVisibles(visibles, indiceVisible, groupe.fin) -
                    (if (groupe.statutTexte != null && visibles.contains(groupe.debut)) 1 else 0)
                if (visiblesGroupe > 0) {
                    liste.add(
                        RangeeRegroupee.EnteteTache(
                            indiceGroupe = indice,
                            groupe = groupe,
                            lignesVisibles = visiblesGroupe,
                        ),
                    )
                }
            }
            val replie = dansGroupe && replis.contains(courant!!.index)
            // La ligne d'EN-TÊTE ouverte par l'ancre texte n'est PAS
            // ré-ajoutée comme Ligne : la rangée d'en-tête LA REPRÉSENTE
            // (jamais deux fois — critère § 10.1.1).
            val estLaLigneEntete = dansGroupe &&
                position == courant.value.debut &&
                courant.value.statutTexte != null
            if (!replie && !estLaLigneEntete) {
                liste.add(RangeeRegroupee.Ligne(position))
            }
        }
        return liste
    }

    /**
     * Compte les lignes visibles d'un groupe depuis la première position
     * visible connue (scan borné par la borne finale du groupe).
     */
    private fun compterVisibles(visibles: List<Int>, depart: Int, borneFin: Int): Int {
        var compteur = 0
        var i = depart
        while (i < visibles.size && visibles[i] <= borneFin) {
            compteur++
            i++
        }
        return compteur
    }
}
