package jo.codeide.core.domain

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le port de SAUT AU SOURCE du Centre de sorties (spec SORTIES § 4.3,
 * lot L3) — les positions cliquables extraites du flux (`e: file://…:l:c`
 * Kotlin, `fichier:l:c: error:` Java, `Build file '…' line: l` scripts)
 * ouvrent le fichier à la ligne dans l'éditeur, comme l'onglet Problèmes
 * ([ControleurPanneauEditeur] côté feature:editor) : PAS DE SECOND
 * ENDROIT pour la même information.
 *
 * Le port vit dans `core:domain` (le lecteur ne connaît pas l'éditeur) ;
 * `feature:editor` enregistre son implémentation dans
 * [RegistrePortSautSource] quand son contrôleur est prêt — même patron
 * que le port d'éditeur actif de l'IA (ADR 0122). Port ABSENT (aucun
 * éditeur enregistré, processus recréé) : l'appelant traite l'absence
 * honnêtement (état vide qui dit pourquoi — spec § 5.8), jamais de
 * blocage.
 *
 * Le fichier peut être relatif au projet (`app/src/main/MainActivity.kt`
 * — le cas du câble Kotlin après relecture d'une sortie persistée) ou
 * absolu : la résolution revient à l'implémentation (elle connaît le
 * projet ouvert).
 */
public interface PortSautSource {

    /**
     * Ouvre le fichier à la position demandée.
     *
     * @param fichier chemin du fichier (relatif au projet ou absolu).
     * @param ligne numéro de ligne 1-based tel qu'imprimé par l'outil.
     * @param colonne numéro de colonne 1-based si l'outil l'imprime.
     * @return `true` si le saut a pu être effectué (éditeur ouvert,
     *         fichier trouvé), `false` sinon — l'appelant affiche alors
     *         pourquoi le saut n'a pas eu lieu.
     */
    public fun ouvrir(fichier: String, ligne: Int, colonne: Int?): Boolean
}

/**
 * Le registre singleton du port de saut au source (patron ADR 0122) — le
 * contrôleur d'éditeur enregistre son implémentation à l'initialisation
 * de l'espace et la retire à sa fermeture ; le lecteur du Centre de
 * sorties lit au moment du geste. UN seul port à la fois (l'éditeur
 * actif) : l'enregistrement REMPLACE le précédent. Thread : champ
 * volatile (même discipline que [RegistrePortEditeurActif]).
 */
@Singleton
public class RegistrePortSautSource
@Inject
constructor() {
    @Volatile
    private var port: PortSautSource? = null

    /** Enregistre (ou remplace) le port de saut ; `null` le retire. */
    public fun enregistrer(port: PortSautSource?) {
        this.port = port
    }

    /** Le port de saut de l'éditeur actif, ou `null` (aucun éditeur). */
    public fun actif(): PortSautSource? = port
}
