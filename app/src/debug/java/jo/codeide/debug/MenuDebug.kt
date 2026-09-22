package jo.codeide.debug

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import jo.codeide.BuildConfig
import jo.codeide.R
import jo.codeide.core.domain.AppLogger

/**
 * Menu de diagnostic — **variante debug** (source set `debug` de `app`,
 * section 5.8) : provoquer un plantage, une exception non fatale, une
 * salve de journaux.
 *
 * Le bouton s'ancre au contenu de la fenêtre (coin bas-droit) sans toucher
 * aux layouts de production ; la version release porte un no-op de même
 * signature.
 */
object MenuDebug {
    /**
     * Ajoute le bouton d'accès au menu sur l'activité.
     *
     * @param activite activité principale.
     * @param logger journal applicatif des actions non fatales.
     */
    fun installer(
        activite: AppCompatActivity,
        logger: AppLogger,
    ) {
        val contenu = activite.findViewById<ViewGroup>(android.R.id.content) ?: return
        val bouton =
            MaterialButton(activite).apply {
                text = activite.getString(R.string.debug_menu_bouton)
                isAllCaps = false
                minHeight = activite.resources.getDimensionPixelSize(R.dimen.debug_menu_hauteur_min)
                setContentDescription(activite.getString(R.string.debug_menu_description))
                setOnClickListener { ouvrirDialogue(activite, logger) }
            }
        val parametres =
            FrameLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    val marge = activite.resources.getDimensionPixelSize(R.dimen.debug_menu_marge)
                    marginEnd = marge
                    bottomMargin = marge
                }
        contenu.addView(bouton, parametres)
    }

    /** Ouvre le dialogue des trois actions de diagnostic. */
    private fun ouvrirDialogue(
        activite: AppCompatActivity,
        logger: AppLogger,
    ) {
        val actions =
            listOf(
                activite.getString(R.string.debug_action_plantage),
                activite.getString(R.string.debug_action_non_fatale),
                activite.getString(R.string.debug_action_salve),
            )
        MaterialAlertDialogBuilder(activite)
            .setTitle(activite.getString(R.string.debug_menu_titre))
            .setItems(actions.toTypedArray()) { dialogue, lequel ->
                dialogue.dismiss()
                when (lequel) {
                    0 -> provoquerPlantage()
                    1 -> exceptionNonFatale(logger)
                    2 -> salveDeJournaux(logger)
                }
            }.setNegativeButton(activite.getString(R.string.debug_annuler), null)
            .show()
    }

    /**
     * Provoque un plantage franc sur le thread principal — la chaîne
     * complète (rapport, écran dédié en processus séparé) est alors
     * éprouvée (procédure manuelle P1).
     */
    private fun provoquerPlantage(): Nothing =
        throw IllegalStateException(
            "Plantage de test provoqué depuis le menu debug (CodeIDE ${BuildConfig.VERSION_NAME})",
        )

    /**
     * Journalise une exception **attrapée** — sans interrompre l'application :
     * éprouve l'aplatissement et l'expurgation des chaînes d'exceptions
     * dans les journaux (section 5.7), pas le gestionnaire de plantages.
     */
    private fun exceptionNonFatale(logger: AppLogger) {
        try {
            throw IllegalStateException(
                "échec simulé du dossier /storage/emulated/0/Projets (test)",
                IllegalArgumentException("paramètre de test invalide"),
            )
        } catch (erreur: IllegalStateException) {
            logger.e(TAG, erreur) { "exception non fatale de test, journalisée et attrapée" }
        }
    }

    /**
     * Émet une salve de journaux (trois niveaux) — éprouve le pipeline
     * asynchrone et la fenêtre d'écriture groupée (section 5.7).
     */
    private fun salveDeJournaux(logger: AppLogger) {
        repeat(SALVE) { index ->
            logger.d(TAG) { "salve de test : entrée $index" }
        }
        logger.w(TAG) { "salve de test : avertissement final" }
        logger.e(TAG) { "salve de test : erreur finale (déclenche un vidage immédiat)" }
    }

    private const val TAG = "Debug"

    /** Entrées de la salve (borne raisonnable pour l'inspection). */
    private const val SALVE = 50
}
