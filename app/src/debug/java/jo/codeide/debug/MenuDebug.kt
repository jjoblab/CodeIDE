package jo.codeide.debug

import android.net.Uri
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import jo.codeide.BuildConfig
import jo.codeide.R
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.ForbiddenFolders
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.launch

/**
 * Menu de diagnostic — **variante debug** (source set `debug` de `app`,
 * section 5.8) : provoquer un plantage, une exception non fatale, une
 * salve de journaux, et les essais SAF de la couche données (étape 4 —
 * procédures S1 à S5 de `docs/TESTS_MANUELS.md`).
 *
 * Le bouton s'ancre au contenu de la fenêtre (coin bas-**gauche**, icône
 * seule semi-transparente) sans toucher aux layouts de production : il ne
 * recouvre jamais le bouton d'action principal d'un écran (Commencer,
 * Suivant, Créer… — toujours en bas à droite), s'élève au-dessus de la
 * barre de navigation via les insets, et sera déplacé dans l'écran
 * Diagnostic à l'étape 12 ; la version release porte un no-op de même
 * signature.
 */
object MenuDebug {
    /**
     * Ajoute le bouton d'accès au menu sur l'activité.
     *
     * @param activite activité principale.
     * @param logger journal applicatif des actions non fatales.
     * @param fichiers port d'accès aux documents (essais SAF S1-S5).
     */
    fun installer(
        activite: AppCompatActivity,
        logger: AppLogger,
        fichiers: FileSystem,
    ) {
        val lanceurArbre =
            activite.activityResultRegistry.register(
                CLE_LANCEUR_SAF,
                activite,
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri: Uri? ->
                if (uri != null) {
                    onDossierChoisi(activite, logger, fichiers, uri)
                }
            }

        val contenu = activite.findViewById<ViewGroup>(android.R.id.content) ?: return
        val bouton =
            MaterialButton(
                activite,
                null,
                com.google.android.material.R.attr.materialIconButtonFilledStyle,
            ).apply {
                setIconResource(R.drawable.ic_menu_debug)
                // Cible tactile confortable malgré le style icône (40 dp).
                minimumHeight = activite.resources.getDimensionPixelSize(R.dimen.debug_menu_hauteur_min)
                minimumWidth = activite.resources.getDimensionPixelSize(R.dimen.debug_menu_hauteur_min)
                setContentDescription(activite.getString(R.string.debug_menu_description))
                // Semi-transparent : présent mais jamais assimilé à un
                // contrôle de l'écran de production en dessous.
                alpha = ALPHA_BOUTON
                setOnClickListener { ouvrirDialogue(activite, logger, fichiers) { lanceurArbre.launch(null) } }
            }
        val marge = activite.resources.getDimensionPixelSize(R.dimen.debug_menu_marge)
        val parametres =
            FrameLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    // Bas-gauche : les actions principales vivent en bas à
                    // droite (Commencer, Suivant, Créer) — le bouton debug
                    // ne doit jamais les recouvrir.
                    gravity = Gravity.BOTTOM or Gravity.START
                    marginStart = marge
                    bottomMargin = marge
                }

        // Edge-to-edge : la marge basse suit la barre de navigation (le
        // bouton ne passe jamais dessous), la marge de début suit l'encoche
        // en paysage.
        ViewCompat.setOnApplyWindowInsetsListener(bouton) { vue, insets ->
            val barres = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (vue.layoutParams as? FrameLayout.LayoutParams)?.apply {
                bottomMargin = marge + barres.bottom
                marginStart = marge + barres.left
            }
            vue.requestLayout()
            insets
        }

