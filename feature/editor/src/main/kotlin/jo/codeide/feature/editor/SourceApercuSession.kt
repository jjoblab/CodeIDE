package jo.codeide.feature.editor

import jo.codeeditor.document.Selection
import jo.codeeditor.session.EditorSession
import jo.codeide.core.domain.SourceApercu

/**
 * Adaptateur de la session d'édition vers le port [SourceApercu]
 * (PE6-0, maquette `docs/mockups/apercu-xml.html` § « Partage de la
 * session ») : la session reste la VÉRITÉ du document (elle vit dans le
 * `EditorViewModel`), l'aperçu en activity dédiée la consomme par
 * `:core:domain` — aucun type `cel-*` ne traverse la frontière.
 *
 * Chaque opération délègue à la session d'édition :
 * - [texte] / [remplacer] : état vivant, édition atomique (une SEULE
 *   entrée d'historique — l'annulation reste celle de l'éditeur) ;
 * - [selection] / [definirSelection] : le tap sur le rendu sélectionne
 *   le SPAN source, l'éditeur retrouve la sélection à son retour ;
 * - [annuler] / [retablir] : le MÊME mécanisme que l'éditeur
 *   (`EditorSession.undo/redo` — un pas de plus, pas un historique
 *   parallèle) ;
 * - les écouteurs de mutation rejouent l'écouteur de frappe de la
 *   session (le moteur de l'aperçu re-tire le texte après debounce).
 *
 * L'URI et le chemin relatif sont MUTABLES : le déplacement d'un
 * fichier (migration d'onglet du ViewModel) met à jour l'adaptateur EN
 * PLACE — le registre libère l'ancienne clé et ré-enregistre la
 * nouvelle, l'instance reste la même (aucune déliaison d'écouteur).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — l'adaptateur
 * 1:1 du port : chaque opération a SA délégation (PE10c ajoute les deux
 * requêtes d'historique peutAnnuler/peutRetablir).
 */
@Suppress("TooManyFunctions")
internal class SourceApercuSession(
    private val session: EditorSession,
    uri: String,
    cheminRelatif: String,
    uriProjet: String? = null,
) : SourceApercu {
    /** URI courant (clé du registre) — mis à jour par la migration d'onglet. */
    @Volatile
    override var uri: String = uri
        private set

    /** Chemin relatif courant (classification du genre) — idem migration. */
    @Volatile
    override var cheminRelatif: String = cheminRelatif
        private set

    /** URI document du projet porteur — suivi de l'espace par le ViewModel.
     *
     * CORRECTIF 0.134.31 : la valeur d'ORIGINE vient du CONSTRUCTEUR
     * (le projet suivi au moment de l'ouverture du fichier) — laisser
     * null et compter sur `suivre(projet)` était un raté : les sessions
     * créées APRÈS l'arrivée du projet ne le connaissaient JAMAIS
     * (l'aperçu résolvait `res/` à null → `@color/…` jamais rendu,
     * rapport 0.134.30). `suivre` reste l'écouteur des changements
     * ultérieurs (relocalisation). */
    @Volatile
    override var uriProjet: String? = uriProjet

    /** Mise à jour après déplacement/renommage du fichier (migration d'onglet). */
    public fun migrer(uri: String, cheminRelatif: String) {
        this.uri = uri
        this.cheminRelatif = cheminRelatif
    }

    override fun texte(): String = session.text

    override fun remplacer(debut: Int, fin: Int, remplacement: String): Boolean {
        val longueur = session.text.length
        // Contrat du port : `false` si la plage est HORS BORNES (jamais
        // d'exception, jamais de bornage silencieux — l'appelant décide).
        if (debut < 0 || fin > longueur || fin < debut) return false
        return try {
            session.replaceRange(debut, fin, remplacement)
            true
        } catch (_: Throwable) {
            // Une édition impossible (session disposée, lecture seule)
            // revient un `false` honnête.
            false
        }
    }

    override fun selection(): Pair<Int, Int> {
        val s = session.selection
        return s.start to s.end
    }

    override fun definirSelection(debut: Int, fin: Int) {
        session.setSelection(Selection.range(debut, fin))
    }

    override fun annuler(): Boolean = session.undo()

    override fun retablir(): Boolean = session.redo()

    /** PE10c : l'état de la pile d'annulation de la session (le MÊME historique). */
    override fun peutAnnuler(): Boolean = session.undoManager.canUndo()

    /** PE10c : au moins une édition annulée reste rétablissable. */
    override fun peutRetablir(): Boolean = session.undoManager.canRedo()

    override fun ajouterEcouteur(ecouteur: SourceApercu.EcouteurMutation) {
        session.addOnTextEditListener(pontDe(ecouteur))
    }

    override fun retirerEcouteur(ecouteur: SourceApercu.EcouteurMutation) {
        // Le pont MIS EN CACHE (égalité par référence de la bibliothèque
        // d'édition : retirer un AUTRE objet ne retirerait rien).
        session.removeOnTextEditListener(ponts.remove(ecouteur) ?: return)
    }

    /** Le pont de [ecouteur] — créé une SEULE fois, réutilisé au retrait. */
    private fun pontDe(ecouteur: SourceApercu.EcouteurMutation): EditorSession.OnTextEditListener =
        ponts.getOrPut(ecouteur) { EcouteurSession(ecouteur) }

    /** Ponts écouteur port → écouteur session (1:1, jamais dupliqué). */
    private val ponts = HashMap<SourceApercu.EcouteurMutation, EditorSession.OnTextEditListener>()

    /**
     * Pont vers l'écouteur de frappe de la bibliothèque d'édition —
     * UNE instance par [EcouteurMutation] (égalité par référence : le
     * retirer doit retomber sur le MÊME objet que l'ajout).
     */
    private class EcouteurSession(private val source: SourceApercu.EcouteurMutation) :
        EditorSession.OnTextEditListener {
        override fun onTextEdit(debut: Int, longueur: Int, texte: String?) {
            source.surMutation()
        }
    }
}
