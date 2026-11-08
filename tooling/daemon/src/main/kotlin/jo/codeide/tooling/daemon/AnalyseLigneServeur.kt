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
    // INC-41 : avertissements SLF4J du shadowJar sans fournisseur — filtrés.
    if (ligne.startsWith("SLF4J(W):")) {
        return null
    }
    val correspondance = REGEX_LIGNE_SERVEUR.find(ligne)
    return when {
        correspondance != null ->
            LigneServeurAnalysee(
                niveau = niveauDuServeur(correspondance.groupValues[1]),
                message = ligne.substring(correspondance.range.last + 1).trim(),
            )

        estTraceJvm(ligne) -> LigneServeurAnalysee(NiveauLigneServeur.ERREUR, ligne)

        else -> LigneServeurAnalysee(NiveauLigneServeur.INFO, ligne)
    }
}

/** Niveau applicatif du niveau écrit par le Journal du serveur. */
private fun niveauDuServeur(niveau: String): NiveauLigneServeur = when (niveau) {
    "ERROR" -> NiveauLigneServeur.ERREUR
    "WARN" -> NiveauLigneServeur.AVERTISSEMENT
    "DEBUG" -> NiveauLigneServeur.DEBUG
    else -> NiveauLigneServeur.INFO
}

/** Une ligne sans préfixe est-elle une trace JVM (Exception / at / Caused by) ? */
private fun estTraceJvm(ligne: String): Boolean =
    ligne.startsWith("Exception") || ligne.startsWith("Caused by") || ligne.startsWith("\tat ")

/**
 * Journalise une ligne du serveur à SON niveau (INC-40, volet C du prompt
 * « retard d'affichage des sorties de build » §6.5) : le [Journal] du
 * serveur préfixe chaque ligne de stderr de « [gradle-server] [NIVEAU] » —
 * l'ancien code envoyait TOUT en `WARN` : l'export de logs et l'écran
 * Diagnostic croulaient d'avertissements factices (« [gradle-server]
 * [INFO] handshake accepté » au niveau WARN) et les VRAIS avertissements
 * ne se distinguaient plus. Le préfixe redondant est retiré, une trace JVM
 * sans préfixe part en erreur, une ligne nue en info.
 */
internal fun journaliserLigneServeur(journal: jo.codeide.core.domain.AppLogger, ligne: String) {
    val analysee = analyserLigneServeur(ligne) ?: return
    when (analysee.niveau) {
        NiveauLigneServeur.ERREUR -> journal.e(TAG_PROCESSUS) { analysee.message }
        NiveauLigneServeur.AVERTISSEMENT -> journal.w(TAG_PROCESSUS) { analysee.message }
        NiveauLigneServeur.INFO -> journal.i(TAG_PROCESSUS) { analysee.message }
        NiveauLigneServeur.DEBUG -> journal.d(TAG_PROCESSUS) { analysee.message }
    }
}

/** Étiquette des lignes du processus orchestrateur (journal applicatif). */
internal const val TAG_PROCESSUS = "gradle-server"

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
