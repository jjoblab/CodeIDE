package jo.codeide.core.ui

import android.app.Activity
import com.google.android.material.color.DynamicColors

/**
 * Applique à l'activité les couleurs dynamiques Material You quand
 * l'appareil les prend en charge (Android 12+), sans effet sinon.
 *
 * Les couleurs dynamiques sont **optionnelles** (section 11, étape 1) :
 * elles s'appliquent par-dessus `Theme.CodeIDE` et deviendront un réglage
 * utilisateur à l'étape 5 (`AppSettings.useDynamicColor`, voir ADR 0008).
 *
 * À appeler dans `Activity.onCreate` après `super`, avant `setContentView`.
 */
public fun Activity.applyDynamicColorsIfAvailable() {
    DynamicColors.applyToActivityIfAvailable(this)
}
