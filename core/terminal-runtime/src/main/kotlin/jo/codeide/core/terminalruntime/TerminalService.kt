package jo.codeide.core.terminalruntime

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
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.TerminalSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Service foreground du terminal (prompt compagnon Terminal-1,
 * section 4.2) : **unique responsabilité** — garder les sessions vivantes
 * en arrière-plan avec une notification honnête, jamais la logique de
 * rendu.
 *
 * Le registre (singleton Hilt) porte les objets réels ; ce service
 * démarre à la création de la première session ([DemarreurService]),
 * observe la liste et : notification tant qu'au moins une session vit
 * ([DecisionServiceTerminal]), arrêt de soi-même sinon. `START_STICKY`
 * (redémarrage après mort du processus : le service repart, constate un
 * registre vide et s'arrête proprement).
 */
@AndroidEntryPoint
internal class TerminalService : Service() {
    @Inject
    lateinit var registre: RegistreSessionsTermux

    @Inject
    lateinit var journal: AppLogger

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Service foreground immédiat : Android exige startForeground
        // dans les cinq secondes qui suivent startForegroundService.
        demarrerEnAvantPlan(nombreVivantesInitial())
        observerSessions()
        return START_STICKY
    }

    override fun onDestroy() {
        portee.cancel()
        super.onDestroy()
    }

    /** Suit la liste : notification ou arrêt, selon la décision pure. */
    private fun observerSessions() {
        portee.launch {
            registre.observeSessions().collect { sessions ->
                when (val decision = DecisionServiceTerminal.decider(sessions)) {
                    DecisionServiceTerminal.Action.Arreter -> {
                        journal.d(TAG) { "plus de session vivante : arrêt du service" }
                        stopSelf()
                    }

                    is DecisionServiceTerminal.Action.Notifier -> {
                        demarrerEnAvantPlan(decision.sessionsVivantes)
                    }
                }
            }
        }
    }

    /** Passe foreground avec la notification du nombre de sessions. */
    private fun demarrerEnAvantPlan(sessionsVivantes: Int) {
        val notification = notification(sessionsVivantes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                IDENTIFIANT_NOTIFICATION,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(IDENTIFIANT_NOTIFICATION, notification)
        }
    }

    /** Notification honnête : « N session(s) de terminal active(s) ». */
    private fun notification(sessionsVivantes: Int): Notification {
        val gestionnaire = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        gestionnaire.createNotificationChannel(
            NotificationChannel(
                CANAL,
                getString(R.string.terminal_service_canal),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val texte =
            resources.getQuantityString(
                R.plurals.terminal_service_texte,
                sessionsVivantes,
                sessionsVivantes,
            )
        return NotificationCompat
            .Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.terminal_service_titre))
            .setContentText(texte)
            .setOngoing(true)
            .setContentIntent(intentionOuverture())
            .build()
    }

    /** Ouvre l'écran du terminal au toucher (activité branchée en T5). */
    private fun intentionOuverture(): PendingIntent? {
        val intention = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            intention,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Nombre de sessions vivantes au démarrage du service. */
    private fun nombreVivantesInitial(): Int = registre.observeSessions().value.count { it.isAlive }

    private companion object {
        const val TAG = "TerminalService"
        const val CANAL = "terminal"
        const val IDENTIFIANT_NOTIFICATION = 42_001
    }
}

/** Démarrage réel du service (implémentation Android de [DemarreurService]). */
internal class DemarreurServiceAndroid
    @Inject
    constructor(
        // Annotation sans `private val` (leçon T1) : évite le warning K2
        // « appliquée au paramètre seulement » — champ dérivé ci-dessous.
        @ApplicationContext contexte: Context,
    ) : DemarreurService {
        private val contexteApplication: Context = contexte.applicationContext

        override fun demarrer() {
            val intention = Intent(contexteApplication, TerminalService::class.java)
            contexteApplication.startForegroundService(intention)
        }
    }
