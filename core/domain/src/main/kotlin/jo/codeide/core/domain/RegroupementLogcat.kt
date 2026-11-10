package jo.codeide.core.domain

import java.util.regex.Pattern

/**
 * UNE trace d'exception du Logcat (spec SORTIES § 5.4 M5) — les ancres
 * pures qui reconnaissent les trois rôles d'une trace imprimée ligne par
 * ligne par le pont : la TÊTE (déclaration d'exception), les CADRES
 * (`at …(Fichier.kt:12)`, le vocabulaire de [LignesPile]) et les CAUSES
 * enchaînées (« Caused by: », « Suppressed: »).
 *
 * Défensif comme [LignesPile] : le message vient d'une application
 * externe, aucune regex ne lève d'exception ici. Les ancres sont
 * volontairement CONSERVATRICES : une tête non reconnue reste une ligne
 * isolée (dégradation honnête — jamais repliée), un faux positif ne fait
 * que replier un bloc qui se déplie d'un geste.
 */
public object TracesLogcat {

    /** Tête : classe qualifiée finissant par Exception/Error, message
     *  optionnel (« java.lang.RuntimeException: boom »). */
    private val TETE: Pattern = Pattern.compile(
        "^(?:[\\w$]+\\.)*[\\w$]+(?:Exception|Error)(?::.*)?$",
    )

    /** Fin de pile relative (« … 4 more » — imprimée après une cause). */
    private val RELATIF: Pattern = Pattern.compile("^\\.{2,3}\\s*\\d+\\s+more$")

    /** Ce message déclare-t-il une exception (tête de trace repliable) ? */
    public fun estTeteTrace(message: String): Boolean {
        val plat = message.trim()
        return plat.startsWith(LignesPile.PREFIXE_EXCEPTION) ||
            plat.startsWith(PREFIXE_FATAL) ||
            TETE.matcher(plat).matches()
    }

    /** Ce message est-il un cadre de pile (le corps replié d'une trace) ? */
    public fun estCadre(message: String): Boolean = LignesPile.estCadrePile(message)

    /** Ce message enchaîne-t-il une cause ou une suppression (visible
     *  MÊME trace repliée : le « Caused by » est l'information) ? */
    public fun estCause(message: String): Boolean {
        val plat = message.trim()
        return plat.startsWith(CAUSE) || plat.startsWith(SUPPRISEE) || RELATIF.matcher(plat).matches()
    }

    /** Ce message appartient-il au corps d'une trace (cadre ou cause) ? */
    public fun estLigneTrace(message: String): Boolean = estCadre(message) || estCause(message)

    private const val PREFIXE_FATAL = "FATAL EXCEPTION"
    private const val CAUSE = "Caused by: "
    private const val SUPPRISEE = "Suppressed: "
}

/**
 * UN groupe de la dérivation REGROUPÉE du Logcat (spec SORTIES § 5.4 M5,
 * lot L5) — soit une RUN de lignes identiques répétées (« ×37 »), soit
 * une trace d'exception repliée sous sa tête. Les bornes sont des
 * POSITIONS de tampon (inclusives) ; les groupes sont disjoints et
 * séquentiels (construits de gauche à droite) — chaque ligne de l'entrée
 * se retrouve EXACTEMENT UNE fois dans [EtatRegroupementLogcat]
 * (critère § 10.1.1, même contrat que le regroupement Gradle).
 */
public sealed interface GroupeLogcat {

    /** Première position de tampon du groupe (inclusive). */
    public val debut: Int

    /** Dernière position de tampon du groupe (inclusive). */
    public val fin: Int

    /**
     * Une run de lignes IDENTIQUES répétées (même message, même étiquette,
     * même niveau) — l'affichage replié montre UNE rangée « ×N ».
     *
     * @property repetitions nombre de lignes de la run (≥ 2 — une ligne
     *           isolée n'est pas un groupe).
     */
    public data class Repetition(override val debut: Int, override val fin: Int, public val repetitions: Int) :
        GroupeLogcat

    /**
     * Une trace d'exception repliée : la tête reste affichée (le contenu
     * de l'en-tête), les cadres se replient, les CAUSES restent visibles
     * MÊME repliée (le « Caused by » porte le diagnostic — spec § 5.4).
     *
     * @property positionsCauses positions de tampon des lignes de cause
     *           (« Caused by » / « Suppressed » / fin relative) DANS le
     *           groupe — la projection les garde visibles à repli fermé.
     */
    public data class Trace(override val debut: Int, override val fin: Int, public val positionsCauses: List<Int>) :
        GroupeLogcat
}

/**
 * L'état dérivé du regroupement Logcat — les groupes (dans l'ordre du
 * flux) et les positions hors groupe (lignes isolées). Disjoints et
 * couvrants : la projection regroupée n'a qu'à assembler.
 *
 * @property groupes les runs répétées et les traces repliables, dans
 *           l'ordre du flux.
 * @property horsGroupes positions des lignes sans groupe (la majorité du
 *           flux vivant).
 */
public data class EtatRegroupementLogcat(public val groupes: List<GroupeLogcat>, public val horsGroupes: List<Int>)

