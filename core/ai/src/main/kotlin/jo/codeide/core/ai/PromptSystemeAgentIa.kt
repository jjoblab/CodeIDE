package jo.codeide.core.ai

/**
 * Données du prompt système de l'agent (rapport IA, chantier IA-CODEIDE,
 * lot 2, A5) : TOUTES les valeurs sont déjà relatives au projet et déjà
 * lues par l'appelant — le constructeur de prompt est PUR (aucune IO,
 * JVM testable), miroir de [AssemblerContexte].
 *
 * @property objectif objectif de l'utilisateur (déjà trimé, non vide).
 * @property chemins chemins RELATIFS du projet (liste brute du port) —
 * nourrissent la détection du système de build et l'arborescence élaguée.
 * @property cheminFichierActif chemin relatif du fichier ouvert dans
 * l'éditeur, ou `null` (aucun onglet actif).
 * @property selection texte sélectionné dans l'éditeur (contenu
 * explicite de l'utilisateur), ou `null`.
 * @property planPremier mode « Planifier d'abord » : l'agent produit
 * d'abord un plan, l'utilisateur le valide AVANT toute exécution.
 */
public data class DonneesPromptAgentIa(
    public val objectif: String,
    public val chemins: List<String> = emptyList(),
    public val cheminFichierActif: String? = null,
    public val selection: String? = null,
    public val planPremier: Boolean = false,
)

/**
 * Prompt système RICHE de l'agent (rapport IA, lot 2, A5) : rôle, langue,
 * système de build, arborescence élaguée, fichier actif et sélection,
 * règles d'usage des outils — « lire avant de modifier », « petites
 * modifications ciblées », « terminer par un résumé ».
 *
 * Classe DÉDIÉE et testée : le prompt n'est plus forgé en chaîne brute
 * dans le contrôleur. Les sections projet ne partent QUE si leur donnée
 * existe (un projet sans fichier actif n'affiche pas de section vide).
 */
public class PromptSystemeAgentIa {
    /**
     * Construit le prompt système complet.
     *
     * @param donnees entrées déjà lues (chemins, fichier actif, sélection).
     * @return le texte du message système.
     */
    public fun construire(donnees: DonneesPromptAgentIa): String = buildString {
        append(IDENTITE)
        append(REGLES_TRAVAIL)
        append(REGLES_OUTILS)
        appendSectionProjet(donnees)
        if (donnees.planPremier) {
            append(REGLE_PLAN_PREMIER)
        }
        append("Objectif de l'utilisateur :\n")
        append(donnees.objectif)
        append('\n')
    }

    /** Sections « projet » : système de build, arborescence, fichier actif. */
    private fun StringBuilder.appendSectionProjet(donnees: DonneesPromptAgentIa) {
        val systeme = detecterSystemeBuild(donnees.chemins)
        if (systeme != null) {
            append("Système de build du projet : $systeme.\n")
        }
        val arborescence = elaguerArborescence(donnees.chemins)
        if (arborescence != null) {
            append("Arborescence du projet (élaguée) :\n$arborescence\n")
        }
        val actif = donnees.cheminFichierActif
        if (!actif.isNullOrBlank()) {
            append("Fichier actif dans l'éditeur : $actif.\n")
            val selection = donnees.selection?.takeIf { it.isNotBlank() }
            if (selection != null) {
                append("Sélection de l'utilisateur : « ${selection.take(SELECTION_MAX)} ».\n")
            }
        }
    }

