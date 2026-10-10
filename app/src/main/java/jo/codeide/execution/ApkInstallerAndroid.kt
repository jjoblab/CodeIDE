package jo.codeide.execution

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.ApkInstaller
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EtapeInstallationApk
import jo.codeide.core.domain.ResultatInstallationApk
import jo.codeide.core.domain.ResultatLancementApk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation Android de [ApkInstaller] (mission « Exécuter » R1,
 * ADR 0102) : **`PackageInstaller`**, sans adb — l'équivalent mobile du
 * runner d'Android Studio.
 *
 * Flux (conforme à l'ADR) :
 * 1. **garde « sources inconnues »** : `canRequestPackageInstalls()`
 *    faux → l'écran système s'ouvre PUIS l'installation **reprend
 *    automatiquement** (attente bornée 5 min — le Run n'est jamais
 *    perdu) ;
 * 2. session `MODE_FULL_INSTALL`, copie de `base.apk` par blocs de
 *    8 Ko + **`fsync`** (un APK partiellement copié n'est jamais
 *    commité) ;
 * 3. `setRequireUserAction(false)` dès l'API 31 : après la première
 *    confirmation d'un paquet, les mises à jour peuvent devenir
 *    silencieuses SI le système l'accepte — sinon la confirmation
 *    s'affiche (honnêteté d'abord) ;
 * 4. `commit` avec un `PendingIntent` de diffusion **mutable** (le
 *    système y écrit le statut — requis depuis API 31) vers un
 *    récepteur **non exporté à action unique par session** (action
 *    auto-réservée au paquet : non forgeable) ;
 * 5. `STATUS_PENDING_USER_ACTION` : l'intent de confirmation du
 *    système est relancé (l'installation passe TOUJOURS par la
 *    confirmation quand le système l'exige) puis l'attente continue ;
 * 6. statut final : succès, ou échec TYPÉE (signature différente,
 *    version plus récente, espace, annulation, autre).
 *
 * Le lancement résout l'activité de démarrage et **persiste** (10 ×
 * 200 ms) : juste après l'installation, le `PackageManager` du
 * processus appelant peut ne pas encore voir le paquet. Un service
 * d'arrière-plan n'ayant pas le droit de démarrer une activité
 * (Android 10+), l'appel vient toujours de l'espace d'édition au
 * premier plan.
 *
 * Exemptions detekt ciblées (règle 16 du prompt maître) :
 * - LongMethod : `attendreStatut` est le pipeline documenté
 *   (récepteur → commit → attente → traduction) — le découper
 *   davantage masquerait l'enchaînement des gardes ;
 * - SwallowedException / TooGenericExceptionCaught : les statuts du
 *   système arrivent en INT et en textes non typés — chaque échec est
 *   converti en issue TYPÉE du port, l'exception brute n'a pas de
 *   valeur pour l'appelant.
 */
