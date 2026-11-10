package jo.codeide.feature.sorties

import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.domain.ParseurAnsiSgr
import jo.codeide.core.domain.PositionSource
import jo.codeide.core.domain.SegmentAnsi
import jo.codeide.core.domain.SourceSortie
import java.util.Locale

/**
 * Options de rendu d'une rangée du lecteur (lot L2) — les bascules de la
 * barre d'outils (spec SORTIES § 2 « barre d'outils du lecteur ») :
 * numéros de ligne, horodatage relatif/absolu, retour à la ligne ou
 * défilement horizontal, taille de police (zoom au pincement, bornes
 * 10–20 sp — spec § 3.7) ; lot L5 : pid/tid affichables (spec § 5.4,
 * Logcat — l'option ne change rien aux familles qui n'en portent pas).
 */
public data class OptionsRenduRangee(
    public val afficheNumeros: Boolean = false,
    public val afficheHorodatage: Boolean = false,
    public val horodatageAbsolu: Boolean = false,
    public val retourALaLigne: Boolean = true,
    public val affichePidTid: Boolean = false,
    public val taillePoliceSp: Int = TAILLE_DEFAUT_SP,
) {
    /**
     * L'option avec la taille de police RAMENÉE dans les bornes du zoom
     * (10–20 sp) — le ViewModel la plie avant publication ; la fonction
     * est pure pour être testée.
     */
    public fun taillePoliceBornee(): OptionsRenduRangee = copy(
        taillePoliceSp = taillePoliceSp.coerceIn(TAILLE_MIN_SP, TAILLE_MAX_SP),
    )

    public companion object {
        /** Borne basse du zoom de police (spec SORTIES § 3.7). */
        public const val TAILLE_MIN_SP: Int = 10

        /** Borne haute du zoom de police (spec SORTIES § 3.7). */
        public const val TAILLE_MAX_SP: Int = 20

        /** Taille par défaut (parité console actuelle, 12 sp mono). */
        public const val TAILLE_DEFAUT_SP: Int = 12
    }
}

/**
 * La rangée de rendu du lecteur (lot L2) — le modèle de projection
 * DOMAINE ([LigneSortie] via l'index de projection) traduit en données
 * d'affichage UNE fois à l'ingestion : les segments ANSI SGR sont
 * pré-analysés par [ParseurAnsiSgr] (le rendu ne re-parse jamais, et le
 * texte pur est calculé au même moment — aucune allocation par ligne au
 * bind), la lettre de niveau porte la sévérité sans la couleur.
 *
 * La rangée ne porte PAS de position : le numéro affiché est le RANG dans
 * la liste affichée (fenêtre du tampon — les numéros glissent quand la
 * tête tombe, comme tout défilement de terminal) ; l'index de projection
 * du ViewModel est le seul détenteur des positions de tampon (ADR 0159).
 *
 * @property niveau niveau normalisé de la ligne (ADR 0160).
 * @property source famille de sorties.
 * @property etiquette étiquette courte (tag, canal, artefact) ou `null`.
 * @property horodatageMs instant d'émission/réception de la ligne.
 * @property segments segments de rendu (texte + gras + italique + couleur
 *           ANSI minimale, parseur pur testé au lot L1).
 * @property positionsSource positions cliquables extraites au passage.
 * @property octetsRecus octets reçus d'une ligne de téléchargement
 *           (arbitrage Q12), `null` hors téléchargement.
 * @property octetsTotal octets totaux SI le protocole les connaît, sinon
 *           `null` — jamais de pourcentage inventé.
 * @property pid processus émetteur (Logcat, § 5.4) ou `null`.
 * @property tid fil émetteur (Logcat, § 5.4) ou `null`.
 */
public data class RangeeSortie(
    public val niveau: NiveauSortie,
    public val source: SourceSortie,
    public val etiquette: String?,
    public val horodatageMs: Long,
    public val segments: List<SegmentAnsi>,
    public val positionsSource: List<PositionSource>,
    public val octetsRecus: Long? = null,
    public val octetsTotal: Long? = null,
    public val pid: Int? = null,
    public val tid: Int? = null,
) {
    /** Texte PUR de la ligne (contrôleurs ANSI purgés) — calculé à la
     *  construction (copie/TalkBack/export sans re-parcours). */
    public val textePur: String = segments.joinToString(separator = "") { segment -> segment.texte }

    /** Ligne graduée ERREUR ou ASSERT — repère rouge de la poignée. */
    public val estErreur: Boolean
        get() = niveau == NiveauSortie.ERREUR || niveau == NiveauSortie.ASSERT

    /** Ligne graduée AVERTISSEMENT — repère ambre de la poignée. */
    public val estAvertissement: Boolean
        get() = niveau == NiveauSortie.AVERTISSEMENT

    public companion object {

        /**
         * Projette une ligne du tampon en rangée de rendu — l'analyse SGR
         * a lieu ICI (ingestion, hors thread principal), jamais au bind.
         */
        public fun de(ligne: LigneSortie): RangeeSortie = RangeeSortie(
            niveau = ligne.niveau,
            source = ligne.source,
            etiquette = ligne.etiquette,
            horodatageMs = ligne.horodatageMs,
            segments = ParseurAnsiSgr.analyser(ligne.message),
            positionsSource = ligne.positionsSource,
            octetsRecus = ligne.octetsRecus,
            octetsTotal = ligne.octetsTotal,
            pid = ligne.pid,
            tid = ligne.tid,
        )

        /** La lettre qui porte le niveau sans la couleur (spec § 2). */
        public fun lettreDe(niveau: NiveauSortie): String = when (niveau) {
            NiveauSortie.VERBEUX -> "V"
            NiveauSortie.DEBOGAGE -> "D"
            NiveauSortie.INFO -> "I"
            NiveauSortie.AVERTISSEMENT -> "W"
            NiveauSortie.ERREUR -> "E"
            NiveauSortie.ASSERT -> "F"
            NiveauSortie.INDETERMINE -> "·"
        }
    }
}

