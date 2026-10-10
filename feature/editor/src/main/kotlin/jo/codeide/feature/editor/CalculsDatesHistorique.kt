package jo.codeide.feature.editor

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Calculs purs des dates de l'historique local (mission H2, spec
 * HISTORIQUE_LOCAL.md § 5) : moments relatifs lisibles (« il y a 4 min »,
 * « il y a 26 min », « il y a 4 h », « hier 14:02 », date complète) et
 * périodes de regroupement (« Aujourd'hui », « Hier », « Plus ancien » —
 * maquette docs/preview/historique-local.html).
 *
 * Pur et JVM-testable : le fragment ne fait que FORMATER ce que ces
 * calculs décident (l'heure de « hier » est pré-formatée HH:mm, la date
 * complète est rendue par l'appelant avec sa locale).
 */
object CalculsDatesHistorique {
    /** Formateur HH:mm invariable (l'heure n'a pas de locale). */
    private val HEURE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Le moment d'une révision, par paliers :
     * - moins de 60 minutes → [MomentHistorique.IlYA] (minutes) ;
     * - moins de 24 heures → [MomentHistorique.IlYA] (heures) ;
     * - la veille calendaire → [MomentHistorique.Hier] (heure pré-formatée) ;
     * - plus ancien → [MomentHistorique.Ancien] (date laissée à l'appelant).
     */
    fun moment(
        horodatageMs: Long,
        maintenantMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MomentHistorique {
        val minutes = (maintenantMs - horodatageMs) / MILLIS_PAR_MINUTE
        // Le JOUR CALENDAIRE prime sur la durée : « hier 23:30 » à
        // 23 h d'écart, pas « il y a 23 h » (maquette § 5).
        val ecartJours = jour(maintenantMs, zone) - jour(horodatageMs, zone)
        return when {
            minutes < MINUTES_PAR_HEURE -> MomentHistorique.IlYA(minutes, UniteMoment.MINUTES)
            ecartJours <= 0L -> MomentHistorique.IlYA(minutes / MINUTES_PAR_HEURE, UniteMoment.HEURES)
            ecartJours == JOUR_ECART_VEILLE -> MomentHistorique.Hier(heure(horodatageMs, zone))
            else -> MomentHistorique.Ancien(horodatageMs)
        }
    }

    /**
     * Période de regroupement (en-têtes de la liste, maquette) : le jour
     * calendaire d'aujourd'hui, celui d'hier, sinon « plus ancien ».
     */
    fun periode(
        horodatageMs: Long,
        maintenantMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PeriodeHistorique {
        val ecart = jour(maintenantMs, zone) - jour(horodatageMs, zone)
        return when {
            ecart <= 0L -> PeriodeHistorique.AUJOURDHUI
            ecart == 1L -> PeriodeHistorique.HIER
            else -> PeriodeHistorique.PLUS_ANCIEN
        }
    }

    /** Heure « HH:mm » d'un horodatage (invariable). */
    fun heure(
        horodatageMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = HEURE.format(dateTime(horodatageMs, zone))

    /** Jour calendaire en jours écoulés depuis l'époque (comparaison) —
     *  via LocalDateTime (API 26) : LocalDate.ofInstant exigerait l'API 34. */
    private fun jour(
        horodatageMs: Long,
        zone: ZoneId,
    ): Long = LocalDateTime.ofInstant(Instant.ofEpochMilli(horodatageMs), zone).toLocalDate().toEpochDay()

    private fun dateTime(
        horodatageMs: Long,
        zone: ZoneId,
    ): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(horodatageMs), zone)

    /** Millisecondes dans une minute. */
    private const val MILLIS_PAR_MINUTE = 60_000L

    /** Minutes dans une heure (palier « il y a N min » → « il y a N h »). */
    private const val MINUTES_PAR_HEURE = 60L

    /** Écart de jours calendaires qui signifie « la veille ». */
    private const val JOUR_ECART_VEILLE = 1L
}

/** Unité du « il y a » (minutes ou heures). */
enum class UniteMoment {
    MINUTES,
    HEURES,
}

/** Moment d'affichage d'une révision, décidé par [CalculsDatesHistorique]. */
sealed interface MomentHistorique {
    /** « il y a N min » / « il y a N h ». */
    data class IlYA(
        val nombre: Long,
        val unite: UniteMoment,
    ) : MomentHistorique

    /** « hier 14:02 » (l'heure est pré-formatée). */
    data class Hier(
        val heureMinute: String,
    ) : MomentHistorique

    /** Plus ancien : l'horodatage brut, formaté par l'appelant (locale). */
    data class Ancien(
        val horodatageMs: Long,
    ) : MomentHistorique
}

/** En-tête de regroupement de la liste des révisions. */
enum class PeriodeHistorique {
    AUJOURDHUI,
    HIER,
    PLUS_ANCIEN,
}