@Suppress("TooManyFunctions") // Une fonction par étape du pipeline (session, copie, statut, lancement), hérité du port.
@Singleton
internal class ApkInstallerAndroid
    @Inject
    constructor(
        // Annotation sans `private val` (leçon T1, même correctif que
        // DemarreurServiceToolingAndroid) : évite le warning K2
        // « appliquée au paramètre seulement ».
        @ApplicationContext contexte: Context,
        private val repartiteurs: DispatcherProvider,
    ) : ApkInstaller {
        private val contexteApplication: Context = contexte.applicationContext
        private val gestionnairePaquets: PackageManager get() = contexteApplication.packageManager
        private val installateurSysteme: PackageInstaller get() = gestionnairePaquets.packageInstaller

        override suspend fun installer(
            apk: File,
            progression: ((EtapeInstallationApk) -> Unit)?,
        ): ResultatInstallationApk =
            withContext(repartiteurs.io) {
                if (!gestionnairePaquets.canRequestPackageInstalls()) {
                    progression?.invoke(EtapeInstallationApk.AutorisationSourcesInconnuesRequise)
                    if (!attendreAutorisationSourcesInconnues()) {
                        return@withContext ResultatInstallationApk.AutorisationRefusee
                    }
                }
                installerSession(apk)
            }

        override suspend fun lancer(nomPaquet: String): ResultatLancementApk =
            withContext(repartiteurs.main) {
                repeat(NB_RELANCES_LANCEMENT) {
                    val intention = intentionLancement(nomPaquet)
                    if (intention != null) {
                        intention.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        contexteApplication.startActivity(intention)
                        return@withContext ResultatLancementApk.Succes(nomPaquet)
                    }
                    delay(DELAI_RELANCES_LANCEMENT_MS)
                }
                ResultatLancementApk.Introuvable(nomPaquet)
            }

        /**
         * Ouvre l'écran « Installer des applications inconnues » pour
         * CodeIDE PUIS attend l'autorisation (poll borné) — l'appelant
         * est au premier plan, l'installation reprend d'elle-même.
         */
        private suspend fun attendreAutorisationSourcesInconnues(): Boolean {
            ouvrirEcranAutorisationSources()
            val accordee =
                withTimeoutOrNull(DELAI_ATTENTE_AUTORISATION_MS) {
                    while (!gestionnairePaquets.canRequestPackageInstalls()) {
                        delay(PERIODE_SONDE_AUTORISATION_MS)
                    }
                    true
                }
            return accordee == true
        }

        /** Ouvre l'écran système (CodeIDE est au premier plan : légal). */
        private fun ouvrirEcranAutorisationSources() {
            val intention =
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData("package:${contexteApplication.packageName}".toUri())
            intention.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            contexteApplication.startActivity(intention)
        }

        /**
         * Copie l'APK dans la session puis commet et attend le statut.
         *
         * Exemption detekt ciblée (règle 16) : ReturnCount — une issue
         * typée par étape du pipeline (session impossible, copie
         * échouée, verdict du système), les imbriquer masquerait les
         * gardes.
         */
        @Suppress("ReturnCount")
        private suspend fun installerSession(apk: File): ResultatInstallationApk {
            val parametres =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Mises à jour silencieuses après la première
                        // confirmation — SI le système l'accepte (SDK 37 :
                        // la signature est passée de boolean à int).
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
            val idSession =
                try {
                    installateurSysteme.createSession(parametres)
                } catch (e: Exception) {
                    return ResultatInstallationApk.Autre("Session d'installation impossible : ${e.message}")
                }

            copierApkDansSession(idSession, apk)?.let { return it }

            return attendreStatut(idSession)
        }

        /**
         * Copie l'APK dans la session ouverte — `null` si la copie a
         * réussi, sinon l'échec typé (session abandonnée, résidu jamais
         * silencieux).
         */
        private fun copierApkDansSession(
            idSession: Int,
            apk: File,
        ): ResultatInstallationApk? =
            try {
                installateurSysteme.openSession(idSession).use { session ->
                    session.openWrite(NOM_APK_SESSION, 0, apk.length()).use { sortie ->
                        viderFichierDans(apk, session, sortie)
                    }
                }
                null
            } catch (e: IOException) {
                abandonner(idSession)
                    ?: ResultatInstallationApk.Autre("Copie de l'APK impossible : ${e.message}")
            }

        /**
         * Vide le fichier APK dans le flux de la session PUIS `fsync` —
         * un APK partiellement copié n'est jamais commité.
         */
        private fun viderFichierDans(
            apk: File,
            session: PackageInstaller.Session,
            sortie: java.io.OutputStream,
        ) {
            apk.inputStream().use { entree -> entree.copyTo(sortie, TAILLE_BLOC) }
            session.fsync(sortie)
        }

        /**
         * Commit + attente du statut final par diffusion (récepteur non
         * exporté, action unique par session, auto-réservée au paquet).
         * `STATUS_PENDING_USER_ACTION` relance la confirmation système
         * et POURSUIT l'attente du statut final.
         *
         * Exemptions : voir la KDoc de la classe.
         */
        @Suppress("LongMethod", "SwallowedException", "TooGenericExceptionCaught")
        private suspend fun attendreStatut(idSession: Int): ResultatInstallationApk {
            val action = "${contexteApplication.packageName}.INSTALL_STATUS.$idSession.${UUID.randomUUID()}"
            val attente = AttenteStatut()
            val recepteur =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        contexteRecepteur: Context,
                        intention: Intent,
                    ) {
                        when (intention.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                IntentCompat
                                    .getParcelableExtra(intention, Intent.EXTRA_INTENT, Intent::class.java)
                                    ?.let { confirmation ->
                                        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        contexteApplication.startActivity(confirmation)
                                    }
                            }

                            else -> {
                                attente.conclure(
                                    intention.getIntExtra(PackageInstaller.EXTRA_STATUS, -1),
                                    intention.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                                )
                            }
                        }
                    }
                }
            ContextCompat.registerReceiver(
                contexteApplication,
                recepteur,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            try {
                val diffusion =
                    PendingIntent.getBroadcast(
                        contexteApplication,
                        UUID.randomUUID().hashCode(),
                        Intent(action).setPackage(contexteApplication.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                installateurSysteme.openSession(idSession).use { session -> session.commit(diffusion.intentSender) }

                val statutFinal =
                    withTimeoutOrNull(DELAI_ATTENTE_STATUT_MS) { attente.statutFinal() }
                        ?: return abandonner(idSession)
                            ?: ResultatInstallationApk.Annule("Délai d'installation dépassé")

                return traduire(statutFinal)
            } finally {
                contexteApplication.unregisterReceiver(recepteur)
            }
        }

        /**
         * Traduit un statut système en issue TYPÉE du port — chaque
         * échec porte son action correctrice (ADR 0102). Les codes
         * d'échec d'installation (`INSTALL_FAILED_*`) voyagent dans le
         * message de statut : ils y sont recherchés (le code brut
         * `STATUS_FAILURE` ne distingue rien).
         */
        private fun traduire(statut: StatutFinal): ResultatInstallationApk =
            when {
                statut.code == PackageInstaller.STATUS_SUCCESS -> {
                    ResultatInstallationApk.Succes(confirmationUtilisateur = false)
                }

                statut.message.orEmpty().contains("UPDATE_INCOMPATIBLE", ignoreCase = true) -> {
                    ResultatInstallationApk.SignatureDifferente
                }

                statut.message.orEmpty().contains("VERSION_DOWNGRADE", ignoreCase = true) -> {
                    ResultatInstallationApk.VersionPlusRecenteInstallee
                }

                statut.message.orEmpty().contains("INSUFFICIENT_STORAGE", ignoreCase = true) -> {
                    ResultatInstallationApk.EspaceInsuffisant
                }

                statut.code == PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    ResultatInstallationApk.Annule(statut.message)
                }

                else -> {
                    ResultatInstallationApk.Autre(statut.message)
                }
            }

        /**
         * Abandonne une session : `null` si l'abandon a réussi
         * (l'appelant fournit alors l'échec explicatif), sinon l'échec
         * de l'abandon lui-même — jamais un résidu silencieux.
         */
        private fun abandonner(idSession: Int): ResultatInstallationApk? =
            try {
                installateurSysteme.abandonSession(idSession)
                null
            } catch (e: Exception) {
                ResultatInstallationApk.Autre("Abandon de session impossible : ${e.message}")
            }

        /**
         * Intent de lancement : résolution `getLaunchIntentForPackage`,
         * repli par requête `MAIN`/`LAUNCHER` en intent EXPLICITE (la
         * visibilité du paquet passe par le bloc `<queries>` du
         * manifeste, ADR 0102).
         *
         * Exemption detekt ciblée (règle 16) : ReturnCount — une issue
         * par garde (intent principal, activité de repli, introuvable).
         */
        @Suppress("ReturnCount")
        private fun intentionLancement(nomPaquet: String): Intent? {
            gestionnairePaquets.getLaunchIntentForPackage(nomPaquet)?.let { return it }
            val activites =
                gestionnairePaquets.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(nomPaquet),
                    0,
                )
            val premiere = activites.firstOrNull()?.activityInfo ?: return null
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = android.content.ComponentName(premiere.packageName, premiere.name)
            }
        }

        /**
         * Statut final d'une session : code `PackageInstaller` + message
         * système (porteur des `INSTALL_FAILED_*`).
         */
        private data class StatutFinal(
            val code: Int,
            val message: String?,
        )

        /**
         * Cloison du statut final : la première conclusion gagne (canal
         * `CONFLATED`), l'attente est ANNULABLE (la fermeture de
         * l'espace n'attend pas indéfiniment un système muet) — le
         * récepteur est désenregistré par l'appelant dans un `finally`.
         */
        private class AttenteStatut {
            private val canal = Channel<StatutFinal>(capacity = Channel.CONFLATED)

            /** Conclut (première conclusion seule retenue). */
            fun conclure(
                code: Int,
                message: String?,
            ) {
                canal.trySend(StatutFinal(code, message))
            }

            /** Attend la conclusion — suspendante et annulable. */
            suspend fun statutFinal(): StatutFinal = canal.receive()
        }

        private companion object {
            /** Nom d'écriture de l'APK dans la session (contrat PackageInstaller). */
            const val NOM_APK_SESSION = "base.apk"

            /** Bloc de copie (8 Ko — même taille que les IDE de référence). */
            const val TAILLE_BLOC = 8 * 1024

            /** Relances du lancement (10 × 200 ms — PackageManager froid). */
            const val NB_RELANCES_LANCEMENT = 10
            const val DELAI_RELANCES_LANCEMENT_MS = 200L

            /** Attente de l'autorisation « sources inconnues » (5 min, poll 300 ms). */
            const val DELAI_ATTENTE_AUTORISATION_MS = 300_000L
            const val PERIODE_SONDE_AUTORISATION_MS = 300L

            /** Attente du statut final (confirmations système comprises). */
            const val DELAI_ATTENTE_STATUT_MS = 600_000L
        }
    }
