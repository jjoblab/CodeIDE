package jo.codeide.core.domain

/**
 * La dérivation REGROUPÉE du Centre de sorties (spec SORTIES § 4.1–4.3,
 * lot L3) — la projection « une liste, deux projections » du prompt :
 * le tampon reste UNIQUE ([TamponSorties], ADR 0078) et cette dérivation
 * en produit la VUE regroupée : un résumé, des rangées par tâche, le bloc
 * d'échec épinglé, les lignes hors groupe. Chaque ligne de l'entrée se
 * retrouve EXACTEMENT UNE fois dans [EtatRegroupement] (critère
 * § 10.1.1 : aucune ligne n'apparaît deux fois) — sauf le déplacement
 * ASSUMÉ du bloc d'échec, épinglé sous le résumé (spec § 4.3), qui quitte
 * sa place chronologique sans être dupliqué.
 *
 * **Alignement événement + texte** (Q6, spec § 4.2) : la frontière de
 * tâche est posée par le champ `tache` des lignes (posé À L'INGESTION par
 * l'événement `EtatTacheBuild` EN_COURS ou l'annonce texte `> Task :…`,
 * via [ParseurFluxGradle]) ; la dégradation HONNÊTE d'un flux qui ne
 * l'aurait pas posé retombe sur le groupe courant puis sur l'ancre texte
 * [AncresFluxGradle] — les deux voies sont testées. Les statuts et les
 * durées viennent des ÉVÉNEMENTS ([EtatExecutionGradle], fait foi sur le
 * texte) ; l'ancre texte ne les complète que si l'événement n'a rien dit
 * (D1 en attente du dépôt frère).
 */
public object RegroupementSorties {

    /**
     * Dérive la vue regroupée d'une fenêtre de lignes.
     *
     * @param lignes lignes de la fenêtre (état figé du tampon), de la plus
     *        ancienne à la plus récente.
     * @param execution état d'exécution porté par les événements (verdict,
     *        compteurs, statuts/durées par tâche) — `null` quand aucun
     *        événement n'a été observé (relecture d'un flux tiers) : le
     *        texte devient alors la seule source (dégradation honnête).
     */
    public fun deriver(lignes: List<LigneSortie>, execution: EtatExecutionGradle?): EtatRegroupement {
        val constructeur = ConstructeurRegroupement(execution)
        lignes.forEachIndexed { position, ligne -> constructeur.ingere(position, ligne) }
        return constructeur.conclut(lignes.size)
    }
}

/**
 * Le résumé en tête (spec SORTIES § 4.1) — verdict, durée, compteurs de
 * tâches, erreurs, avertissements, volume. Les compteurs viennent des
 * événements ([EtatExecutionGradle.compteurs]) ; `erreurs`/`avertissements`
 * tombent en secours SUR LES LIGNES (gravité portée par les positions
 * cliquables et le niveau) quand aucun événement ne les a comptés.
 *
 * @property verdict verdict de l'exécution (événement, sinon verdict texte).
 * @property dureeMs durée terminale (événement, sinon durée texte).
 * @property compteurs compteurs de tâches (événement, sinon résumé texte).
 * @property erreurs lignes graduées ERREUR (ou ASSERT) de la fenêtre.
 * @property avertissements lignes graduées AVERTISSEMENT de la fenêtre.
 * @property totalLignes lignes présentes dans la fenêtre (volume brut).
 */
public data class ResumeExecution(
    public val verdict: VerdictExecution?,
    public val dureeMs: Long?,
    public val compteurs: CompteursTaches?,
    public val erreurs: Int,
    public val avertissements: Int,
    public val totalLignes: Int,
)

/**
 * UNE rangée de tâche de la vue regroupée (spec SORTIES § 4.2) — chemin,
 * statut (événement fait foi, texte en granularité fine), durée serveur,
 * bornes dans le tampon et compteurs de gravité du contenu du groupe.
 *
 * @property chemin chemin Gradle complet (`:app:compileDebugKotlin`).
 * @property statutTexte statut FIN lu sur l'en-tête texte (`UP-TO-DATE`,
 *           `FROM-CACHE`… — granularité que l'énumération d'événements
 *           n'a pas encore, D1 en attente) ; `null` hors en-tête.
 * @property statutEvenement dernier statut ÉVÉNEMENT de la tâche (fait
 *           foi sur le texte) ; `null` si aucun événement n'a parlé.
 * @property dureeMs durée MESURÉE par l'orchestrateur (événement) —
 *           jamais mesurée côté client.
 * @property debut première position de tampon du groupe (l'en-tête
 *           elle-même, quand l'ancre texte la porte).
 * @property fin dernière position de tampon du groupe (inclusive).
 * @property erreurs lignes de gravité ERREUR dans le groupe.
 * @property avertissements lignes de gravité AVERTISSEMENT dans le groupe.
 */
