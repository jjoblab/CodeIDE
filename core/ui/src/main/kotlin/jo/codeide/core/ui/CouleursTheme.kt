package jo.codeide.core.ui

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.google.android.material.color.MaterialColors

/**
 * Résolution des rôles de couleur Material 3 du **thème courant**
 * (ADR 0060) : base des teintes programmatiques migrées du tiroir
 * explorateur — elles suivent désormais le mode clair/sombre, les
 * couleurs dynamiques et la palette statique.
 *
 * [MaterialColors.getColor] exige un message d'échec explicite : les
 * attrs demandés existent tous dans `Theme.CodeIDE`, un résidu d'erreur
 * serait un bug de thème, pas un état d'exécution.
 */
@ColorInt
public fun Context.couleurRole(
    @AttrRes attr: Int,
): Int = MaterialColors.getColor(this, attr, MESSAGE_ATTR_ABSENT)

/** Couleur primaire (accents de l'explorateur). */
@ColorInt
public fun Context.couleurPrimaire(): Int = couleurRole(androidx.appcompat.R.attr.colorPrimary)

/** Conteneur primaire (accents doux, halos). */
@ColorInt
public fun Context.couleurConteneurPrimaire(): Int =
    couleurRole(com.google.android.material.R.attr.colorPrimaryContainer)

/** Texte principal sur surface. */
@ColorInt
public fun Context.couleurSurSurface(): Int = couleurRole(com.google.android.material.R.attr.colorOnSurface)

/** Texte secondaire sur surface. */
@ColorInt
public fun Context.couleurSurSurfaceDiscret(): Int =
    couleurRole(com.google.android.material.R.attr.colorOnSurfaceVariant)

/** Bordure discrète (traits de séparation, guides). */
@ColorInt
public fun Context.couleurBordureDiscrete(): Int = couleurRole(com.google.android.material.R.attr.colorOutlineVariant)

/** Message d'échec de résolution — attribut absent du thème courant. */
private const val MESSAGE_ATTR_ABSENT = "attribut de couleur absent du thème"
