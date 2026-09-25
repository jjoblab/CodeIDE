package jo.codeide.core.ui

import androidx.fragment.app.Fragment

/**
 * Fabrique du fragment **Terminal du tiroir** (v0.32.2, ADR 0053).
 *
 * Le rendu réel des sessions (vues Termux, split view, plein écran dans
 * le tiroir) impose au fragment de vivre dans `feature:terminal` — seul
 * feature autorisé à dépendre du runtime des sessions (règle des
 * modules). L'activité d'édition, elle, reste dans `feature:editor` et
 * ne peut référencer la classe : elle demande donc le fragment à cette
 * fabrique, liée par Hilt (`@Binds`) côté `feature:terminal`.
 *
 * Le fragment résout son [ControleurTerminalTiroir] auprès de l'activité
 * hôte (cast en `onAttach`) : aucune référence croisée feature ↔ feature
 * au moment de la compilation — les deux interfaces vivent dans ce
 * module, le socle partagé.
 */
public interface FabriqueFragmentTerminalTiroir {
    /** Crée le fragment Terminal du tiroir, prêt à ajouter au gestionnaire. */
    public fun creer(): Fragment
}

/**
 * Commandes du fragment Terminal du tiroir que seule l'activité d'édition
 * peut exécuter (v0.32.2, ADR 0053) : elles réclament l'état de l'espace
 * de travail (dossier réel du projet via le pont FUSE, garde-fou du
 * bootstrap) — des choses que `feature:terminal` ne connaît pas.
 *
 * L'activité d'édition implémente l'interface et le fragment la résout
 * par cast en `onAttach` ; un hôte qui ne l'implémente pas voit
 * simplement les actions concernées se désactiver.
 */
public interface ControleurTerminalTiroir {
    /**
     * Ouvre l'écran plein écran du terminal, répertoire de travail
     * suggéré = dossier réel du projet courant (résolu côté éditeur).
     */
    public fun ouvrirEcranTerminal()

    /**
     * Crée une session dans le dossier du projet courant **sans**
     * naviguer : la session apparaît dans le tiroir (liste ou split),
     * l'utilisateur choisit ensuite de l'agrandir dans le tiroir ou de
     * passer au plein écran. Bootstrap absent : ouvre l'installation.
     */
    public fun creerSessionProjet()

    /** Ouvre l'écran d'installation du bootstrap natif. */
    public fun ouvrirInstallationBootstrap()
}
