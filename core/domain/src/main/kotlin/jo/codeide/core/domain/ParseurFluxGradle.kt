package jo.codeide.core.domain

/**
 * Statut FIN d'une tâche lu dans le TEXTE de l'en-tête `> Task :…`
 * (arbitrage Q6 : les frontières sont posées par les ÉVÉNEMENTS
 * `EtatTacheBuild` — ce statut texte ne sert qu'à la granularité fine
 * que l'énumération du client n'a pas encore — `UP-TO-DATE`,
 * `FROM-CACHE`, `NO-SOURCE` — en attendant la réponse du dépôt frère
 * à la demande D1). L'événement fait TOUJOURS foi sur le texte.
 */
public enum class StatutTacheGradle {
    /** Tâche exécutée (en-tête sans suffixe). */
    EXECUTEE,

    /** Tâche à jour — incrémentale, rien refait (`UP-TO-DATE`). */
    A_JOUR,

    /** Tâche reprise du cache de build (`FROM-CACHE`). */
    DEPUIS_CACHE,

    /** Tâche sans source à traiter (`NO-SOURCE`). */
    SANS_SOURCE,

    /** Tâche échouée (`FAILED` — le build échouera). */
    ECHOUEE,

    /** Tâche sautée (`SKIPPED` — désactivée, exclue). */
    SAUTEE,
}

/**
 * Section du flux Gradle dans laquelle tombe une ligne (spec SORTIES
 * § 4.3) — tout ce qui n'est pas [FLUX] appartient au bloc d'échec,
 * épinglé sous le résumé et copiable en un geste.
 */
public enum class SectionFluxGradle {
    /** Flux normal (sorties de tâches, configuration, verdicts). */
    FLUX,

    /** En-tête du bloc d'échec : « FAILURE: Build failed with an exception. ». */
    ECHEC_INTITULE,

    /** Section « * Where: » — emplacement du script fautif. */
    ECHEC_OU,

    /** Section « * What went wrong: » et ses « Caused by: ». */
    ECHEC_CAUSE,

    /** Section « * Try: » — conseils de Gradle. */
    ECHEC_CONSEIL,

    /** Section « * Exception is: » — trace interne de Gradle. */
    ECHEC_TRACE,
}

/**
 * Verdict de fin d'un build lu dans le TEXTE (`BUILD SUCCESSFUL in 42s`,
 * `BUILD FAILED in 2m 3s`) — complète l'événement `EtatBuild` (la source
 * de vérité du cycle de vie) : le texte ne fait que le rattacher au flux.
 *
 * @property reussi `true` pour SUCCESSFUL, `false` pour FAILED.
 * @property dureeMs durée imprimée par Gradle, convertie en ms — `null`
 *           si la durée n'a pas pu être lue (le verdict reste affiché).
 */
public data class VerdictGradle(public val reussi: Boolean, public val dureeMs: Long?)

/**
 * Résumé des tâches lu dans le TEXTE (`N actionable tasks: 1 executed,
 * 11 up-to-date`) — complète les compteurs d'événements (`EtatBuild`,
 * jamais comptés sur le texte seul, spec SORTIES § 4.1) : ce résumé ne
 * sert qu'à l'affichage de la ligne de synthèse du flux.
 *
 * Toutes les valeurs sont `null` quand la forme n'a pas été imprimée
 * (build échoué avant la fin).
 */
public data class ResumeTachesGradle(
    public val actionnables: Int?,
    public val executees: Int?,
    public val aJour: Int?,
    public val depuisCache: Int?,
    public val sansSource: Int?,
    public val sautees: Int?,
)

/**
 * Une en-tête de tâche reconnue dans le TEXTE (`> Task :chemin [STATUT]`)
 * — la frontière texte posée par Gradle lui-même (arbitrage Q6 : l'ÉVÉNEMENT
 * fait foi sur le statut, l'ancre rattache les lignes suivantes).
 *
 * @property chemin chemin Gradle complet de la tâche.
 * @property statutTexte statut fin porté par le suffixe, s'il y en a un.
 */
public data class EnTeteTacheGradle(public val chemin: String, public val statutTexte: StatutTacheGradle?)

