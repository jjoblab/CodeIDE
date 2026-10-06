package jo.codeide.core.bootstrap.installation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.bootstrap.R
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.EnvironmentSetupOrchestrator
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.Progress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Service de premier plan de l'installation (ADR 0085 § 5 / ADR 0087
 * § 8) : **unique responsabilité** — porter le pipeline hors écran
 * avec une notification honnête et une action Annuler, jamais la
 * logique du parcours (l'orchestrateur singleton la porte).
 *
 * `targetSdk` 28 (ADR 0045) : aucun type de service n'est imposé avant
 * la cible 29 — le précédent `specialUse` du terminal (ADR 0035) est
 * suivi, sous-type documenté. `startForeground` **immédiat** (règle des
 * 5 s), `START_STICKY` : après une mort de processus, le service
 * repart, relit un état sans phase en cours (normalisé `NotStarted`)
 * et s'arrête proprement.
 *
 * En E2, aucun écran ne déclenche `run()` — le branchement UI arrive
 * en E5 ; le service est livré et testé, pas encore démarré en
 * production.
 */
@AndroidEntryPoint
internal class ServiceInstallationEnvironnement : Service() {
    @Inject
    lateinit var orchestrateur: EnvironmentSetupOrchestrator

    @Inject
    lateinit var journal: AppLogger

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_ANNULER -> {
                journal.i(TAG) { "annulation demandée depuis la notification" }
                orchestrateur.cancel()
            }

            else -> {
                Unit
            }
        }
        // Service foreground immédiat : Android exige startForeground
        // dans les cinq secondes qui suivent startForegroundService.
        demarrerEnAvantPlan()
        observerEtat()
        return START_STICKY
    }

    override fun onDestroy() {
        portee.cancel()
        super.onDestroy()
    }

    /** Suit l'état du parcours : notification ou arrêt, selon la décision pure. */
    private fun observerEtat() {
        portee.launch {
            orchestrateur.state.collect { etat ->
                when (val decision = DecisionNotificationInstallation.decider(etat)) {
                    DecisionNotificationInstallation.Action.Arreter -> {
                        journal.i(TAG) { "plus de phase en cours : arrêt du service" }
                        stopSelf()
                    }

                    is DecisionNotificationInstallation.Action.Notifier -> {
                        val gestionnaire = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        gestionnaire.notify(IDENTIFIANT_NOTIFICATION, notification(decision))
                    }
                }
            }
        }
    }

    /** Passe foreground : progression de la phase en cours, ou notification neutre hors exécution. */
    private fun demarrerEnAvantPlan() {
        val notification =
            when (val decision = DecisionNotificationInstallation.decider(orchestrateur.state.value)) {
                // Hors exécution : notification minimale du passage foreground
                // (règle des 5 s) — le collecteur arrêtera le service aussitôt.
                DecisionNotificationInstallation.Action.Arreter -> {
                    baseNotification()
                        .setContentText(getString(R.string.installation_service_titre))
                        .build()
                }

                is DecisionNotificationInstallation.Action.Notifier -> {
                    notification(decision)
                }
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(IDENTIFIANT_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(IDENTIFIANT_NOTIFICATION, notification)
        }
    }

    /** Notification honnête : phase en cours, progression, action Annuler. */
    private fun notification(decision: DecisionNotificationInstallation.Action.Notifier): Notification {
        val (avancement, total, indeterminee) = progression(decision.progression)
        return baseNotification()
            .setContentText(getString(ressourcePhase(decision.phase)))
            .setProgress(total, avancement, indeterminee)
            .build()
    }

    /** Canal + constructeur commun (titre, action Annuler, ouverture de l'application). */
    private fun baseNotification(): NotificationCompat.Builder {
        val gestionnaire = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        gestionnaire.createNotificationChannel(
            NotificationChannel(
                CANAL,
                getString(R.string.installation_service_canal),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val ouverture =
            packageManager.getLaunchIntentForPackage(packageName)?.let { intention ->
                PendingIntent.getActivity(
                    this,
                    0,
                    intention,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        val annuler =
            PendingIntent.getService(
                this,
                0,
                Intent(this, ServiceInstallationEnvironnement::class.java).setAction(ACTION_ANNULER),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val constructeur =
            NotificationCompat
                .Builder(this, CANAL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.installation_service_titre))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.installation_service_annuler), annuler)
        ouverture?.let { constructeur.setContentIntent(it) }
        return constructeur
    }

    /** Ressource du libellé d'une phase (fr/en — jamais une chaîne en dur). */
    private fun ressourcePhase(phase: InstallPhase): Int =
        when (phase) {
            InstallPhase.BOOTSTRAP -> R.string.installation_service_phase_bootstrap
            InstallPhase.PACKAGE_TOOLS -> R.string.installation_service_phase_outils
            InstallPhase.JAVA -> R.string.installation_service_phase_java
            InstallPhase.ANDROID_SDK -> R.string.installation_service_phase_sdk
        }

    /** Traduit la progression du domaine en triplet `setProgress` (avancement, total, indéterminée). */
    private fun progression(progression: Progress): Triple<Int, Int, Boolean> =
        when (progression) {
            Progress.Indeterminate -> {
                Triple(0, 0, true)
            }

            is Progress.Bytes -> {
                val total = progression.total
                if (total != null && total > 0) {
                    Triple((progression.received * PROGRESSION_MAX / total).toInt(), PROGRESSION_MAX, false)
                } else {
                    Triple(0, 0, true)
                }
            }

            is Progress.Items -> {
                Triple(progression.done * PROGRESSION_MAX / progression.total, progression.total, false)
            }
        }

    internal companion object {
        private const val TAG = "install"

        private const val CANAL = "installation_environnement"

        private const val IDENTIFIANT_NOTIFICATION = 4_2087

        /** Action de l'intent d'annulation (bouton Annuler de la notification). */
        internal const val ACTION_ANNULER: String = "jo.codeide.installation.ANNULER"

        /** Échelle commune des barres de progression (0-10 000, précision des octets). */
        private const val PROGRESSION_MAX: Int = 10_000
    }
}
