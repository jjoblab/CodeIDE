package jo.codeide.feature.editor

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
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service foreground du tooling Gradle (étape 32, ADR 0057) : **unique
 * responsabilité** — tenir une notification honnête pendant qu'une
 * activité tooling tourne (sync, build, listage des tâches), jamais la
 * logique de l'exécution (elle vit dans l'orchestrateur).
 *
 * Même contrat que `TerminalService` (prompt Terminal-1 §4.2) :
 * l'état process-wide ([GradleService], singleton) décide ce qui se
 * montre ([decisionNotificationTooling], pure) — le service démarre au
 * premier départ d'activité (port [DemarreurServiceTooling], appelé par
 * le détenteur d'état) et s'arrête de LUI-MÊME quand il n'y a plus rien
 * à montrer : notification en cours tant qu'une activité vit, une
 * notification finale au résultat (elle reste dans le tiroir jusqu'à
 * effacement), rien sinon. `START_STICKY` : après une mort du processus
 * le service repart, constate l'état et s'arrête proprement.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — le cycle de
 * vie Android (onBind/onStartCommand/onDestroy) plus les QUATRE gestes
 * de notification (passer foreground, rendre en cours, rendre résultat,
 * ouvrir l'app) et la durée lisible : les regrouper déplacerait le
 * problème, chaque geste porte sa décision.
 */
@Suppress("TooManyFunctions")
@AndroidEntryPoint
internal class ToolingService : Service() {
    @Inject
    lateinit var serviceGradle: GradleService

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
        rendreEtat(serviceGradle.etat.value)
        observerEtat()
        return START_STICKY
    }

    override fun onDestroy() {
        portee.cancel()
        super.onDestroy()
    }

    /** Suit l'état process-wide : notification, résultat, ou arrêt. */
    private fun observerEtat() {
        portee.launch {
            serviceGradle.etat.collect { etat -> rendreEtat(etat) }
        }
    }

    /** Applique la décision pure : en cours, résultat final, ou arrêt. */
    private fun rendreEtat(etat: EtatGradle) {
        when (val decision = decisionNotificationTooling(etat)) {
            DecisionNotificationTooling.Rien -> {
                journal.d(TAG) { "plus d'activité tooling : arrêt du service" }
                stopSelf()
            }

            is DecisionNotificationTooling.EnCours -> {
                demarrerEnAvantPlan(notificationEnCours(decision))
            }

            is DecisionNotificationTooling.Resultat -> {
                // Notification finale : détachée du foreground (elle
                // reste dans le tiroir, dismissable), service arrêté —
                // la tâche est finie, rien ne justifie de vivre.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        IDENTIFIANT_NOTIFICATION,
                        notificationResultat(decision),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                } else {
                    startForeground(IDENTIFIANT_NOTIFICATION, notificationResultat(decision))
                }
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    /** Passe foreground avec la notification en cours (persistante). */
    private fun demarrerEnAvantPlan(notification: Notification) {
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

    /** Notification « activité en vol » : texte du canal, persistante. */
    private fun notificationEnCours(decision: DecisionNotificationTooling.EnCours): Notification {
        val texte =
            when (decision.canal) {
                CanalTooling.SYNC -> {
                    getString(R.string.tooling_service_sync_en_cours)
                }

                CanalTooling.BUILD -> {
                    if (decision.taches.isEmpty()) {
                        getString(R.string.tooling_service_build_en_cours)
                    } else {
                        getString(R.string.tooling_service_build_taches, decision.taches.joinToString(", "))
                    }
                }

                CanalTooling.TACHES -> {
                    getString(R.string.tooling_service_taches_en_cours)
                }
            }
        return notification(texte = texte, enCours = true)
    }

    /** Notification « résultat » : le dénouement, non persistante. */
    private fun notificationResultat(decision: DecisionNotificationTooling.Resultat): Notification {
        val texte =
            when (decision.canal) {
                CanalTooling.SYNC -> {
                    if (decision.reussi) {
                        getString(R.string.tooling_service_sync_reussie, dureeLisible(decision.dureeMs))
                    } else {
                        getString(R.string.tooling_service_sync_echouee)
                    }
                }

                CanalTooling.BUILD -> {
                    when {
                        decision.annule -> {
                            getString(R.string.tooling_service_build_annule)
                        }

                        decision.reussi -> {
                            getString(
                                R.string.tooling_service_build_reussi,
                                dureeLisible(decision.dureeMs),
                            )
                        }

                        else -> {
                            getString(R.string.tooling_service_build_echoue)
                        }
                    }
                }

                // La décision ne produit jamais de résultat Taches (le
                // sélecteur est le résultat) — repli défensif localisé.
                CanalTooling.TACHES -> {
                    getString(R.string.tooling_service_taches_en_cours)
                }
            }
        return notification(texte = texte, enCours = false)
    }

    /** Notification honnête du tooling : un titre, un texte, un état. */
    private fun notification(
        texte: String,
        enCours: Boolean,
    ): Notification {
        val gestionnaire = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        gestionnaire.createNotificationChannel(
            NotificationChannel(
                CANAL,
                getString(R.string.tooling_service_canal),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        return NotificationCompat
            .Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_executer)
            .setContentTitle(getString(R.string.tooling_service_titre))
            .setContentText(texte)
            .setOngoing(enCours)
            .setAutoCancel(!enCours)
            .setContentIntent(intentionOuverture())
            .build()
    }

    /** Ouvre l'app au toucher (l'éditeur y montrera le détail des canaux). */
    private fun intentionOuverture(): PendingIntent? {
        val intention = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            intention,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Durée lisible (secondes, puis minutes) — même goût que la console. */
    private fun dureeLisible(dureeMs: Long): String {
        val secondes = dureeMs / MILLIS_PAR_SECONDE
        return if (secondes < SECONDES_PAR_MINUTE) {
            getString(R.string.tooling_service_duree_secondes, secondes)
        } else {
            getString(R.string.tooling_service_duree_minutes, secondes / SECONDES_PAR_MINUTE)
        }
    }

    private companion object {
        const val TAG = "ToolingService"
        const val CANAL = "tooling"
        const val IDENTIFIANT_NOTIFICATION = 42_002

        /** Millisecondes par seconde (durée lisible). */
        const val MILLIS_PAR_SECONDE = 1_000L

        /** Secondes par minute (durée lisible). */
        const val SECONDES_PAR_MINUTE = 60L
    }
}

/**
 * Câblage Hilt du service tooling (étape 32) : le port
 * [DemarreurServiceTooling] est implémenté par
 * [DemarreurServiceToolingAndroid] — le détenteur d'état
 * ([GradleService]) reste constructible à la main dans les tests avec un
 * faux qui enregistre les lancements.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ModuleServiceTooling {
    @Binds
    @Singleton
    abstract fun lierDemarreur(implementation: DemarreurServiceToolingAndroid): DemarreurServiceTooling
}
