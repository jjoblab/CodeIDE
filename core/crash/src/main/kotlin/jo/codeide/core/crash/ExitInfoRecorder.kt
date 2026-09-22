package jo.codeide.core.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.PendingExitInfoRecorder
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.CrashType
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Détection au démarrage des sorties non traitées (section 5.8) : ANR et
 * plantages natifs de la session précédente, lus dans `ApplicationExitInfo`
 * (API 30+) **hors thread principal**.
 *
 * Les doublons sont impossibles : le dernier horodatage traité est
 * mémorisé dans un fichier témoin du répertoire des rapports. Seul le
 * processus principal est examiné — la mort du processus `:crash` est
 * l'affaire du système.
 *
 * Les rapports ANR/natifs sont des reconstructions : session, écran et
 * filons de la session morte sont perdus (chaîne vide, écran inconnu) —
 * c'est dit dans le rapport, pas inventé.
 */
@Singleton
class ExitInfoRecorder
    @Inject
    internal constructor(
        @param:ApplicationContext private val context: Context,
        private val fileStore: CrashReportFileStore,
        private val appInfo: CrashAppInfo,
        private val timeProvider: TimeProvider,
        private val dispatchers: DispatcherProvider,
    ) : PendingExitInfoRecorder {
        private val fabrique = CrashReportFactory(timeProvider)

        override suspend fun recordPending(): Int =
            withContext(dispatchers.io) {
                // ApplicationExitInfo n'existe qu'à partir de l'API 30 : la
                // garde est ici, tout l'accès vit dans [traiterSorties]
                // annotée `@RequiresApi(R)` — lint vérifie ainsi chaque
                // chemin d'appel.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    0
                } else {
                    traiterSorties()
                }
            }

        /**
         * Parcours des sorties : enregistrement des nouvelles, avance du
         * marqueur de déduplication.
         */
        @RequiresApi(Build.VERSION_CODES.R)
        private fun traiterSorties(): Int {
            val sorties =
                sortiesLisibles() ?: return 0

            val dernierTraite = lireMarqueur()
            var maximum = dernierTraite
            var crees = 0

            for (sortie in sorties) {
                if (sortie.timestamp > maximum) maximum = sortie.timestamp
                if (sortie.timestamp <= dernierTraite) continue
                if (enregistrer(sortie)) crees++
            }

            if (maximum > dernierTraite) ecrireMarqueur(maximum)
            return crees
        }

        /**
         * Sorties de processus du service système — `null` quand la lecture
         * est impossible (service absent, refus).
         */
        @RequiresApi(Build.VERSION_CODES.R)
        private fun sortiesLisibles(): List<ApplicationExitInfo>? {
            val activityManager =
                context.getSystemService(ActivityManager::class.java) ?: return null
            return try {
                activityManager.getHistoricalProcessExitReasons(
                    context.packageName,
                    0,
                    CrashLimits.EXIT_INFO_MAX,
                )
            } catch (erreur: RuntimeException) {
                // Service indisponible : rien à traiter, aucune erreur.
                null
            }
        }

        /**
         * Enregistre une sortie si elle concerne ce processus et porte un
         * type de rapport.
         *
         * @param sortie la sortie de processus candidate.
         * @return `true` si un rapport a été écrit.
         */
        @RequiresApi(Build.VERSION_CODES.R)
        private fun enregistrer(sortie: ApplicationExitInfo): Boolean {
            val type = typeDepuis(sortie.reason) ?: return false
            // Type explicite nullable : getProcessName() est un type
            // plateforme — l'absence de nom reste une entrée valide.
            val nomProcessus: String? = sortie.processName
            if (nomProcessus != null && nomProcessus != context.packageName) return false
            return try {
                fileStore.save(
                    fabrique.fromExitInfo(
                        type = type,
                        timestampMillis = sortie.timestamp,
                        reason = libelleRaison(sortie.reason, sortie.description),
                        trace = sortie.traceInputStream?.use(::lireTraceBornee),
                        appInfo = appInfo,
                        deviceInfo = { deviceInfoDepuisContext(context) },
                    ),
                )
                true
            } catch (erreur: IOException) {
                // Rapport non écrit : le marqueur avance quand même
                // (traité) pour ne pas tourner en rond.
                false
            }
        }

        /** Type de rapport pour cette cause de sortie, ou `null` si ignorée. */
        @RequiresApi(Build.VERSION_CODES.R)
        private fun typeDepuis(reason: Int): CrashType? =
            when (reason) {
                ApplicationExitInfo.REASON_ANR -> CrashType.ANR
                ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashType.NATIVE
                else -> null
            }

        /** Libellé lisible de la cause système (ANR, signal…). */
        @RequiresApi(Build.VERSION_CODES.R)
        private fun libelleRaison(
            reason: Int,
            description: String?,
        ): String =
            buildString {
                append("Sortie de processus (")
                append(
                    when (reason) {
                        ApplicationExitInfo.REASON_ANR -> "ANR"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "plantage natif"
                        else -> "raison $reason"
                    },
                )
                append(")")
                if (!description.isNullOrBlank()) {
                    append(" — ")
                    append(description)
                }
            }

        /** Lit la trace en la bornant — décodée une seule fois, texte pur. */
        private fun lireTraceBornee(entree: InputStream): String =
            try {
                val tampon = ByteArray(TAILLE_TAMPON)
                val resultat = ByteArrayOutputStream()
                var total = 0
                while (total < CrashLimits.EXIT_INFO_TRACE_MAX_BYTES) {
                    val demande =
                        minOf(tampon.size, CrashLimits.EXIT_INFO_TRACE_MAX_BYTES - total)
                    val lus = entree.read(tampon, 0, demande)
                    if (lus <= 0) break
                    resultat.write(tampon, 0, lus)
                    total += lus
                }
                resultat.toByteArray().decodeToString()
            } catch (erreur: IOException) {
                ""
            }

        private fun marqueur(): File = File(context.filesDir, CrashLimits.DIRECTORY_NAME + "/" + NOM_MARQUEUR)

        private fun lireMarqueur(): Long =
            try {
                marqueur().readText().trim().toLongOrNull() ?: 0L
            } catch (erreur: IOException) {
                0L
            }

        private fun ecrireMarqueur(valeur: Long) {
            try {
                marqueur().parentFile?.mkdirs()
                marqueur().writeText(valeur.toString())
            } catch (erreur: IOException) {
                // Marqueur perdu : au pire, une sortie déjà traitée sera
                // re-scannée — un doublon de rapport, jamais une boucle.
            }
        }

        private companion object {
            const val NOM_MARQUEUR = "exit-info-marker.txt"
            const val TAILLE_TAMPON = 4 * 1024
        }
    }
