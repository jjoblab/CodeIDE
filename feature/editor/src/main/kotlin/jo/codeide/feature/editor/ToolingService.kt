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
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.model.AppSettings
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
 *
 * Réglages utilisateur (ADR 0059) : les interrupteurs par canal
 * (Synchronisation, Build) filtrent la décision pure — un canal coupé ne
 * montre ni notification en cours détaillée ni notification finale ;
 * le service reste foreground le temps de l'activité (obligation
 * Android) avec une notification neutre discrète. Le réglage Son
 * choisit entre le canal silencieux (IMPORTANCE_LOW) et le canal sonore
 * (IMPORTANCE_DEFAULT) — jamais de mutation d'un canal existant
 * (verrouillé par le système après création).
 */
@Suppress("TooManyFunctions")
@AndroidEntryPoint
internal class ToolingService : Service() {
    @Inject
    lateinit var serviceGradle: GradleService

    @Inject
    lateinit var journal: AppLogger

    @Inject
    lateinit var observerParametres: ObserveSettingsUseCase

    /** Derniers réglages connus (collectés dès le démarrage du service). */
    private var reglages = AppSettings()

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
        observerReglages()
        return START_STICKY
    }

    override fun onDestroy() {
        portee.cancel()
        super.onDestroy()
    }

    /** Suit les réglages (canaux, son) pour filtrer la décision. */
    private fun observerReglages() {
        portee.launch {
            observerParametres().collect { reglages = it }
        }
    }

    /** Suit l'état process-wide : notification, résultat, ou arrêt. */
    private fun observerEtat() {
        portee.launch {
            serviceGradle.etat.collect { etat -> rendreEtat(etat) }
        }
    }

    /** Applique la décision pure (filtrée par les réglages, ADR 0059). */
    private fun rendreEtat(etat: EtatGradle) {
        val decision = decisionNotificationTooling(etat)
        if (!canalAutorise(decision)) {
            // Canal coupé par l'utilisateur : ni détail ni résultat — si
            // une activité vit encore, l'obligation de premier plan tient
            // avec une notification neutre discrète, sinon le service
            // s'arrête simplement.
            if (decision is DecisionNotificationTooling.EnCours) {
                journal.d(TAG) { "canal coupé par les réglages : notification neutre" }
                demarrerEnAvantPlan(notificationNeutre())
            } else {
                journal.d(TAG) { "canal coupé par les réglages : rien à montrer" }
                stopSelf()
            }
            return
        }
        when (decision) {
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

    /**
     * Le canal de la décision est-il autorisé par les réglages (ADR 0059) ?
     * `Rien` et le canal Taches (sélecteur sans notification propre)
     * passent toujours ; Sync et Build suivent leurs interrupteurs.
     */
    private fun canalAutorise(decision: DecisionNotificationTooling): Boolean =
        when (decision) {
            DecisionNotificationTooling.Rien -> {
                true
            }

            is DecisionNotificationTooling.EnCours -> {
                when (decision.canal) {
                    CanalTooling.SYNC -> reglages.notificationsSync
                    CanalTooling.BUILD -> reglages.notificationsBuild
                    CanalTooling.TACHES -> true
                }
            }

            is DecisionNotificationTooling.Resultat -> {
                when (decision.canal) {
                    CanalTooling.SYNC -> reglages.notificationsSync
                    CanalTooling.BUILD -> reglages.notificationsBuild
                    CanalTooling.TACHES -> true
                }
            }
        }

    /** Notification neutre : l'obligation de premier plan, sans détail. */
    private fun notificationNeutre(): Notification {
        val gestionnaire = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        gestionnaire.createNotificationChannel(
            NotificationChannel(
                canalCourant(),
                getString(R.string.tooling_service_canal),
                importanceDuCanal(),
            ),
        )
        return NotificationCompat
            .Builder(this, canalCourant())
            .setSmallIcon(R.drawable.ic_executer)
            .setContentTitle(getString(R.string.tooling_service_titre))
            .setContentText(getString(R.string.tooling_service_neutre_texte))
            .setOngoing(true)
            .setContentIntent(intentionOuverture())
            .build()
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
                canalCourant(),
                getString(R.string.tooling_service_canal),
                importanceDuCanal(),
            ),
        )
        return NotificationCompat
            .Builder(this, canalCourant())
            .setSmallIcon(R.drawable.ic_executer)
            .setContentTitle(getString(R.string.tooling_service_titre))
            .setContentText(texte)
            .setOngoing(enCours)
            .setAutoCancel(!enCours)
            .setContentIntent(intentionOuverture())
            .build()
    }

    /**
     * Canal de notification courant (ADR 0059) : silencieux ou sonore
     * selon le réglage — deux canaux distincts plutôt qu'une mutation
     * (l'importance d'un canal existant est figée par le système après
     * création).
     */
    private fun canalCourant(): String = if (reglages.sonNotifications) CANAL_SONORE else CANAL

    /** Importance du canal courant : sonore par défaut, basse sinon. */
    private fun importanceDuCanal(): Int =
        if (reglages.sonNotifications) {
            NotificationManager.IMPORTANCE_DEFAULT
        } else {
            NotificationManager.IMPORTANCE_LOW
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

        /** Canal sonore du tooling (réglage Son, ADR 0059). */
        const val CANAL_SONORE = "tooling_sonore"
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
