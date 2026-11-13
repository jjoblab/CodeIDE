package jo.codeide.tooling.daemon

/**
 * Paramètres d'instrumentation du transport, lus à CHAQUE lancement du
 * process orchestrateur (Phase 1 du diagnostic « retard d'affichage »,
 * ADR 0152) — projection pure du réglage utilisateur ([AppSettings]) vers
 * la ligne de commande du serveur ([argumentsInstru]) et le drapeau de la
 * sonde du fil lecteur ([SondeLecteurTransport]).
 *
 * Un changement de réglage s'applique à la **prochaine vie** de
 * l'orchestrateur (redémarrage de l'app ou de la chaîne d'outils) — le
 * daemon relit ces valeurs à chaque tentative de lancement, même contrat
 * que l'injection applog (ADR 0103).
 *
 * @property instrumentationTransport arme `--instru-transport` côté serveur
 *           ET la sonde T3 détaillée du fil lecteur côté client — une
 *           seule vérité, les deux bouts armés ensemble.
 * @property bancSocketSeul arme le banc « socket seul » (§3.1b) : le
 *           serveur émet la séquence du banc SANS Gradle au démarrage.
 * @property bancLignes lignes émises par le banc (défaut 60 — profil du
 *           journal du propriétaire).
 * @property bancCadenceMs cadence d'émission en ms (0 = rafale).
 * @property bancLongueurLigne longueur des lignes émises.
 * @property bancContenu contenu des lignes : `plain`, `ansi`, `utf8` ou
 *           `multi` — le serveur valide et replie sur `plain`.
 */
internal data class ParametresInstru(
    val instrumentationTransport: Boolean,
    val bancSocketSeul: Boolean,
    val bancLignes: Int,
    val bancCadenceMs: Int,
    val bancLongueurLigne: Int,
    val bancContenu: String,
) {
    internal companion object {
        /** Tout coupé — valeur de test et d'un réglage jamais touché. */
        internal val INACTIFS = ParametresInstru(
            instrumentationTransport = false,
            bancSocketSeul = false,
            bancLignes = 60,
            bancCadenceMs = 0,
            bancLongueurLigne = 80,
            bancContenu = "plain",
        )
    }
}