public data class GroupeTache(
    public val chemin: String,
    public val statutTexte: StatutTacheGradle?,
    public val statutEvenement: StatutTache?,
    public val dureeMs: Long?,
    public val debut: Int,
    public val fin: Int,
    public val erreurs: Int,
    public val avertissements: Int,
) {
    /** Nombre de lignes de tampon du groupe (bornes inclusives). */
    public val taille: Int
        get() = fin - debut + 1
}

/**
 * L'état dérivé de la vue regroupée — le résumé, les groupes (dans
 * l'ordre du flux), les positions du bloc d'échec (épinglées, HORS des
 * groupes par construction) et les positions hors groupe. Les trois
 * familles de positions sont DISJOINTES et couvrent chaque ligne : la
 * projection regroupée ([ProjectionRegroupee]) n'a qu'à assembler.
 *
 * @property resume le résumé en tête.
 * @property groupes les rangées de tâches, dans l'ordre du flux.
 * @property blocEchec positions des lignes du bloc d'échec (FAILURE /
 *           What went wrong / Where / Try / Exception is / Caused by).
 * @property horsGroupes positions des lignes sans groupe (configuration
 *           avant la première tâche, verdicts, résumé).
 */
public data class EtatRegroupement(
    public val resume: ResumeExecution,
    public val groupes: List<GroupeTache>,
    public val blocEchec: List<Int>,
    public val horsGroupes: List<Int>,
)

/** Constructeur séquentiel de la dérivation (une passe, état courant). */
private class ConstructeurRegroupement(private val execution: EtatExecutionGradle?) {
    private val groupes = ArrayList<Brouillon>()
    private val blocEchec = ArrayList<Int>()
    private val horsGroupes = ArrayList<Int>()
    private var section: SectionFluxGradle = SectionFluxGradle.FLUX
    private var verdictTexte: VerdictGradle? = null
    private var resumeTexte: ResumeTachesGradle? = null

    /** Une structure de fin (bloc d'échec, verdict, résumé) a fermé le
     *  groupe courant : aucun contenu postérieur ne l'étend (jamais de
     *  chevauchement entre groupe et hors-groupe/bloc — la couverture
     *  « chaque ligne une fois » reste vraie par construction). */
    private var structureFermee = false

    /** Compteurs d'événements (`null` = aucun événement compteur : les
     *  lignes servent alors de secours — jamais les deux à la fois). */
    private var erreursEvenement: Int? = execution?.erreurs
    private var avertissementsEvenement: Int? = execution?.avertissements
    private var erreursLignes = 0
    private var avertissementsLignes = 0

    /** Ingestion d'une ligne : les ancres structurelles d'abord (section
     *  fait foi sur le rattachement de tâche), le groupe ensuite. */
    fun ingere(position: Int, ligne: LigneSortie) {
        compteGravite(ligne)
        val enTete = AncresFluxGradle.enTeteTache(ligne.message)
        if (enTete != null) {
            // Frontière TEXTE : l'annonce ouvre un groupe (l'événement
            // fait foi sur le statut, cf. AnalyseLigneGradle) — et
            // réouvre la chasse aux contenus (une nouvelle tâche suit
            // parfois un bloc d'échec, cf. 08-erreur-config.log).
            section = SectionFluxGradle.FLUX
            structureFermee = false
            groupes.add(Brouillon(enTete.chemin, enTete.statutTexte, position))
        } else if (
            poseVerdict(position, ligne) ||
            poseBlocEchec(position, ligne) ||
            poseResume(position, ligne)
        ) {
            // Structure reconnue et posée par son prédicat.
        } else {
            rattacher(position, ligne)
        }
    }

    /** Conclut la dérivation (résumé, groupes figés, listes disjointes). */
    fun conclut(totalLignes: Int): EtatRegroupement = EtatRegroupement(
        resume = ResumeExecution(
            verdict = execution?.verdict ?: verdictTexte?.let { verdictDepuisTexte(it) },
            dureeMs = execution?.dureeMs ?: verdictTexte?.dureeMs,
            compteurs = execution?.compteurs ?: resumeTexte?.let { compteursDepuisTexte(it) },
            erreurs = erreursEvenement ?: erreursLignes,
            avertissements = avertissementsEvenement ?: avertissementsLignes,
            totalLignes = totalLignes,
        ),
        groupes = groupes.map { brouillon -> brouillon.fige(execution) },
        blocEchec = blocEchec.toList(),
        horsGroupes = horsGroupes.toList(),
    )

