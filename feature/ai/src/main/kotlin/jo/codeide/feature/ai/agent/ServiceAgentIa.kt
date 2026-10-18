package jo.codeide.feature.ai.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.feature.ai.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** ID de notification du service Agent (42_001 terminal, 42_002 tooling). */
public const val ID_NOTIFICATION_AGENT: Int = 42_003

/** Code de requête de l'action « Arrêter » (piège PendingIntent — voir TerminalService). */
public const val REQUETE_ARRETER_AGENT: Int = 42_003

/**
 * Décision PURE du service premier plan (testable hors Android, ADR 0125
 * « service au premier plan visible tant qu'il tourne ») : la notification
 * n'existe QUE pendant une exécution EN_COURS.
 */
public class DecisionServiceAgentIa {
    public enum class Action {
        AFFICHER,
        MASQUER,
    }

    /** La notification doit-elle être affichée pour ce statut ? */
    public fun decider(statut: StatutAgentIa): Action =
        when (statut) {
            StatutAgentIa.EN_COURS -> Action.AFFICHER
            else -> Action.MASQUER
        }
}

/**
 * Service premier plan de l'exécution d'agent (IA-7b, ADR 0125) :
 * notification « Agent en cours » honnête avec action **Arrêter**,
 * démarré à chaque exécution, arrêt de lui-même dès que la boucle sort
 * de EN_COURS. Même patron que [jo.codeide.core.terminalruntime.TerminalService]
 * (type `specialUse` + sous-type FR documenté, startForeground immédiat).
 */
@AndroidEntryPoint
public class ServiceAgentIa : Service() {
    @Inject
    lateinit var controleur: ControleurAgentIa

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decision = DecisionServiceAgentIa()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_ARRETER) {
            controleur.arreter()
        }
        // Android exige startForeground dans les cinq secondes.
        demarrerEnAvantPlan()
        portee.launch {
            controleur.etat.collect { etat ->
                when (decision.decider(etat.statut)) {
                    DecisionServiceAgentIa.Action.AFFICHER -> Unit
                    DecisionServiceAgentIa.Action.MASQUER -> stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        portee.cancel()
        super.onDestroy()
    }

    private fun demarrerEnAvantPlan() {
        val notification = construireNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_NOTIFICATION_AGENT, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_NOTIFICATION_AGENT, notification)
        }
    }

    private fun construireNotification() =
        NotificationCompat
            .Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.agent_service_titre))
            .setContentText(getString(R.string.agent_service_texte))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                getString(R.string.agent_service_arreter),
                PendingIntent.getService(
                    this,
                    REQUETE_ARRETER_AGENT,
                    Intent(this, ServiceAgentIa::class.java).setAction(ACTION_ARRETER),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()

    private companion object {
        /** Canal de notification (créé à chaque construction, même patron que TerminalService). */
        const val CANAL = "agent"

        /** Action « Arrêter » de la notification. */
        const val ACTION_ARRETER = "jo.codeide.agent.ARRETER"
    }

    init {
        val gestionnaire = getSystemService(NotificationManager::class.java)
        gestionnaire.createNotificationChannel(
            NotificationChannel(CANAL, getString(R.string.agent_service_canal), NotificationManager.IMPORTANCE_LOW),
        )
    }
}
