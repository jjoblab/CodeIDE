package jo.codeide.core.domain

/**
 * Diff unifié ligne à ligne (mission « Historique local » H2, spec
 * HISTORIQUE_LOCAL.md § 5) — pur et BORNÉ : l'afficheur ne diffusera
 * jamais un résultat illimité, et le calcul ne creusera jamais une
 * matrice N×M (les fichiers font jusqu'à 2 Mo de texte).
 *
 * Algorithme : rognage du préfixe/suffixe communs (le cas courant —
 * petite édition au milieu d'un fichier), puis Myers O((N+M)·D) sur
 * le cœur ; si les éditions dépassent [BORNE_EDITIONS], repli HONNÊTE
 * (tout l'ancien en retraits, tout le nouveau en ajouts — lisible,
 * jamais un faux diff par explosion).
 *
 * Partagé par l'historique local (H2) et la future vue de diff Git.
 */
public object DiffUnifie {
    /** Éditions maximales avant le repli honnête (borné, mémoire O(N+M)). */
    public const val BORNE_EDITIONS: Int = 4096

    /**
     * Calcule le diff ligne à ligne de [ancien] vers [nouveau].
     *
     * @param ancien lignes de l'état AVANT (révision consultée).
     * @param nouveau lignes de l'état APRÈS (contenu actuel ou révision
     *        suivante).
     * @return les lignes du diff unifié, dans l'ordre.
     */
    public fun calculer(
        ancien: List<String>,
        nouveau: List<String>,
    ): List<LigneDiff> {
        // 1-2. Rognage du préfixe et du suffixe communs (borne le cœur).
        val bornes = rognerCommuns(ancien, nouveau)
        val coeurAncien = ancien.subList(bornes.debut, bornes.finAncien)
        val coeurNouveau = nouveau.subList(bornes.debut, bornes.finNouveau)

        val lignes = ArrayList<LigneDiff>(ancien.size + nouveau.size)
        // 3. Préfixe inchangé.
        for (i in 0 until bornes.debut) {
            lignes.add(LigneDiff(TypeLigneDiff.INCHANGE, ancien[i]))
        }
        // 4. Cœur : Myers borné, ou repli honnête.
        ajouterCoeur(coeurAncien, coeurNouveau, lignes)
        // 5. Suffixe inchangé.
        for (i in bornes.finAncien until ancien.size) {
            lignes.add(LigneDiff(TypeLigneDiff.INCHANGE, ancien[i]))
        }
        return lignes
    }

    /** Bornes du cœur après rognage des extrémités communes. */
    private data class Bornes(
        val debut: Int,
        val finAncien: Int,
        val finNouveau: Int,
    )

    /** Rognage du préfixe puis du suffixe communs (le cas courant :
     *  petite édition au milieu d'un fichier — le cœur devient minuscule). */
    private fun rognerCommuns(
        ancien: List<String>,
        nouveau: List<String>,
    ): Bornes {
        var debut = 0
        while (debut < ancien.size && debut < nouveau.size && ancien[debut] == nouveau[debut]) {
            debut++
        }
        var finAncien = ancien.size
        var finNouveau = nouveau.size
        while (finAncien > debut && finNouveau > debut && ancien[finAncien - 1] == nouveau[finNouveau - 1]) {
            finAncien--
            finNouveau--
        }
        return Bornes(debut, finAncien, finNouveau)
    }

    /**
     * Ajoute le diff du CŒUR (débarrassé des extrémités communes) :
     * cœur vide d'un côté → pur ajout/retrait ; cœur trop grand pour
     * Myers borné → repli honnête (tout remplacé) ; sinon Myers exact.
     */
    private fun ajouterCoeur(
        coeurAncien: List<String>,
        coeurNouveau: List<String>,
        lignes: MutableList<LigneDiff>,
    ) {
        when {
            coeurAncien.isEmpty() -> {
                coeurNouveau.forEach { lignes.add(LigneDiff(TypeLigneDiff.AJOUT, it)) }
            }

            coeurNouveau.isEmpty() -> {
                coeurAncien.forEach { lignes.add(LigneDiff(TypeLigneDiff.RETRAIT, it)) }
            }

            coeurAncien.size + coeurNouveau.size > 2 * BORNE_EDITIONS -> {
                coeurAncien.forEach { lignes.add(LigneDiff(TypeLigneDiff.RETRAIT, it)) }
                coeurNouveau.forEach { lignes.add(LigneDiff(TypeLigneDiff.AJOUT, it)) }
            }

            else -> {
                traduireEtapes(myers(coeurAncien, coeurNouveau), coeurAncien, coeurNouveau, lignes)
            }
        }
    }

    /** Traduit les étapes du parcours Myers en lignes du diff. */
    private fun traduireEtapes(
        parcours: List<EtapeMyers>,
        coeurAncien: List<String>,
        coeurNouveau: List<String>,
        lignes: MutableList<LigneDiff>,
    ) {
        var a = 0
        var b = 0
        for (etape in parcours) {
            when (etape) {
                EtapeMyers.DESCENDRE -> {
                    lignes.add(LigneDiff(TypeLigneDiff.RETRAIT, coeurAncien[a]))
                    a++
                }

                EtapeMyers.AVANCER -> {
                    lignes.add(LigneDiff(TypeLigneDiff.AJOUT, coeurNouveau[b]))
                    b++
                }

                EtapeMyers.DIAGONALE -> {
                    lignes.add(LigneDiff(TypeLigneDiff.INCHANGE, coeurAncien[a]))
                    a++
                    b++
                }
            }
        }
    }