        contenu.addView(bouton, parametres)
    }

    /** Dernière arborescence choisie — mémoire du processus, pour S1/S2/S5. */
    private var arbreChoisi: Uri? = null

    /** Ouvre le dialogue des actions de diagnostic. */
    private fun ouvrirDialogue(
        activite: AppCompatActivity,
        logger: AppLogger,
        fichiers: FileSystem,
        choisirArbre: () -> Unit,
    ) {
        // Paires libellé → action : l'index positionnel ne peut plus dériver.
        val actions: List<Pair<String, () -> Unit>> =
            listOf(
                activite.getString(R.string.debug_action_plantage) to { provoquerPlantage() },
                activite.getString(R.string.debug_action_non_fatale) to { exceptionNonFatale(logger) },
                activite.getString(R.string.debug_action_salve) to { salveDeJournaux(logger) },
                activite.getString(R.string.debug_action_saf_choisir) to choisirArbre,
                activite.getString(R.string.debug_action_saf_decrire) to { decrireDossier(activite, logger, fichiers) },
                activite.getString(R.string.debug_action_saf_temoin) to { creerTemoin(activite, logger, fichiers) },
            )
        MaterialAlertDialogBuilder(activite)
            .setTitle(activite.getString(R.string.debug_menu_titre))
            .setItems(actions.map { it.first }.toTypedArray()) { dialogue, lequel ->
                dialogue.dismiss()
                actions[lequel].second()
            }.setNegativeButton(activite.getString(R.string.debug_annuler), null)
            .show()
    }

    /** Prise de permission persistante + journalisation de l'arborescence choisie (S2, S3). */
    private fun onDossierChoisi(
        activite: AppCompatActivity,
        logger: AppLogger,
        fichiers: FileSystem,
        arbre: Uri,
    ) {
        arbreChoisi = arbre
        activite.lifecycleScope.launch {
            val permission = fichiers.takePersistablePermission(arbre.toString())
            val raison = ForbiddenFolders.reasonFor(DocumentsContract.getTreeDocumentId(arbre))
            when (permission) {
                is AppResult.Success -> {
                    logger.i(TAG) {
                        "SAF : arborescence choisie, permission prise" +
                            (raison?.let { " — dossier refusé détecté : $it" } ?: "")
                    }
                }

                is AppResult.Failure -> {
                    logger.w(TAG, null) {
                        "SAF : permission refusée (${(permission.error as? jo.codeide.core.model.AppError.Storage)?.reason})"
                    }
                }
            }
        }
    }

    /** Décrit le dossier choisi (S1) : FileStat complet dans les journaux. */
    private fun decrireDossier(
        activite: AppCompatActivity,
        logger: AppLogger,
        fichiers: FileSystem,
    ) {
        val arbre = arbreChoisi
        if (arbre == null) {
            logger.w(TAG, null) { "SAF : aucun dossier choisi pour l'essai" }
            return
        }
        activite.lifecycleScope.launch {
            val uriDocument =
                DocumentsContract.buildDocumentUriUsingTree(
                    arbre,
                    DocumentsContract.getTreeDocumentId(arbre),
                )
            when (val statut = fichiers.stat(uriDocument.toString())) {
                is AppResult.Success -> {
                    logger.i(TAG) {
                        "SAF : dossier « ${statut.value.name} », taille=${statut.value.sizeBytes}, modifié=${statut.value.lastModifiedMillis}"
                    }
                }

                is AppResult.Failure -> {
                    logger.w(TAG, null) {
                        "SAF : description échouée (${(statut.error as? jo.codeide.core.model.AppError.Storage)?.reason})"
                    }
                }
            }
        }
    }

    /** Crée (une fois) le fichier témoin dans le dossier choisi (S2). */
    private fun creerTemoin(
        activite: AppCompatActivity,
        logger: AppLogger,
        fichiers: FileSystem,
    ) {
        val arbre = arbreChoisi
        if (arbre == null) {
            logger.w(TAG, null) { "SAF : aucun dossier choisi pour l'essai" }
            return
        }
        activite.lifecycleScope.launch {
            val uriParent =
                DocumentsContract
                    .buildDocumentUriUsingTree(
                        arbre,
                        DocumentsContract.getTreeDocumentId(arbre),
                    ).toString()
            when (val creation = fichiers.createFile(uriParent, NOM_TEMOIN, "text/plain")) {
                is AppResult.Success -> {
                    fichiers.writeText(creation.value, CONTENU_TEMOIN)
                    logger.i(TAG) { "SAF : témoin créé puis écrit." }
                }

                is AppResult.Failure -> {
                    logger.w(TAG, null) {
                        "SAF : témoin refusé (${(creation.error as? jo.codeide.core.model.AppError.Storage)?.reason}) — collision attendue au second essai."
                    }
                }
            }
        }
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

    /** Translucidité du bouton flottant — visible, jamais assimilé à l'UI. */
    private const val ALPHA_BOUTON = 0.65f

    /** Entrées de la salve (borne raisonnable pour l'inspection). */
    private const val SALVE = 50

    /** Clé d'enregistrement du sélecteur d'arborescence (essais S1-S5). */
    private const val CLE_LANCEUR_SAF = "codeide-debug-saf-arbre"

    /** Nom du fichier témoin (procédure S2). */
    private const val NOM_TEMOIN = "codeide-temoin.txt"

    /** Contenu du fichier témoin (procédure S2). */
    private const val CONTENU_TEMOIN = "Témoin CodeIDE — essai SAF (menu debug)."
}
