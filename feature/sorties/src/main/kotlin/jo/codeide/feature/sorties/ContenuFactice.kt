package jo.codeide.feature.sorties

import jo.codeide.core.domain.GravitePosition
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.domain.PositionSource
import jo.codeide.core.domain.SourceSortie
import java.util.Random

/**
 * Le contenu DÉTERMINISTE des lignes factices (lot L2) — un contenu
 * réaliste, calibré et reproductible (graine fixe : deux PV du banc se
 * rejouent à l'identique) : en-têtes de tâches Gradle avec statuts,
 * verdicts avec durées, positions cliquables `e:`/`w:`, segments ANSI
 * SGR, lignes de téléchargement avec octets (arbitrage Q12), UTF-8
 * multi-octets, une ligne longue — le banc mesure le lecteur sur du
 * contenu qui ressemble au câble réel.
 *
 * Calibrage S2 (le banc § 3 « 100 000 lignes, 12 480 chaudes ») : une
 * ligne sur huit est chaude, hors les vingt dernières — exactement
 * 12 480 lignes graduées erreur/avertissement pour les repères de
 * défilement.
 *
 * Extrait de [SourceSortiesFactice] pour tenir la discipline detekt
 * (une classe = une responsabilité : le rythme et les volumes vivent
 * dans la source, le CONTENU vit ici).
 */
// Exemption detekt ciblée (règle 16) : le contenu déterministe du banc a
// UNE fabrique par famille de ligne (tâches, verdicts, positions, ANSI,
// téléchargements) — la scinder éparpillerait le protocole du banc.
@Suppress("TooManyFunctions")
internal object ContenuFactice {

    /** Le contenu d'une ligne selon sa position (réaliste, calibré). */
    internal fun ligneDePosition(position: Int, hasard: Random): LigneSortie {
        val horodatage = HORODATAGE_DEPART + position * PAS_HORODATAGE_MS
        // S2 : 12 480 lignes chaudes exactement — une ligne sur huit,
        // hors les 20 dernières (100 000 / 8 = 12 500 ; 12 500 - 20).
        val chaude = position % PERIODE_CHAUDE == 0 && position < LIMITE_CHAUDES
        return when {
            chaude && position % PERIODE_ERREUR == 0 -> erreurCompilation(position, horodatage)
            chaude -> avertissementCompilation(position, horodatage)
            position % PERIODE_TACHE == 0 -> enTeteTache(position, horodatage)
            position % PERIODE_TACHE == 1 -> verdictReussite(horodatage)
            position % PERIODE_LONGUE == 0 -> ligneTresLongue(position, horodatage)
            position % PERIODE_UTF8 == 0 -> ligneUtf8(position, horodatage)
            position % PERIODE_ANSI == 0 -> ligneAnsi(position, horodatage)
            position % PERIODE_TELECHARGEMENT == 0 -> ligneTelechargement(position, horodatage, hasard)
            else -> ligneFlux(position, horodatage, hasard)
        }
    }

    private fun erreurCompilation(position: Int, horodatage: Long) = LigneSortie(
        source = SourceSortie.BUILD,
        message = "e: file:///home/outils/projet/src/main.kt:" +
            "${position % LIGNES_FICHIER + 1}:${position % COLONNES_FICHIER + 1}: " +
            "unresolved reference: export${position % EXPORTS}",
        horodatageMs = horodatage,
        niveau = NiveauSortie.ERREUR,
        positionsSource = listOf(
            PositionSource(
                fichier = "/home/outils/projet/src/main.kt",
                ligne = position % 400 + 1,
                colonne = position % 60 + 1,
                gravite = GravitePosition.ERREUR,
            ),
        ),
    )

    private fun avertissementCompilation(position: Int, horodatage: Long) = LigneSortie(
        source = SourceSortie.BUILD,
        message = "w: file:///home/outils/projet/src/main.kt:${position % LIGNES_FICHIER + 1}: " +
            "variable 'cache${position % CACHES}' is never used",
        horodatageMs = horodatage,
        niveau = NiveauSortie.AVERTISSEMENT,
    )

    private fun enTeteTache(position: Int, horodatage: Long) = LigneSortie(
        source = SourceSortie.BUILD,
        message = "> Task :app:compile${TACHES[position % TACHES.size]}" + suffixeStatut(position),
        horodatageMs = horodatage,
        etiquette = "tache",
    )

    /** Le suffixe de statut fin de l'en-tête (granularité D1 en attente —
     *  le banc mesure le regroupement sur les QUATRE statuts). */
    private fun suffixeStatut(position: Int): String {
        val statut = STATUTS[(position / PERIODE_TACHE) % STATUTS.size]
        return if (statut == STATUTS.first()) "" else " $statut"
    }

    private fun verdictReussite(horodatage: Long) = LigneSortie(
        source = SourceSortie.BUILD,
        message = "BUILD SUCCESSFUL in 42s",
        horodatageMs = horodatage,
        niveau = NiveauSortie.INFO,
        etiquette = "verdict",
    )