/**
 * Les ANCRES du flux Gradle (lot L3) — les fonctions pures qui reconnaissent
 * dans le texte brut les structures que le protocole Gradle imprime
 * lui-même : en-têtes de tâches, sections du bloc d'échec, verdicts,
 * résumé. UNE source de vérité partagée par [ParseurFluxGradle] (analyse
 * ligne à ligne au fil du câble) et [RegroupementSorties] (dérivation
 * regroupée sur le tampon) : deux consommateurs, ZÉRO divergence de
 * reconnaissance (une ancre reconnue par l'un est reconnue par l'autre).
 *
 * Ce ne sont PAS des « devinettes de contenu » (Q6) : chaque ancre est un
 * marqueur IMPRIMÉ par le protocole Gradle (mêmes constantes que le
 * client tooling) — l'événement reste la source de vérité du statut.
 */
public object AncresFluxGradle {

    /** En-tête `> Task :chemin [STATUT]` — la frontière TEXTE. */
    public fun enTeteTache(texte: String): EnTeteTacheGradle? {
        val correspondance = REGEX_TACHE.find(texte) ?: return null
        return EnTeteTacheGradle(
            chemin = correspondance.groupValues[1],
            statutTexte = statutTexteDe(correspondance.groupValues[2]),
        )
    }

    /** Le statut fin porté par le suffixe de l'en-tête `> Task :…`. */
    public fun statutTexteDe(suffixe: String): StatutTacheGradle? = when (suffixe) {
        "" -> StatutTacheGradle.EXECUTEE
        "UP-TO-DATE" -> StatutTacheGradle.A_JOUR
        "FROM-CACHE" -> StatutTacheGradle.DEPUIS_CACHE
        "NO-SOURCE" -> StatutTacheGradle.SANS_SOURCE
        "FAILED" -> StatutTacheGradle.ECHOUEE
        "SKIPPED" -> StatutTacheGradle.SAUTEE
        else -> null
    }

    /** Avance (ou persiste) la section du flux selon la ligne. */
    public fun sectionDe(texte: String, precedente: SectionFluxGradle): SectionFluxGradle {
        val t = texte.trim()
        return when {
            t.startsWith(PREFIXE_INTITULE) -> SectionFluxGradle.ECHEC_INTITULE
            t.startsWith(PREFIXE_OU) -> SectionFluxGradle.ECHEC_OU
            t.startsWith(PREFIXE_CAUSE) -> SectionFluxGradle.ECHEC_CAUSE
            t.startsWith(PREFIXE_CONSEIL) -> SectionFluxGradle.ECHEC_CONSEIL
            t.startsWith(PREFIXE_TRACE) -> SectionFluxGradle.ECHEC_TRACE
            t.startsWith(PREFIXE_CAUSE_PAR) -> SectionFluxGradle.ECHEC_CAUSE
            else -> precedente
        }
    }

    /** Le texte est-il un verdict de fin de build ? */
    public fun verdictDe(texte: String): VerdictGradle? {
        val correspondance = REGEX_VERDICT.find(texte) ?: return null
        val reussi = correspondance.groupValues[1] == "SUCCESSFUL"
        val duree = dureeEnMs(correspondance.groupValues[2])
        return VerdictGradle(reussi = reussi, dureeMs = duree)
    }

    /** Le texte est-il la ligne de résumé « N actionable tasks: … » ? */
    public fun resumeDe(texte: String): ResumeTachesGradle? {
        val correspondance = REGEX_RESUME.find(texte) ?: return null
        var executees: Int? = null
        var aJour: Int? = null
        var depuisCache: Int? = null
        var sansSource: Int? = null
        var sautees: Int? = null
        for (detail in REGEX_DETAIL.findAll(correspondance.groupValues[2])) {
            val compte = detail.groupValues[1].toIntOrNull() ?: continue
            when (detail.groupValues[2]) {
                "executed" -> executees = compte
                "up-to-date" -> aJour = compte
                "from-cache" -> depuisCache = compte
                "no-source" -> sansSource = compte
                "skipped" -> sautees = compte
            }
        }
        return ResumeTachesGradle(
            actionnables = correspondance.groupValues[1].toIntOrNull(),
            executees = executees,
            aJour = aJour,
            depuisCache = depuisCache,
            sansSource = sansSource,
            sautees = sautees,
        )
    }

    /** Convertit une durée Gradle imprimée (« 2m 3s », « 954ms ») en ms. */
    private fun dureeEnMs(imprime: String): Long? {
        var totalMs: Long? = null
        for (correspondance in REGEX_DUREE.findAll(imprime)) {
            val nombre = correspondance.groupValues[1].toDoubleOrNull() ?: continue
            val enMs = when (correspondance.groupValues[2]) {
                UNITE_HEURE -> (nombre * MS_PAR_HEURE).toLong()
                UNITE_MINUTE -> (nombre * MS_PAR_MINUTE).toLong()
                UNITE_SECONDE -> (nombre * MS_PAR_SECONDE).toLong()
                UNITE_MILLISECONDE -> nombre.toLong()
                else -> null
            }
            if (enMs != null) {
                totalMs = (totalMs ?: 0) + enMs
            }
        }
        return totalMs
    }