    /** Une étape du parcours Myers (diagonale = ligne commune). */
    private enum class EtapeMyers {
        DESCENDRE,
        AVANCER,
        DIAGONALE,
    }

    /**
     * Parcours Myers (greedy, D croissant) — retourné en étapes
     * DIAGONALE/DESCENDRE/AVANCER. Le cœur est borné par l'appelant ;
     * la trace est reconstruite en remontant les V (pas de récursion).
     */
    private fun myers(
        ancien: List<String>,
        nouveau: List<String>,
    ): List<EtapeMyers> {
        val avancee = myersAvancer(ancien, nouveau)
        if (avancee.dFinal < 0) {
            // Inatteignable par construction (D = N+M suffit toujours) —
            // repli défensif : tout remplacer.
            return buildList {
                repeat(ancien.size) { add(EtapeMyers.DESCENDRE) }
                repeat(nouveau.size) { add(EtapeMyers.AVANCER) }
            }
        }
        return myersRemonter(avancee, ancien.size, nouveau.size)
    }

    /** État du parcours avant remontée : traces de V et profondeur finale. */
    private class AvanceeMyers(
        val traces: List<IntArray>,
        val dFinal: Int,
        val offset: Int,
    )

    /**
     * Phase ALLER de Myers : profondeur croissante, V[k] = x le plus
     * avancé sur la diagonale k. traces[d] = V APRÈS la profondeur d —
     * la remontée relit V(d-1) = traces[d-1].
     */
    private fun myersAvancer(
        ancien: List<String>,
        nouveau: List<String>,
    ): AvanceeMyers {
        val n = ancien.size
        val m = nouveau.size
        val maxD = n + m
        val offset = maxD
        val v = IntArray(2 * maxD + 1)
        val traces = ArrayList<IntArray>()
        var dFinal = -1
        var profondeur = 0
        while (profondeur <= maxD && dFinal < 0) {
            var k = -profondeur
            while (k <= profondeur) {
                var x =
                    if (k == -profondeur || (k != profondeur && v[k - 1 + offset] < v[k + 1 + offset])) {
                        v[k + 1 + offset]
                    } else {
                        v[k - 1 + offset] + 1
                    }
                var y = x - k
                while (x < n && y < m && ancien[x] == nouveau[y]) {
                    x++
                    y++
                }
                v[k + offset] = x
                if (x >= n && y >= m) {
                    dFinal = profondeur
                    break
                }
                k += 2
            }
            traces.add(v.copyOf())
            profondeur++
        }
        return AvanceeMyers(traces, dFinal, offset)
    }

    /**
     * Phase RETOUR : de la fin (n, m) vers l'origine, chaque profondeur
     * rend d'abord son SERPENT (diagonales), puis son MOUVEMENT —
     * l'inversion finale retrouve l'ordre chronologique.
     */
    private fun myersRemonter(
        avancee: AvanceeMyers,
        n: Int,
        m: Int,
    ): List<EtapeMyers> {
        val traces = avancee.traces
        val offset = avancee.offset
        val etapes = ArrayList<EtapeMyers>(n + m)
        var d = avancee.dFinal
        var x = n
        var y = m
        while (d > 0) {
            val vPrecedent = traces[d - 1]
            val k = x - y
            val depuisKPlusUn: Boolean =
                k == -d || (k != d && vPrecedent[k - 1 + offset] < vPrecedent[k + 1 + offset])
            val kPrecedent = if (depuisKPlusUn) k + 1 else k - 1
            val xPrecedent = vPrecedent[kPrecedent + offset]
            // Le mouvement AMÈNE sur la diagonale k : insert (y++) garde x,
            // retrait (x++) avance x d'une unité depuis le prédécesseur.
            val xMouvement = if (depuisKPlusUn) xPrecedent else xPrecedent + 1
            // 1. Serpent de la profondeur d : (x, y) → (xMouvement, yMouvement).
            while (x > xMouvement) {
                etapes.add(EtapeMyers.DIAGONALE)
                x--
                y--
            }
            // 2. Le mouvement lui-même.
            if (depuisKPlusUn) {
                etapes.add(EtapeMyers.AVANCER)
                y--
            } else {
                etapes.add(EtapeMyers.DESCENDRE)
                x--
            }
            d--
        }
        // Serpent initial (profondeur 0) : de l'origine au premier V[0].
        while (x > 0 && y > 0) {
            etapes.add(EtapeMyers.DIAGONALE)
            x--
            y--
        }
        etapes.reverse()
        return etapes
    }
}

/** Type d'une ligne du diff unifié. */
public enum class TypeLigneDiff {
    /** Ligne commune aux deux états. */
    INCHANGE,

    /** Ligne présente dans le NOUVEAU seulement. */
    AJOUT,

    /** Ligne présente dans l'ANCIEN seulement. */
    RETRAIT,
}

/** Une ligne du diff unifié : son type et son texte (sans marqueur). */
public data class LigneDiff(
    public val type: TypeLigneDiff,
    public val texte: String,
)
