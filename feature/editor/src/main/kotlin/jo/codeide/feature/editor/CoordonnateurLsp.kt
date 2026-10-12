package jo.codeide.feature.editor

import jo.codeeditor.lsp.LspEditor
import jo.codeeditor.lsp.LspProject
import jo.codeeditor.view.EditorView
import jo.codeide.core.domain.AppLogger
import jo.codeide.lsp.host.SessionLsp
import jo.codeide.lsp.host.SessionLspKotlin

/**
 * Branche l'EditorView cel sur le serveur LSP Java in-process (mission
 * « LSP Java » étape 4 lot h2, docs/LSP_JAVA.md §16.2) — miroir
 * volontairement MINCE du `LspCoordinator` de l'ancienne app : UN seul
 * langage (Java), UN seul éditeur (l'EditorView unique de
 `EditorActivity`, rebranchée par onglet, ADR 0028).
 *
 * Cycle : [brancher] déconnecte l'éditeur précédent, crée le
 * `LspEditor` du fichier (`file://` + chemin courant pour la navigation
 * intra-fichier), le lie à la vue puis `connect()` — cel-lsp mène le
 * cycle LSP complet (initialize, didOpen, LspLanguage SPI : complétion,
 * diagnostics, hover, navigation) ; [debrancher] coupe ; [fermer] coupe
 * ET arrête le `LspProject` (à la destruction de l'activité — la
 * SESSION, elle, meurt avec le ViewModel, test de fuite h1).
 *
 * L'échec de `connect()` est JOURNALISÉ, jamais propagé : un fichier
 * .java sans serveur retombe sur la coloration lexicale (comportement
 * de l'ancienne app, honnête).
 *
 * @param journal journal applicatif (règle 11 — rien de personnel dans
 *   les messages : chemins et noms de fichiers seulement).
 */
internal class CoordonnateurLsp(
    private val journal: AppLogger,
) {
    /** Le LspProject cel-lsp de l'espace (UN par session LSP). */
    private var projetLsp: LspProject? = null

    /** L'éditeur branché sur l'onglet actif (UN à la fois, ADR 0028). */
    private var editeurCourant: LspEditor? = null

    /** URI de l'onglet actuellement branché (garde anti-reconnexion). */
    private var uriBranchee: String? = null

    /** L'onglet [uri] est-il déjà branché (évite les re-connexions) ? */
    internal fun estBrancheSur(uri: String): Boolean = uriBranchee == uri

    /**
     * Branche l'éditeur du fichier [cheminFuse] sur le serveur Java de
     * la [session] — déconnecte l'éditeur précédent, crée le nouveau
     * via le `LspProject` (créé UNE fois par session, muni de la
     * définition `definition()` de `:lsp:host`), le lie à la vue puis
     * connecte (asynchrone : le cycle LSP part en arrière-plan).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque sortie
     * porte une DÉCISION distincte (déjà branché, projet cel-lsp
     * incréable, LspEditor incréable, branchement) avec son journal.
     */
    @Suppress("ReturnCount")
    internal fun brancher(
        session: SessionLsp?,
        sessionKotlin: SessionLspKotlin?,
        uri: String,
        cheminFuse: String,
        vue: EditorView,
    ) {
        if (uriBranchee == uri && editeurCourant?.isConnected == true) return
        debrancher()
        val projet = projetLsp ?: creerProjet(session, sessionKotlin) ?: return
        val editeur =
            try {
                projet.createEditor("file://$cheminFuse")
            } catch (t: Throwable) {
                journal.w(TAG, t) { "création LspEditor impossible ($cheminFuse) — coloration seule" }
                return
            }
        editeur.setEditorView(vue)
        // cel (depuis 3.33) : le chemin courant conditionne la
        // reconnaissance des cibles de définition DANS le fichier
        // courant (v0.101.0 : lib montée en 3.49.2 — comportement
        // inchangé, vérifié par diff des sources publiques).
        vue.setCurrentFilePath(cheminFuse)
        editeur
            .connect()
            // v0.100.2 : INFO (visible en verbosité NORMAL) — c'est LE
            // signal « LSP branché » demandé par l'utilisateur ; en DEBUG
            // il était filtré et la connexion semblait muette (§16.2.2).
            .thenRun {
                val langue =
                    if (cheminFuse.endsWith(".kts", true) || cheminFuse.endsWith(".kt", true)) "Kotlin" else "Java"
                journal.i(TAG) { "LSP $langue connecté ($cheminFuse)" }
            }.exceptionally { echec ->
                journal.w(TAG, echec) { "LSP : échec de connexion ($cheminFuse) — coloration seule" }
                null
            }
        editeurCourant = editeur
        uriBranchee = uri
    }

    /** Déconnecte l'éditeur courant (changement d'onglet, onglet fermé). */
    internal fun debrancher() {
        val editeur = editeurCourant ?: return
        editeurCourant = null
        uriBranchee = null
        try {
            editeur.disconnect()
        } catch (t: Throwable) {
            journal.w(TAG, t) { "déconnexion LspEditor en échec (ignorée)" }
        }
    }

    /**
     * Ferme TOUT : éditeur débranché + `LspProject.shutdown()` — appelé
     * à la destruction de l'activité ; la SESSION LSP (moteurs, index,
     * modèle) refermée par le ViewModel (son propre cycle, §16.2).
     */
    internal fun fermer() {
        debrancher()
        val projet = projetLsp
        projetLsp = null
        if (projet != null) {
            try {
                projet.shutdown()
            } catch (t: Throwable) {
                journal.w(TAG, t) { "shutdown LspProject en échec (ignoré)" }
            }
        }
    }

    /**
     * Crée le LspProject de la session (définitions cel-lsp des langues
     * présentes — Java et/ou Kotlin, K2) : cel-lsp route par EXTENSION,
     * le même projet porte toutes les définitions. Racine = celle de la
     * session Java (prioritaire) ou Kotlin — les DEUX sessions d'un même
     * espace partagent la racine du projet.
     */
    private fun creerProjet(
        session: SessionLsp?,
        sessionKotlin: SessionLspKotlin?,
    ): LspProject? {
        val racine = session?.racine ?: sessionKotlin?.racine
        if (racine == null) {
            journal.w(TAG) { "LspProject incréable : aucune session de langue — coloration seule" }
            return null
        }
        return try {
            LspProject(racine.absolutePath).also { projet ->
                session?.let { projet.addServerDefinition(it.definition()) }
                sessionKotlin?.definitions()?.forEach { projet.addServerDefinition(it) }
                journal.d(TAG) {
                    "LspProject configuré (${if (session != null) "Java" else ""}" +
                        "${if (session != null && sessionKotlin != null) " + " else ""}" +
                        "${if (sessionKotlin != null) "Kotlin" else ""}, ${racine.absolutePath})"
                }
            }
        } catch (t: Throwable) {
            journal.w(TAG, t) { "configuration LspProject impossible — fichiers .java/.kt en coloration seule" }
            null
        }
    }

    private companion object {
        private const val TAG = "LspEditeur"
    }
}