    /** Suffixes de statut fin d'un en-tête de tâche (ordre du texte). */
    private const val SUFFIXES_STATUT = "UP-TO-DATE|FROM-CACHE|NO-SOURCE|FAILED|SKIPPED"

    /** En-tête `> Task :chemin [STATUT]` — la frontière TEXTE. */
    private val REGEX_TACHE = Regex("^> Task (\\S+)(?: ($SUFFIXES_STATUT))?$")

    /** Verdict de fin (`BUILD SUCCESSFUL in …` / `BUILD FAILED in …`). */
    private val REGEX_VERDICT = Regex("^BUILD (SUCCESSFUL|FAILED) in (.+)$")

    /** Résumé de fin (`N actionable tasks: …` — singulier compris). */
    private val REGEX_RESUME = Regex("^(\\d+) actionable tasks?: (.+)$")

    /** Détail du résumé (`7 executed`, `11 up-to-date`…). */
    private val REGEX_DETAIL = Regex("(\\d+) (executed|up-to-date|from-cache|no-source|skipped)")

    /** Durée imprimée par Gradle (« 42s », « 2m 3s », « 954ms », « 3.4s ») —
     *  l'unité la plus longue d'abord pour que « ms » ne se coupe pas. */
    private val REGEX_DUREE = Regex("(\\d+(?:\\.\\d+)?)\\s*(ms|s|m|h)")

    /** Préfixes de section du bloc d'échec, dans l'ordre du texte. */
    private const val PREFIXE_INTITULE = "FAILURE:"
    private const val PREFIXE_OU = "* Where:"
    private const val PREFIXE_CAUSE = "* What went wrong:"
    private const val PREFIXE_CONSEIL = "* Try:"
    private const val PREFIXE_TRACE = "* Exception is:"
    private const val PREFIXE_CAUSE_PAR = "Caused by:"

    /** Unités de durée imprimées par Gradle. */
    private const val UNITE_HEURE = "h"
    private const val UNITE_MINUTE = "m"
    private const val UNITE_SECONDE = "s"
    private const val UNITE_MILLISECONDE = "ms"

    /** Conversions d'unités de durée. */
    private const val MS_PAR_SECONDE = 1_000.0
    private const val MS_PAR_MINUTE = 60_000.0
    private const val MS_PAR_HEURE = 3_600_000.0
}

/**
 * Le résultat de l'analyse d'UNE ligne du flux Gradle par
 * [ParseurFluxGradle] — tout ce que le lecteur (L2) et la projection
 * regroupée (L3) ont besoin de savoir pour placer, colorer et épingler
 * la ligne : rattachement de tâche, section, verdict, résumé, positions
 * cliquables.
 *
 * @property texte texte analysé (transparé — le parseur ne le modifie
 *           pas ; les séquences ANSI se purgent côté [ParseurAnsiSgr]).
 * @property horodatageMs horodatage repris de l'appelant.
 * @property tacheCourante groupe courant APRÈS traitement de la ligne —
 *           c'est lui qui rattache la ligne suivante (posé par les
 *           événements `EtatTacheBuild` ou l'annonce `> Task :…`,
 *           JAMAIS deviné d'une ligne de contenu — Q6).
 * @property tacheAnnoncee chemin de la tâche si CETTE ligne est un
 *           en-tête `> Task :…`, sinon `null` — la frontière texte.
 * @property statutTexte statut fin lu sur l'en-tête texte (voir
 *           [StatutTacheGradle]) — `null` hors en-tête.
 * @property section section du flux APRÈS traitement (une ligne
 *           d'intitulé `FAILURE:` est déjà DANS le bloc d'échec).
 * @property verdict verdict de fin si CETTE ligne est un verdict.
 * @property resume résumé des tâches si CETTE ligne est le résumé
 *           « N actionable tasks ».
 * @property positions positions cliquables extraites AU PASSAGE sur la
 *           ligne (Kotlin `e:`/`w:`, Java `error:`/`warning:`,
 *           emplacements `Build file '…' line: N`).
 */
public data class AnalyseLigneGradle(
    public val texte: String,
    public val horodatageMs: Long,
    public val tacheCourante: String?,
    public val tacheAnnoncee: String?,
    public val statutTexte: StatutTacheGradle?,
    public val section: SectionFluxGradle,
    public val verdict: VerdictGradle?,
    public val resume: ResumeTachesGradle?,
    public val positions: List<PositionSource>,
) {
    /** La ligne appartient-elle au bloc d'échec (épinglable, copiable) ? */
    public val dansBlocEchec: Boolean
        get() = section != SectionFluxGradle.FLUX
}