    public companion object {
        /** Rôle et langue — la réponse est TOUJOURS en français. */
        public const val IDENTITE: String =
            "Tu es l'agent de développement de CodeIDE, un IDE Android. " +
                "Tu réponds TOUJOURS en français.\n"

        /** Règles de travail : lire avant de modifier, petites modifications, résumé final. */
        public const val REGLES_TRAVAIL: String =
            "Règles de travail :\n" +
                "- LIS avant de modifier : lis un fichier (read_file, read_lines) avant d'y proposer un changement.\n" +
                "- Petites modifications CIBLÉES : change le minimum de fichiers et de lignes ; " +
                "ne réécris jamais un fichier entier quand un extrait suffit.\n" +
                "- Termine par un RÉSUMÉ : fichiers touchés (chemins relatifs), ce que tu as fait, " +
                "ce qui reste à faire.\n"

        /** Règles d'usage des outils (miroir de la table ADR 0125 + A5). */
        public const val REGLES_OUTILS: String =
            "Outils :\n" +
                "- Lecture (sans approbation) : list_files, read_file, read_lines (intervalle de lignes), " +
                "search_text (recherche dans le projet), diagnostics_lsp (problèmes du fichier actif).\n" +
                "- Écriture MISE EN ATTENTE : propose_edit, create_file, delete_file — RIEN n'est écrit " +
                "sans la revue de l'utilisateur.\n" +
                "- Exécution : run_gradle (liste blanche, approbation explicite à chaque appel) ; " +
                "si la tâche échoue, corrige puis réessaie, sans boucler.\n"

        /** Consigne du mode « Planifier d'abord » (A5). */
        public const val REGLE_PLAN_PREMIER: String =
            "MODE PLANIFIER D'ABORD : produis D'ABORD un plan en étapes numérotées " +
                "(tu peux lire le projet, mais ne propose AUCUNE modification) ; " +
                "l'utilisateur validera le plan avant l'exécution.\n"

        /** Plafond de la sélection encadrée dans le prompt (caractères). */
        public const val SELECTION_MAX: Int = 400

        /** Plafond de lignes de l'arborescence élaguée. */
        public const val ARBORESCENCE_MAX: Int = 30

        /**
         * Détecte le système de build depuis la liste des chemins (pur).
         *
         * @return le nom lisible, ou `null` (aucun marqueur connu).
         */
        public fun detecterSystemeBuild(chemins: List<String>): String? {
            val racines = chemins.map { it.substringBefore('/') }.toSet()
            return when {
                "settings.gradle.kts" in racines || "build.gradle.kts" in racines -> "Gradle (Kotlin DSL)"
                "build.gradle" in racines || "settings.gradle" in racines -> "Gradle (Groovy DSL)"
                "pom.xml" in racines -> "Maven"
                "package.json" in racines -> "npm"
                "Cargo.toml" in racines -> "Cargo"
                "go.mod" in racines -> "Go modules"
                else -> null
            }
        }

        /**
         * Élague la liste des chemins en arborescence lisible (pur) :
         * entrées de premier niveau, les dossiers portant leur nombre de
         * fichiers — bornée à [ARBORESCENCE_MAX] lignes.
         *
         * @return le texte de l'arborescence, ou `null` (liste vide).
         */
        public fun elaguerArborescence(chemins: List<String>): String? {
            if (chemins.isEmpty()) {
                return null
            }
            val fichiersRacine = mutableListOf<String>()
            val dossiers = sortedMapOf<String, Int>()
            for (chemin in chemins) {
                val premier = chemin.substringBefore('/')
                if ('/' in chemin) {
                    dossiers[premier] = (dossiers[premier] ?: 0) + 1
                } else {
                    fichiersRacine.add(premier)
                }
            }
            val lignes = mutableListOf<String>()
            for ((dossier, nombre) in dossiers) {
                // Pluriel français honnête : « 1 fichier », « 2 fichiers ».
                val mot = if (nombre > 1) "fichiers" else "fichier"
                lignes.add("$dossier/ ($nombre $mot)")
            }
            fichiersRacine.sorted().forEach { lignes.add(it) }
            val bornees = lignes.take(ARBORESCENCE_MAX)
            val reste = lignes.size - bornees.size
            return if (reste > 0) {
                bornees.joinToString("\n") + "\n… (+$reste autres entrées)"
            } else {
                bornees.joinToString("\n")
            }
        }
    }
}
