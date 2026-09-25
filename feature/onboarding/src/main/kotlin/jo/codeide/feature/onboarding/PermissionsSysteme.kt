package jo.codeide.feature.onboarding

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * Lecteurs d'état RÉEL des permissions système pour l'assistant
 * (v0.31.3, ADR 0046/0047) — partagés par l'hôte (lanceurs de requêtes)
 * et par la page Notifications (relecture au `onResume`) : une seule
 * source de vérité, jamais de mémoire supposée.
 *
 * La cible 28 (ADR 0045) laisse le système accorder `POST_NOTIFICATIONS`
 * par défaut et montrer lui-même le dialogue : l'état lu fait foi,
 * quel que soit le chemin qui l'a fait changer.
 *
 * Stockage partagé (OPT-IN, ADR 0047) : gestionnaire « Tous les
 * fichiers » sous Android 11+ (`isExternalStorageManager`), permission
 * WRITE sinon (même groupe que READ — un seul dialogue système les
 * accorde ensemble).
 */
internal fun etatNotifications(contexte: Context): Boolean {
    val gestionnaire =
        contexte.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return gestionnaire.areNotificationsEnabled()
}

/** État réel de l'accès au stockage partagé (voir KDoc du fichier). */
internal fun etatStockagePartage(contexte: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            contexte,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