/**
 * Le parseur de flux Gradle du Centre de sorties (ADR 0160 § Décision 4,
 * lot L1) — machine à états PUR JVM sur le texte du câble : rattache les
 * lignes aux tâches, repère les blocs d'échec (FAILURE / Where / What
 * went wrong / Try / Exception is / Caused by), les verdicts (BUILD
 * SUCCESSFUL/FAILED), le résumé (« N actionable tasks ») et extrait les
 * positions cliquables au passage.
 *
 * **Alignement événement + texte** (spec SORTIES § 4.2, arbitrage Q6) :
 * la frontière de tâche est posée par l'ÉVÉNEMENT ([surTache] sur
 * `EtatTacheBuild` EN_COURS) ; l'en-tête TEXTE `> Task :…` ne sert qu'à
 * rattacher les lignes du flux sous la bonne rangée. Une ligne de
 * contenu sans événement reste au groupe courant — JAMAIS devinée.
 *
 * Le parseur est tolérant aux flux coupés (une ligne tronquée est
 * analysée telle quelle — la suite complétera le groupe sans rien
 * réinventer) et repart propre ([reinitialiser] au début d'une
 * nouvelle exécution, après un `Vider` de canal).
 *
 * Performance : toutes les expressions régulières sont pré-compilées
 * (companions) ; aucune allocation par ligne hormis l'analyse elle-même
 * — le banc de mesure (T6, ADR 0159) fait foi sur les budgets.
 */
public class ParseurFluxGradle {
    /** Groupe de tâche courant (posé par événement ou en-tête texte). */
    private var tacheCourante: String? = null

    /** Section courante du flux (persiste d'une ligne de contenu à l'autre). */
    private var sectionCourante: SectionFluxGradle = SectionFluxGradle.FLUX

    /**
     * Analyse UNE ligne du flux et rend son analyse complète. Le texte
     * n'est jamais modifié (les séquences ANSI se purgent côté
     * [ParseurAnsiSgr]) ; l'état interne porte le groupe courant et la
     * section.
     */
    public fun traiterLigne(texte: String, horodatageMs: Long = 0): AnalyseLigneGradle {
        var annoncee: String? = null
        var statutTexte: StatutTacheGradle? = null
        var verdict: VerdictGradle? = null
        var resume: ResumeTachesGradle? = null

        val enTete = AncresFluxGradle.enTeteTache(texte)
        if (enTete != null) {
            // Frontière TEXTE : l'annonce rattache les lignes suivantes à
            // la rangée de la tâche (l'événement fait foi sur le statut).
            annoncee = enTete.chemin
            tacheCourante = annoncee
            statutTexte = enTete.statutTexte
            sectionCourante = SectionFluxGradle.FLUX
        } else {
            verdict = AncresFluxGradle.verdictDe(texte)
            if (verdict != null) {
                sectionCourante = SectionFluxGradle.FLUX
            } else {
                sectionCourante = AncresFluxGradle.sectionDe(texte, sectionCourante)
                resume = AncresFluxGradle.resumeDe(texte)
            }
        }

        val positions = extrairePositions(texte)
        return AnalyseLigneGradle(
            texte = texte,
            horodatageMs = horodatageMs,
            tacheCourante = tacheCourante,
            tacheAnnoncee = annoncee,
            statutTexte = statutTexte,
            section = sectionCourante,
            verdict = verdict,
            resume = resume,
            positions = positions,
        )
    }

    /**
     * Applique l'événement de tâche du client (`EtatTacheBuild`) : le
     * démarrage POSE la frontière (les lignes suivantes se rattachent à
     * sa rangée), la conclusion laisse le groupe en place jusqu'à la
     * prochaine frontière (la durée se conclut EN PLACE, spec SORTIES
     * § 4.2).
     */
    public fun surTache(evenement: EtatTacheBuild) {
        if (evenement.statut == StatutTache.EN_COURS) {
            tacheCourante = evenement.chemin
        }
    }

    /** Remise à zéro de l'état (nouvelle exécution, canal vidé). */
    public fun reinitialiser() {
        tacheCourante = null
        sectionCourante = SectionFluxGradle.FLUX
    }

    /** Extraction AU PASSAGE des positions cliquables de la ligne. */
    private fun extrairePositions(texte: String): List<PositionSource> = ExtrairePositionsGradle.extraire(texte)
}