/**
 * La dérivation REGROUPÉE du Logcat (spec SORTIES § 5.4 M5, lot L5) — le
 * MÊME patron que [RegroupementSorties] (une passe, aucune re-analyse
 * par ligne) mais le VOCABULAIRE de sa famille : le Logcat n'a ni tâche
 * ni verdict ; ses groupes sont les répétitions ×N et les traces
 * repliables. Classes PROPRES (le fichier du regroupement Gradle reste
 * Gradle-spécifique) ; le tampon reste UNIQUE, cette dérivation n'est
 * qu'une vue.
 *
 * Règles (documentées, testées) :
 * 1. la RÉPÉTITION prime : une run de ≥ 2 lignes identiques (message,
 *    étiquette, niveau) forme un groupe ×N — même au milieu d'une trace
 *    (le spam « même ligne » est le cas d'usage du ×N) ;
 * 2. une TRACE s'ouvre sur une tête SUIVIE d'au moins une ligne de trace
 *    et s'étend aux cadres et causes consécutifs — une ligne de trace
 *    sans tête au-dessus reste isolée (jamais repliée sans raison) ;
 * 3. tout le reste est hors groupe.
 */
public object RegroupementLogcat {

    /** Taille minimale d'une run répétée (deux occurrences suffisent). */
    public const val REPETITION_MIN: Int = 2

    /**
     * Dérive la vue regroupée d'une fenêtre de lignes.
     *
     * @param lignes lignes de la fenêtre (état figé du tampon), de la
     *        plus ancienne à la plus récente.
     */
    public fun deriver(lignes: List<LigneSortie>): EtatRegroupementLogcat {
        val groupes = ArrayList<GroupeLogcat>()
        val horsGroupes = ArrayList<Int>()
        var position = 0
        while (position < lignes.size) {
            position = consommerGroupe(lignes, position, groupes, horsGroupes)
        }
        return EtatRegroupementLogcat(groupes = groupes, horsGroupes = horsGroupes)
    }

    /** Consomme LE groupe qui commence à [position] (répétition, trace
     *  ou ligne isolée) et rend la position SUIVANTE — un groupe par
     *  appel, aucun saut dans la boucle du lecteur. */
    private fun consommerGroupe(
        lignes: List<LigneSortie>,
        position: Int,
        groupes: MutableList<GroupeLogcat>,
        horsGroupes: MutableList<Int>,
    ): Int {
        val finRun = finRepetition(lignes, position)
        val finTrace = finTraceSiTeteSuivie(lignes, position)
        val groupe: GroupeLogcat? = when {
            finRun - position + 1 >= REPETITION_MIN ->
                GroupeLogcat.Repetition(position, finRun, finRun - position + 1)

            finTrace != null ->
                GroupeLogcat.Trace(position, finTrace, causesEntre(lignes, position + 1, finTrace))

            else -> null
        }
        return when (groupe) {
            null -> {
                horsGroupes.add(position)
                position + 1
            }

            else -> {
                groupes.add(groupe)
                groupe.fin + 1
            }
        }
    }

    /** La fin d'une trace si la ligne [position] EN EST une tête suivie
     *  d'au moins un cadre ou une cause, `null` sinon. */
    private fun finTraceSiTeteSuivie(lignes: List<LigneSortie>, position: Int): Int? {
        val suitTrace = position + 1 < lignes.size &&
            TracesLogcat.estLigneTrace(lignes[position + 1].message)
        if (!TracesLogcat.estTeteTrace(lignes[position].message) || !suitTrace) return null
        return finTrace(lignes, position)
    }

    /** Fin de la run de répétition ouverte à [debut] (incluse). */
    private fun finRepetition(lignes: List<LigneSortie>, debut: Int): Int {
        val reference = lignes[debut]
        var fin = debut
        while (fin + 1 < lignes.size && identiques(reference, lignes[fin + 1])) {
            fin++
        }
        return fin
    }

    /** Deux lignes sont-elles « identiques répétées » (le contenu rendu
     *  ET l'étiquette ET le niveau — l'horodatage ne compte pas) ? */
    private fun identiques(a: LigneSortie, b: LigneSortie): Boolean =
        a.message == b.message && a.etiquette == b.etiquette && a.niveau == b.niveau

    /** Fin de la trace ouverte à [debut] (la tête) — s'étend aux cadres
     *  et causes consécutifs (une répétition ENCORE à l'intérieur reste
     *  dans la trace : la trace prime sur son propre corps, la règle 1
     *  ne s'applique qu'AU-DESSUS de la tête). */
    private fun finTrace(lignes: List<LigneSortie>, debut: Int): Int {
        var fin = debut
        var curseur = debut + 1
        while (curseur < lignes.size && TracesLogcat.estLigneTrace(lignes[curseur].message)) {
            fin = curseur
            curseur++
        }
        return fin
    }

    /** Les positions de CAUSE d'une trace (tête exclue — entre les bornes
     *  passées). */
    private fun causesEntre(lignes: List<LigneSortie>, debut: Int, fin: Int): List<Int> {
        val positions = ArrayList<Int>()
        for (position in debut..fin) {
            if (TracesLogcat.estCause(lignes[position].message)) positions.add(position)
        }
        return positions
    }
}
