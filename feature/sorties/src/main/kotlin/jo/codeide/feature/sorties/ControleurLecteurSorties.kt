package jo.codeide.feature.sorties

import jo.codeide.core.domain.FiltreSorties

/**
 * La façade publique du lecteur pour ses surfaces HÔTES (lot L4, ADR
 * 0162) — les commandes du composant sans exposer le ViewModel interne :
 * l'écran Journaux de `feature:diagnostics` (consommateur du même
 * composant — « pas un second afficheur ») et le panneau compact
 * pilotent le lecteur par CE contrat, jamais par un type interne.
 *
 * La surface reste MINIMALE et honnête : le lecteur rend déjà les
 * bandeaux, la recherche n/N et le suivi — l'hôte ne touche que ce qui
 * lui appartient (ses propres contrôles persistés, ses propres actions).
 */
public interface ControleurLecteurSorties {
    /** Définit la recherche du lecteur (motif, regex optionnelle — un
     *  motif invalide est signalé en ligne, jamais d'exception). */
    public fun definirRecherche(texte: String, regex: Boolean)

    /** Définit le filtre unifié (ADR 0160) — le tampon ne bouge pas,
     *  les lignes masquées sont comptées et annoncées. */
    public fun definirFiltre(filtre: FiltreSorties)

    /** Effacement explicite du tampon (jamais automatique — règle 14). */
    public fun effacer()

    /** Bascule la pause du direct (le tampon ingère, la vue fige ; la
     *  reprise livre le delta EN UNE fois — spec SORTIES § 3.3). */
    public fun basculerPause()

    /** Le texte PUR des dernières rangées visibles (pré-remplissage
     *  « Expliquer » du chrome logcat — fenêtre bornée, jamais
     *  auto-envoyée), ou `null` si rien n'est affiché. */
    public fun texteVisible(dernieres: Int): String?
}