/**
 * Horodatage RELATIF d'une rangée (« +2,140 s » depuis l'origine de la
 * fenêtre) — pur, localisé ([Locale] injectée), jamais résolu au modèle.
 */
public fun formaterHorodatageRelatif(
    horodatageMs: Long,
    origineMs: Long,
    locale: Locale = Locale.getDefault(),
): String {
    val ecart = horodatageMs - origineMs
    return if (ecart < 0) {
        String.format(locale, "%.3f s", ecart / MS_PAR_SECONDE)
    } else {
        String.format(locale, "+%.3f s", ecart / MS_PAR_SECONDE)
    }
}

/**
 * Horodatage ABSOLU d'une rangée (HH:mm:ss.SSS, invariant locale — même
 * discipline que la table Logcat). Le format est partagé sous verrou
 * ([SimpleDateFormat] n'est PAS thread-safe) : aucun objet de formatage
 * alloué au bind — le steady-state ne doit rien allouer par ligne (spec
 * SORTIES § 10.2).
 */
public fun formaterHorodatageAbsolu(horodatageMs: Long): String = synchronized(verrouFormatAbsolu) {
    val format = formatAbsolu
        ?: java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).also { formatAbsolu = it }
    format.format(java.util.Date(horodatageMs))
}

/** Format partagé (SimpleDateFormat n'est PAS thread-safe). */
private var formatAbsolu: java.text.SimpleDateFormat? = null
private val verrouFormatAbsolu = Any()

/**
 * Résumé honnête d'une ligne de téléchargement (arbitrage Q12, spec
 * SORTIES § 4.7) : total connu → « 1,2/2,7 Mo · 45 % » ; total inconnu →
 * cumul reçu SEUL (« 1,2 Mo reçus ») — JAMAIS de pourcentage inventé.
 * `null` hors téléchargement (pas d'octets).
 */
public fun resumeTelechargement(
    octetsRecus: Long?,
    octetsTotal: Long?,
    locale: Locale = Locale.getDefault(),
): String? {
    if (octetsRecus == null) return null
    val recu = formaterOctets(octetsRecus, locale)
    return if (octetsTotal != null && octetsTotal > 0) {
        // Unité portée UNE fois : « 1,3/2,6 Mo · 50 % » (la valeur reçue
        // est ramenée dans l'unité du total — jamais deux unités).
        val (valeurTotal, unite) = valeurEtUnite(octetsTotal, locale)
        val facteur = facteurDe(unite)
        val valeurRecus = String.format(locale, "%.1f", octetsRecus / facteur)
        val pourcentage = ((octetsRecus * POURCENTAGE_TOTAL) / octetsTotal).coerceIn(0L, POURCENTAGE_TOTAL)
        String.format(locale, "%s/%s %s · %d %%", valeurRecus, valeurTotal, unite, pourcentage)
    } else {
        String.format(locale, "%s reçus", recu)
    }
}

/** La valeur et son unité lisibles d'un volume d'octets (« 2,6 », « Mo »). */
private fun valeurEtUnite(octets: Long, locale: Locale): Pair<String, String> = when {
    octets >= GIGA_OCTET -> Pair(String.format(locale, "%.1f", octets / GIGA_OCTET.toDouble()), "Go")
    octets >= MEGA_OCTET -> Pair(String.format(locale, "%.1f", octets / MEGA_OCTET.toDouble()), "Mo")
    octets >= KILO_OCTET -> Pair(String.format(locale, "%.1f", octets / KILO_OCTET.toDouble()), "ko")
    else -> Pair(String.format(locale, "%d", octets), "o")
}

/** Le facteur multiplicatif d'une unité d'octets. */
private fun facteurDe(unite: String): Double = when (unite) {
    "Go" -> GIGA_OCTET.toDouble()
    "Mo" -> MEGA_OCTET.toDouble()
    "ko" -> KILO_OCTET.toDouble()
    else -> 1.0
}

/**
 * Octets lisibles (ko / Mo / Go, un décimal) — pur, localisé.
 */
public fun formaterOctets(octets: Long, locale: Locale = Locale.getDefault()): String = when {
    octets >= GIGA_OCTET -> String.format(locale, "%.1f Go", octets / GIGA_OCTET.toDouble())
    octets >= MEGA_OCTET -> String.format(locale, "%.1f Mo", octets / MEGA_OCTET.toDouble())
    octets >= KILO_OCTET -> String.format(locale, "%.1f ko", octets / KILO_OCTET.toDouble())
    else -> String.format(locale, "%d o", octets)
}

/**
 * Le préfixe « pid:tid » d'une rangée Logcat (spec SORTIES § 5.4, lot
 * L5) — pur, localisé ; `null` quand la ligne ne porte pas de processus
 * (l'option du lecteur ne fabrique jamais un pid inventé).
 */
public fun prefixePidTid(pid: Int?, tid: Int?): String? {
    val processus = pid ?: return null
    return "$processus:${tid ?: "?"}"
}

private const val KILO_OCTET: Long = 1024L
private const val MEGA_OCTET: Long = 1024L * 1024L
private const val GIGA_OCTET: Long = 1024L * 1024L * 1024L

/** Millisecondes par seconde (conversion d'horodatage relatif). */
private const val MS_PAR_SECONDE = 1000.0

/** Le pourcentage est exprimé sur une base de cent. */
private const val POURCENTAGE_TOTAL = 100L