    /** Pose un verdict texte (hors groupe, ferme la structure) — `true`
     *  quand la ligne EN ÉTAIT un. */
    private fun poseVerdict(position: Int, ligne: LigneSortie): Boolean {
        val verdict = AncresFluxGradle.verdictDe(ligne.message) ?: return false
        section = SectionFluxGradle.FLUX
        verdictTexte = verdict
        structureFermee = true
        horsGroupes.add(position)
        return true
    }

    /** Avance la section et pose la ligne au bloc d'échec (fermé) —
     *  `true` quand elle y tombe. */
    private fun poseBlocEchec(position: Int, ligne: LigneSortie): Boolean {
        section = AncresFluxGradle.sectionDe(ligne.message, section)
        if (section == SectionFluxGradle.FLUX) return false
        structureFermee = true
        blocEchec.add(position)
        return true
    }

    /** Pose la ligne de résumé texte (hors groupe, fermé) — `true` quand
     *  la ligne EN ÉTAIT une. */
    private fun poseResume(position: Int, ligne: LigneSortie): Boolean {
        val resume = AncresFluxGradle.resumeDe(ligne.message) ?: return false
        resumeTexte = resume
        structureFermee = true
        horsGroupes.add(position)
        return true
    }

    /** Le groupe en cours d'ouverture (dernier brouillon), s'il y en a un. */
    private val groupeCourante: Brouillon?
        get() = groupes.lastOrNull()

    /** Rattache la ligne à son groupe (ou la laisse hors groupe) — après
     *  une structure de fin (ou sans groupe courant), un contenu
     *  n'appartient à personne : JAMAIS étendu au groupe fermé (couverture
     *  stricte). */
    private fun rattacher(position: Int, ligne: LigneSortie) {
        val tache = ligne.tache ?: groupeCourante?.chemin
        if (structureFermee || tache == null) {
            horsGroupes.add(position)
            return
        }
        val courant = groupeCourante
        if (courant != null && courant.chemin == tache) {
            courant.etend(position, ligne)
        } else {
            val nouveau = Brouillon(tache, null, position)
            nouveau.etend(position, ligne)
            groupes.add(nouveau)
        }
    }

    /** Compte la gravité d'une ligne (événement fait foi, lignes en secours). */
    private fun compteGravite(ligne: LigneSortie) {
        if (ligne.positionsSource.any { it.gravite == GravitePosition.ERREUR } ||
            ligne.niveau == NiveauSortie.ERREUR ||
            ligne.niveau == NiveauSortie.ASSERT
        ) {
            if (erreursEvenement == null) erreursLignes++
        } else if (
            ligne.positionsSource.any { it.gravite == GravitePosition.AVERTISSEMENT } ||
            ligne.niveau == NiveauSortie.AVERTISSEMENT
        ) {
            if (avertissementsEvenement == null) avertissementsLignes++
        }
    }

    /** Verdict de l'exécution depuis un verdict texte (honnête). */
    private fun verdictDepuisTexte(verdict: VerdictGradle): VerdictExecution =
        if (verdict.reussi) VerdictExecution.REUSSI else VerdictExecution.ECHOUE

    /** Compteurs depuis un résumé texte (dégradation, granularité D1). */
    private fun compteursDepuisTexte(resume: ResumeTachesGradle): CompteursTaches = CompteursTaches(
        actionnables = resume.actionnables,
        executees = resume.executees,
        aJour = resume.aJour,
        depuisCache = resume.depuisCache,
        sansSource = resume.sansSource,
        sautees = resume.sautees,
    )
}

/** Brouillon d'un groupe en cours d'ouverture (figé à la conclusion). */
private class Brouillon(val chemin: String, val statutTexte: StatutTacheGradle?, val debut: Int) {
    private var fin: Int = debut
    private var erreurs = 0
    private var avertissements = 0

    /** Étend le groupe à la position et compte la gravité de la ligne. */
    fun etend(position: Int, ligne: LigneSortie) {
        fin = position
        if (ligne.positionsSource.any { it.gravite == GravitePosition.ERREUR } ||
            ligne.niveau == NiveauSortie.ERREUR ||
            ligne.niveau == NiveauSortie.ASSERT
        ) {
            erreurs++
        } else if (
            ligne.positionsSource.any { it.gravite == GravitePosition.AVERTISSEMENT } ||
            ligne.niveau == NiveauSortie.AVERTISSEMENT
        ) {
            avertissements++
        }
    }

    /** Fige le groupe avec les statuts/durées ÉVÉNEMENT connus. */
    fun fige(execution: EtatExecutionGradle?): GroupeTache = GroupeTache(
        chemin = chemin,
        statutTexte = statutTexte,
        statutEvenement = execution?.statutsParTache?.get(chemin),
        dureeMs = execution?.dureesParTache?.get(chemin),
        debut = debut,
        fin = fin,
        erreurs = erreurs,
        avertissements = avertissements,
    )
}
