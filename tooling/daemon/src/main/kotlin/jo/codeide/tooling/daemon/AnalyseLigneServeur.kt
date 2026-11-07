package jo.codeide.tooling.daemon

/**
 * Analyse PURE d'une ligne de stderr de l'orchestrateur (INC-40, volet C
 * du prompt « retard d'affichage des sorties de build » §6.5) : le
 * [Journal][jo.codeide.tooling.server.Journal] du serveur préfixe chaque
 * ligne de « [gradle-server] [NIVEAU] message » — le niveau est PARSÉ
 * pour journaliser côté application à la BONNE sévérité (plus de WARN
 * factice pour un INFO), le préfixe redondant retiré.
 *
 * Replis : une trace JVM sans préfixe (« Exception… », « at … ») part en
 * [NiveauLigneServeur.ERREUR] ; une ligne nue part en
 * [NiveauLigneServeur.INFO] ; les avertissements « SLF4J(W): … » du
 * shadowJar sans fournisseur (INC-41) rendent `null` — filtrés.
 */
internal fun analyserLigneServeur(ligne: String): LigneServeurAnalysee? {
    if (ligne.startsWith("SLF4J(W):")) {
        return null
    }
    val correspondance = REGEX_LIGNE_SERVEUR.find(ligne)
    if (correspondance != null) {
        val niveau =
            when (correspondance.groupValues[1]) {
                "ERROR" -> NiveauLigneServeur.ERREUR
                "WARN" -> NiveauLigneServeur.AVERTISSEMENT
                "DEBUG" -> NiveauLigneServeur.DEBUG
                else -> NiveauLigneServeur.INFO
            }
        return LigneServeurAnalysee(niveau, ligne.substring(correspondance.range.last + 1).trim())
    }
    if (ligne.startsWith("Exception") || ligne.startsWith("Caused by") || ligne.startsWith("\tat ")) {
        return LigneServeurAnalysee(NiveauLigneServeur.ERREUR, ligne)
    }
    return LigneServeurAnalysee(NiveauLigneServeur.INFO, ligne)
}

/** Niveau applicatif d'une ligne du serveur. */
internal enum class NiveauLigneServeur {
    ERREUR,
    AVERTISSEMENT,
    INFO,
    DEBUG,
}

/** Ligne analysée : niveau applicatif + message DÉPOUILLÉ de son préfixe. */
internal data class LigneServeurAnalysee(val niveau: NiveauLigneServeur, val message: String)

/** Préfixe du Journal du serveur : « [gradle-server] [NIVEAU] ». */
private val REGEX_LIGNE_SERVEUR = Regex("^\\[gradle-server] \\[(DEBUG|INFO|WARN|ERROR)] ")
