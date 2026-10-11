package jo.codeide.feature.diagnostics

import jo.codeide.core.domain.LogRedactor
import jo.codeide.core.domain.RapportDiagnosticGit
import jo.codeide.core.domain.ResultatCommandeGit

/**
 * Formateur du rapport « Diagnostic Git » (v0.90.1, mission « section Git
 * figée » étape A) — objet pur, testé unitairement.
 *
 * Deux sorties, deux niveaux de confidence :
 * - [formater] (affichage) : valeurs réelles — l'utilisateur lit SON
 *   appareil, les chemins réels sont la matière du diagnostic (chemin
 *   du binaire, point de montage, résolution FUSE) ;
 * - [formaterExpurge] (copie) : [LogRedactor] + masquage des valeurs de
 *   configuration sensibles (identité, jetons) — la sortie collée ne
 *   contient AUCUNE donnée personnelle (règle 15 du prompt maître).
 *
 * Le formateur reste factuel : aucune interprétation (« uid différent ⇒
 * dubious ownership ») — le lecteur du rapport conclut, pas l'outil.
 */
internal object FormateurDiagnosticGit {
    /** Marqueur d'une valeur indisponible (sonde en échec). */
    private const val INDISPONIBLE = "(indisponible)"

    /** Marqueur d'une sortie vide. */
    private const val VIDE = "(vide)"

    /**
     * Valeurs de configuration git masquées dans la COPIE : identité et
     * secrets — le reste (safe.directory, core.*, remote.*) est inerte.
     */
    private val CLES_CONFIG_SENSIBLES = setOf("user.name", "user.email", "github.user")

    /**
     * Morceaux de clé de configuration TOUJOURS masqués dans la copie
     * (jetons, mots de passe, en-têtes d'authentification HTTP).
     */
    private val MORCEAUX_CLES_SENSIBLES = listOf("token", "password", "secret", "extraheader", "credential")

    /**
     * Origine de configuration au format `file:/chemin` (sortie
     * `--show-origin` de git — un SEUL slash) : [LogRedactor] masque
     * `file://` (deux slashes) et les chemins nus, mais `file:/x` lui
     * échappe (le slash suit « : », exclus par son lookbehind) — éprouvé
     * par le test de la copie, corrigé ici, dans le formateur (le
     * redacteur du domaine reste inchangé — contrat doré).
     */
    private val regexOrigineConfig = Regex("""\bfile:/\S*""")

    /**
     * Formate le rapport pour l'AFFICHAGE (valeurs réelles).
     */
    internal fun formater(rapport: RapportDiagnosticGit): String {
        val lignes = mutableListOf<String>()
        lignes += TITRE
        lignes += "Projet : ${rapport.nomProjet ?: INDISPONIBLE}"
        lignes += "Chemin FUSE : ${rapport.cheminFuse ?: INDISPONIBLE}"
        lignes += ""
        lignes += "[Binaire git]"
        lignes += "  Chemin : ${rapport.cheminBinaire ?: INDISPONIBLE}"
        lignes += "  Version : ${rapport.versionGit ?: INDISPONIBLE}"
        lignes += ""
        lignes += "[Propriété du dossier]"
        lignes += "  uid effectif (application) : ${rapport.uidEffectif ?: INDISPONIBLE}"
        lignes += "  uid propriétaire du dossier : ${rapport.uidProprietaireDossier ?: INDISPONIBLE}"
        lignes += ""
        lignes += "[Point de montage]"
        lignes += "  Montage : ${rapport.pointDeMontage ?: INDISPONIBLE}"
        lignes += "  Type : ${rapport.typeSystemeFichiers ?: INDISPONIBLE}"
        lignes += ""
        lignes += "[git rev-parse --is-inside-work-tree]"
        lignes += texteCommande(rapport.revParse)
        lignes += ""
        lignes += "[git config --list --show-origin]"
        lignes += texteCommande(rapport.configList)
        lignes += ""
        lignes += "[Environnement]"
        if (rapport.environnement.isEmpty()) {
            lignes += "  $VIDE"
        } else {
            rapport.environnement.forEach { (cle, valeur) -> lignes += "  $cle=$valeur" }
        }
        return lignes.joinToString("\n")
    }

    /**
     * Formate le rapport pour la COPIE (presse-papiers, partage) :
     * expurgé — chemins et courriels masqués par [LogRedactor], nom de
     * projet et valeurs de configuration sensibles masquées ici.
     *
     * La copie reste diagnostique : codes de sortie, messages d'erreur
     * (le type de refus survit au masquage du chemin), uid et
     * environnement y figurent.
     */
    internal fun formaterExpurge(rapport: RapportDiagnosticGit): String {
        val brut = formater(rapport)
        val lignes = brut.lines().map { ligne -> expurgerLigne(ligne) }
        return lignes.joinToString("\n")
    }

    /** Bloc d'une commande : code, stdout, stderr. */
    private fun texteCommande(resultat: ResultatCommandeGit?): String {
        if (resultat == null) return "  Non exécuté (pas de projet / chemin irrésolvable)"
        val code = resultat.code?.toString() ?: INDISPONIBLE
        return listOf(
            "  Code de sortie : $code",
            "  stdout : ${resultat.sortieStandard.ifBlank { VIDE }}",
            "  stderr : ${resultat.sortieErreur.ifBlank { VIDE }}",
        ).joinToString("\n")
    }

    /** Expurgation d'une ligne : projet, identité, origines, puis [LogRedactor]. */
    private fun expurgerLigne(ligne: String): String {
        val sansProjet = masquerValeur(ligne, prefixeLigne = "Projet : ")
        val sansIdentite = masquerConfigSensible(sansProjet)
        val sansOrigines = regexOrigineConfig.replace(sansIdentite, "file:<origine>")
        return LogRedactor.redact(sansOrigines)
    }

    /** Remplace la valeur après [prefixeLigne] (nom de projet). */
    private fun masquerValeur(
        ligne: String,
        prefixeLigne: String,
    ): String =
        if (ligne.startsWith(prefixeLigne) && ligne.length > prefixeLigne.length &&
            !ligne.substring(prefixeLigne.length).startsWith(INDISPONIBLE)
        ) {
            "$prefixeLigne<projet>"
        } else {
            ligne
        }

    /**
     * Masque la valeur d'une ligne de configuration sensible — format
     * `…[origine]  clé=valeur` (sortie `--show-origin`).
     */
    private fun masquerConfigSensible(ligne: String): String {
        val indiceEgal = ligne.lastIndexOf('=')
        if (indiceEgal < 0) return ligne
        val cle = ligne.substring(0, indiceEgal).substringAfterLast(' ').lowercase()
        val sensible =
            cle in CLES_CONFIG_SENSIBLES || MORCEAUX_CLES_SENSIBLES.any { cle.contains(it) }
        return if (sensible) ligne.substring(0, indiceEgal + 1) + "<masqué>" else ligne
    }

    /** En-tête du rapport. */
    private const val TITRE = "=== Diagnostic Git (CodeIDE) ==="
}