/**
 * Extraction AU PASSAGE des positions cliquables d'une ligne du flux
 * (ADR 0160 § Décision 1) — pur et sans état, appelé par le parseur à
 * chaque ligne : `e:`/`w: file://…:l:c` (Kotlin), `fichier.java:l(:c):
 * error|warning:` (Java), `Build file '…' line: N` et `Script '…' line:
 * N` (* Where:). Chaque motif rend AU PLUS une position.
 *
 * Les indices de groupes des trois motifs sont portés par des constantes
 * nommées (même précédent que les codes SGR : un domaine fermé porté par
 * son protocole — la règle MagicNumber ne s'applique pas). Les deux
 * ordres DIFFÈRENT entre Kotlin (marqueur d'abord) et Java (fichier
 * d'abord) : d'où deux jeux de constantes distincts.
 */
private object ExtrairePositionsGradle {
    // Motif Kotlin `e:|w:` : marqueur, fichier, ligne, colonne.
    private const val K_MARQUEUR = 1
    private const val K_FICHIER = 2
    private const val K_LIGNE = 3
    private const val K_COLONNE = 4

    // Motif Java `…java:l(:c)?: error|warning:` : fichier, ligne, colonne,
    // marqueur.
    private const val J_FICHIER = 1
    private const val J_LIGNE = 2
    private const val J_COLONNE = 3
    private const val J_MARQUEUR = 4

    // Motif script (* Where:) : fichier, ligne.
    private const val S_FICHIER = 1
    private const val S_LIGNE = 2

    /** Position Kotlin : `e|w: file://…:l:c: msg` (file:// retiré) — le
     *  marqueur est CAPTURÉ (groupe 1) pour porter la gravité. */
    private val REGEX_KOTLIN = Regex("^([ew]):\\s+(?:(?:file://)|(?:file:/+))?(.+?):(\\d+)(?::(\\d+))?[:\\s]")

    /** Position Java : `chemin.java:l(:c)?: error|warning: msg`. */
    private val REGEX_JAVA = Regex("^(\\S+\\.java):(\\d+)(?::(\\d+))?: (error|warning):")

    /** Emplacement de script (section * Where) : `Build file '…' line: N`. */
    private val REGEX_SCRIPT = Regex("^(?:Build file|Script) '(.+)' line: (\\d+)$")

    /** Les positions cliquables de la ligne, dans l'ordre des motifs. */
    fun extraire(texte: String): List<PositionSource> {
        val positions = ArrayList<PositionSource>(1)

        REGEX_KOTLIN.find(texte)?.let { correspondance ->
            val ligne = correspondance.groupValues[K_LIGNE].toIntOrNull()
            if (ligne != null) {
                positions.add(
                    PositionSource(
                        fichier = correspondance.groupValues[K_FICHIER],
                        ligne = ligne,
                        colonne = correspondance.groupValues[K_COLONNE].toIntOrNull(),
                        gravite = graviteKotlin(correspondance.groupValues[K_MARQUEUR]),
                    ),
                )
            }
        }

        REGEX_JAVA.find(texte)?.let { correspondance ->
            val ligne = correspondance.groupValues[J_LIGNE].toIntOrNull()
            if (ligne != null) {
                positions.add(
                    PositionSource(
                        fichier = correspondance.groupValues[J_FICHIER],
                        ligne = ligne,
                        colonne = correspondance.groupValues[J_COLONNE].toIntOrNull(),
                        gravite = graviteJava(correspondance.groupValues[J_MARQUEUR]),
                    ),
                )
            }
        }

        REGEX_SCRIPT.find(texte)?.let { correspondance ->
            val ligne = correspondance.groupValues[S_LIGNE].toIntOrNull()
            if (ligne != null) {
                positions.add(
                    PositionSource(
                        fichier = correspondance.groupValues[S_FICHIER],
                        ligne = ligne,
                        colonne = null,
                        gravite = GravitePosition.ERREUR,
                    ),
                )
            }
        }

        return positions
    }

    /** Gravité du marqueur Kotlin (`e:` erreur, `w:` avertissement). */
    private fun graviteKotlin(marqueur: String): GravitePosition = when (marqueur) {
        "w" -> GravitePosition.AVERTISSEMENT
        else -> GravitePosition.ERREUR
    }

    /** Gravité du marqueur Java (`error:` / `warning:`). */
    private fun graviteJava(marqueur: String): GravitePosition = when (marqueur) {
        "warning" -> GravitePosition.AVERTISSEMENT
        else -> GravitePosition.ERREUR
    }
}
