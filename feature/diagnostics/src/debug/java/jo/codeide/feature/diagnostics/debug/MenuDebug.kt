package jo.codeide.feature.diagnostics.debug

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.ForbiddenFolders
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.feature.diagnostics.R
import kotlinx.coroutines.launch

/**
 * Menu de diagnostic — **variante debug** (source set `debug` de
 * `feature:diagnostics`, étape 12) : provoquer un plantage, une exception
 * non fatale, une salve de journaux, et les essais SAF de la couche
 * données (procédures S1 à S5 de `docs/TESTS_MANUELS.md`).
 *
 * Déplacé depuis `MainActivity` (étape 3) vers l'écran Diagnostic : les
 * outils de développement vivent désormais avec les outils de diagnostic,
 * jamais sur les écrans de production. La version release porte un no-op
 * de même signature — l'écran n'a aucune connaissance de la variante.
 */
object MenuDebug {
    /** Version lisible de l'application — portée dans les messages de test. */
    private var versionLisible: String = "inconnue"

    /** Dernière arborescence choisie — mémoire du processus, pour S1/S2/S5. */
    private var arbreChoisi: Uri? = null

    /**
     * Ajoute le bouton d'accès au menu dans le conteneur réservé.
     *
     * @param fragment hôte (écran Diagnostic).
     * @param conteneur conteneur réservé du menu dans le layout.
     * @param logger journal applicatif des actions non fatales.
     * @param fichiers port d'accès aux documents (essais SAF S1-S5).
     * @param version nom de version affichable (section « Informations »).
     * @param choisirArbre lance le sélecteur d'arborescence SAF.
     */
    fun installer(
        fragment: Fragment,
        conteneur: ViewGroup,
        logger: AppLogger,
        fichiers: FileSystem,
        version: String,
        choisirArbre: () -> Unit,
    ) {
        versionLisible = version
        val contexte: Context = fragment.requireContext()
        val bouton =
            MaterialButton(
                contexte,
                null,
                com.google.android.material.R.attr.materialIconButtonFilledStyle,
            ).apply {
                setIconResource(R.drawable.ic_menu_debug)
                // Cible tactile confortable malgré le style icône (40 dp).
                minimumHeight = contexte.resources.getDimensionPixelSize(R.dimen.debug_menu_hauteur_min)
                minimumWidth = contexte.resources.getDimensionPixelSize(R.dimen.debug_menu_hauteur_min)
                setContentDescription(contexte.getString(R.string.debug_menu_description))
                setOnClickListener {
                    ouvrirDialogue(fragment, logger, fichiers, choisirArbre)
                }
            }
        conteneur.removeAllViews()
        conteneur.addView(
            bouton,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /**
     * Réagit au dossier choisi par le sélecteur SAF (essais S2, S3) :
     * mémorise l'arborescence, prend la permission persistante et
     * journalise le résultat.
     */
    fun dossierChoisi(
        fragment: Fragment,
        logger: AppLogger,
        fichiers: FileSystem,
        uri: Uri?,
    ) {
        val arbre = uri ?: return
        arbreChoisi = arbre
        fragment.viewLifecycleOwner.lifecycleScope.launch {
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
                        "SAF : permission refusée (${(permission.error as? AppError.Storage)?.reason})"
                    }
                }
            }
        }
    }

    /** Ouvre le dialogue des actions de diagnostic. */
    private fun ouvrirDialogue(
        fragment: Fragment,
        logger: AppLogger,
        fichiers: FileSystem,
        choisirArbre: () -> Unit,
    ) {
        val contexte: Context = fragment.requireContext()
        // Paires libellé → action : l'index positionnel ne peut plus dériver.
        val actions: List<Pair<String, () -> Unit>> =
            listOf(
                contexte.getString(R.string.debug_action_plantage) to { provoquerPlantage() },
                contexte.getString(R.string.debug_action_non_fatale) to { exceptionNonFatale(logger) },
                contexte.getString(R.string.debug_action_salve) to { salveDeJournaux(logger) },
                contexte.getString(R.string.debug_action_saf_choisir) to choisirArbre,
                contexte.getString(R.string.debug_action_saf_decrire) to { decrireDossier(fragment, logger, fichiers) },
                contexte.getString(R.string.debug_action_saf_temoin) to { creerTemoin(fragment, logger, fichiers) },
            )
        MaterialAlertDialogBuilder(contexte)
            .setTitle(contexte.getString(R.string.debug_menu_titre))
            .setItems(actions.map { it.first }.toTypedArray()) { dialogue, lequel ->
                dialogue.dismiss()
                actions[lequel].second()
            }.setNegativeButton(contexte.getString(R.string.debug_annuler), null)
            .show()
    }

    /** Décrit le dossier choisi (S1) : FileStat complet dans les journaux. */
    private fun decrireDossier(
        fragment: Fragment,
        logger: AppLogger,
        fichiers: FileSystem,
    ) {
        val arbre = arbreChoisi
        if (arbre == null) {
            logger.w(TAG, null) { "SAF : aucun dossier choisi pour l'essai" }
            return
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
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
                        "SAF : description échouée (${(statut.error as? AppError.Storage)?.reason})"
                    }
                }
            }
        }
    }

    /** Crée (une fois) le fichier témoin dans le dossier choisi (S2). */
    private fun creerTemoin(
        fragment: Fragment,
        logger: AppLogger,
        fichiers: FileSystem,
    ) {
        val arbre = arbreChoisi
        if (arbre == null) {
            logger.w(TAG, null) { "SAF : aucun dossier choisi pour l'essai" }
            return
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
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
                        "SAF : témoin refusé (${(creation.error as? AppError.Storage)?.reason}) — collision attendue au second essai."
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
            "Plantage de test provoqué depuis le menu debug (CodeIDE $versionLisible)",
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

    /** Nom du fichier témoin (procédure S2). */
    private const val NOM_TEMOIN = "codeide-temoin.txt"

    /** Contenu du fichier témoin (procédure S2). */
    private const val CONTENU_TEMOIN = "Témoin CodeIDE — essai SAF (menu debug)."
}
