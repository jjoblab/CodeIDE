package jo.codeide.core.domain

/**
 * Port d'une source de texte pour l'aperçu XML (PE6-0, maquette
 * `docs/mockups/apercu-xml.html` § « Activité dédiée ») : le
 * `EditorViewModel` de `:feature:editor` enregistre chaque session
 * d'édition OUVERTE dans le registre singleton sous cette interface, et
 * `:feature:preview` la consomme — l'activité d'aperçu n'a JAMAIS accès
 * à la bibliothèque d'édition (aucun type `cel-*` ne traverse cette
 * frontière, la dépendance reste confinée à `:feature:editor`).
 *
 * Le port porte la surface du PE7d FUTURE (remplacement atomique,
 * annuler/rétablir) DÈS MAINTENANT — le contrat du registre ne changera
 * pas quand la palette écrira dans le document : [remplacer] délègue
 * l'édition à la session (une SEULE entrée d'historique, l'éditeur reste
 * l'annulable), et [annuler]/[retablir] réutilisent le mécanisme de
 * l'éditeur lui-même.
 *
 * Thread : appelé depuis le fil UI (écouteurs de mutation, sélection) ;
 * les implémentations ne bloquent jamais.
 */
public interface SourceApercu {
    /** URI du document source (clé du registre, survit à l'ouverture d'onglets). */
    public val uri: String

    /** Chemin relatif au projet (ex. `app/src/main/res/layout/main.xml`) — classification du genre. */
    public val cheminRelatif: String

    /** URI document du PROJET porteur (état de l'espace au moment de la lecture) — résolution FUSE de `res/`. */
    public val uriProjet: String?

    /** Texte intégral du document (l'état VIVANT — le tap fournit le texte à la demande). */
    public fun texte(): String

    /**
     * Remplacement ATOMIQUE d'une plage du document (PE7d : la palette
     * écrit par ici) ; `false` si la plage est hors bornes ou la source
     * indisponible — JAMAIS d'exception. La sélection suit l'édition
     * (l'éditeur garde son historique : un seul geste d'annulation).
     */
    public fun remplacer(
        debut: Int,
        fin: Int,
        remplacement: String,
    ): Boolean

    /** Sélection courante (debut, fin) — bornes inclusives/exclusives de la bibliothèque d'édition. */
    public fun selection(): Pair<Int, Int>

    /**
     * Pose la sélection du document (preview → éditeur : le tap sur le
     * rendu sélectionne le SPAN source) — l'éditeur la retrouve quand
     * l'utilisateur revient dans l'onglet.
     */
    public fun definirSelection(
        debut: Int,
        fin: Int,
    )

    /** Annule la dernière édition (`false` = historique épuisé ou indisponible). */
    public fun annuler(): Boolean

    /** Rétablit la dernière édition annulée (`false` = rien à rétablir). */
    public fun retablir(): Boolean

    /** Enregistre un écouteur de mutation (frappes, éditions de la palette) — le moteur de l'aperçu se re-soumet. */
    public fun ajouterEcouteur(ecouteur: EcouteurMutation)

    /** Retire un écouteur précédemment enregistré (déliaison, changement de fichier). */
    public fun retirerEcouteur(ecouteur: EcouteurMutation)

    /**
     * Écouteur de mutation du document — paramètres de la bibliothèque
     * d'édition volontairement NON exposés (le port reste libre : le
     * moteur de l'aperçu re-tire [texte] après son debounce).
     */
    public fun interface EcouteurMutation {
        /** Le document a muté. */
        public fun surMutation()
    }
}