    private fun ligneFlux(position: Int, horodatage: Long, hasard: Random) = LigneSortie(
        source = SourceSortie.BUILD,
        message = "o.compileReleaseKotlin${position % VARIANTS} — ${CONTENUS[hasard.nextInt(CONTENUS.size)]}",
        horodatageMs = horodatage,
    )

    private fun ligneTresLongue(position: Int, horodatage: Long) = LigneSortie(
        source = SourceSortie.BUILD,
        message = "classpath " + "gradle-core-${position % PREFIXES}/".repeat(SEGMENTS_LONGS) + " (4 000 caractères)",
        horodatageMs = horodatage,
    )

    private fun ligneUtf8(position: Int, horodatage: Long) = LigneSortie(
        source = SourceSortie.BUILD,
        message = "relecture des thèses 中文 résumé 🚀 ${position % DOSSIERS_UTF8}",
        horodatageMs = horodatage,
    )

    private fun ligneAnsi(position: Int, horodatage: Long) = LigneSortie(
        source = SourceSortie.BUILD,
        // Concaténation ESC + motif : aucune corruption littérale (leçon
        // du lot L1 — les caractères de contrôle ne passent JAMAIS par un
        // template).
        message = ESC + "[31mviolation de lint: règle ${position % REGLES_LINT}" + ESC + "[0m",
        horodatageMs = horodatage,
        etiquette = "lint",
    )

    private fun ligneTelechargement(position: Int, horodatage: Long, hasard: Random) = LigneSortie(
        source = SourceSortie.BUILD,
        message = "téléchargement ${ARTEFACTS[position % ARTEFACTS.size]}",
        horodatageMs = horodatage,
        etiquette = ARTEFACTS[position % ARTEFACTS.size],
        dureeMs = DUREE_BASE + hasard.nextInt(DUREE_AMPLITUDE),
        octetsRecus = OCTETS_BASE + hasard.nextInt(OCTETS_AMPLITUDE),
        octetsTotal = OCTETS_TOTAL_BASE + hasard.nextInt(OCTETS_TOTAL_AMPLITUDE),
    )

    // Les constantes du contenu, dans le corps de l'objet — le patron de
    // ProjectionSorties : un objet, ses constantes, ses fonctions.

    /** Période des lignes chaudes (une sur huit). */
    private const val PERIODE_CHAUDE = 8

    /** Limite des lignes chaudes (100 000 / 8 − 20 = 12 480 exact). */
    private const val LIMITE_CHAUDES = 99_840

    /** Une ligne chaude sur deux est une erreur (une sur seize). */
    private const val PERIODE_ERREUR = 16

    /** Période des en-têtes de tâche et de la ligne de verdict — 500
     *  tâches sur le volume du banc (S5 : « 500 tâches sur tampon
     *  plein », lot L3). */
    private const val PERIODE_TACHE = 200

    /** Les statuts fins cyclés des en-têtes (protocole Gradle). */
    private val STATUTS = listOf("", "UP-TO-DATE", "FROM-CACHE", "NO-SOURCE")

    /** Périodes des lignes spéciales du contenu factice. */
    private const val PERIODE_LONGUE = 3_000
    private const val PERIODE_UTF8 = 7_000
    private const val PERIODE_ANSI = 9_000
    private const val PERIODE_TELECHARGEMENT = 11_000

    /** Physionomie du fichier compilé dans les positions cliquables. */
    private const val LIGNES_FICHIER = 400
    private const val COLONNES_FICHIER = 60
    private const val EXPORTS = 7
    private const val CACHES = 9
    private const val VARIANTS = 97
    private const val PREFIXES = 99
    private const val SEGMENTS_LONGS = 60
    private const val DOSSIERS_UTF8 = 13
    private const val REGLES_LINT = 21

    /** Durées et volumes des téléchargements factices (Q12). */
    private const val DUREE_BASE = 1_500L
    private const val DUREE_AMPLITUDE = 4_000
    private const val OCTETS_BASE = 1_250_000L
    private const val OCTETS_AMPLITUDE = 2_000_000
    private const val OCTETS_TOTAL_BASE = 2_700_000L
    private const val OCTETS_TOTAL_AMPLITUDE = 3_000_000

    /** Horodatage de départ des lignes factices (epoch fixe). */
    private const val HORODATAGE_DEPART: Long = 1_762_000_000_000L

    /** Pas d'horodatage entre lignes factices (ms) — une progression
     *  lisible à l'affichage relatif. */
    private const val PAS_HORODATAGE_MS: Long = 1L

    private const val ESC: String = "\u001B"

    private val TACHES =
        listOf("DebugKotlin", "DebugJavaWithJavac", "ProcessDebugResources", "MergeDebugNativeLibs")

    private val ARTEFACTS = listOf("kotlin-stdlib-2.2.10.jar", "androidx-core-1.15.0.aar", "gradle-api-9.7.1.jar")

    private val CONTENUS =
        listOf(
            "cache de compilation revalidé",
            "sortie de l'outil de liaison",
            "ressources fusionnées (1 204 entrées)",
            "diagnostic de configuration consigné",
        )
}
