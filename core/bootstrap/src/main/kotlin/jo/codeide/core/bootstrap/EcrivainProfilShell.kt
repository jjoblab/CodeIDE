package jo.codeide.core.bootstrap

import jo.codeide.core.domain.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Écrivain du profil shell de CodeIDE (correctifs C1/C2 du prompt Terminal
 * — retour utilisateur : « aucune invite personnalisée, les chemins ne sont
 * visibles nulle part ») :
 *
 * - `$PREFIX/etc/codeide.sh`, régénéré ENTIÈREMENT à chaque installation de
 *   base (idempotent par construction : jamais de bloc marqué à parser dans
 *   un fichier existant) : `PS1` personnalisé (« codeide » à la place de
 *   l'utilisateur/hôte par défaut), branche Git courante dans l'invite
 *   (silencieuse hors dépôt), et message de bienvenue montrant l'état RÉEL
 *   des outils (JDK, SDK Android, Gradle) — `JAVA_HOME`/`ANDROID_HOME` sont
 *   déjà injectés dans chaque session par `EnvironnementProcessus`, le
 *   profil ne fait que les RENDRE visibles ;
 * - LA ligne d'inclusion dans le `.bashrc` du HOME du bootstrap, ajoutée
 *   SEULEMENT si absente (jamais dupliquée — comparaison ligne à ligne sur
 *   le texte rogné, pas un grep naïf) ;
 * - l'affichage de bienvenue est gardé par l'interactivité du shell
 *   (`case $- in *i*)` — le test POSIX canonique) : silencieux pour les
 *   shells non interactifs (scripts lancés par le tooling). NB : le
 *   `[ -n "$PS1" ]` du plan initial aurait TOUJOURS affiché la bannière —
 *   le profil pose PS1 juste avant, la garde n'aurait jamais vu un PS1
 *   vide ; `$-` reflète le mode du shell, pas une variable qu'on vient
 *   d'écraser.
 *
 * Aucun changement côté `TerminalSession`/`core:terminal-runtime` : le
 * mécanisme passe entièrement par les fichiers shell sourcés par bash.
 *
 * L'écriture est atomique (fichier temporaire + renommage) : un profil à
 * moitié écrit casserait l'invite de la session suivante.
 *
 * @param journal journal applicatif (règle 15 : identifiants seulement —
 *        aucun chemin de projet n'y paraît).
 */
internal class EcrivainProfilShell(
    private val journal: AppLogger,
) {
    /**
     * Écrit le profil et son inclusion dans le `.bashrc`.
     *
     * @param racine racine du bootstrap (`filesDir` — le préfixe et le HOME
     *        s'en dérivent, mêmes dispositions que l'installateur).
     */
    suspend fun ecrire(racine: File) {
        withContext(Dispatchers.IO) {
            val prefixe = DispositionsBootstrap.prefix(racine)
            val home = DispositionsBootstrap.home(racine)

            ecrireProfil(prefixe)
            ajouterInclusionBashrc(home)

            journal.i(TAG) { "profil shell CodeIDE posé (codeide.sh + .bashrc)" }
        }
    }

    /** Régénère `$PREFIX/etc/codeide.sh` en entier (idempotent). */
    private fun ecrireProfil(prefixe: File) {
        val profil = File(prefixe, "etc/codeide.sh")
        profil.parentFile?.mkdirs()
        val temporaire = File(profil.parentFile, "codeide.sh.codeide")
        try {
            temporaire.bufferedWriter().use { ecrivain -> ecrivain.write(contenuProfil()) }
            if (profil.exists() && !profil.delete()) {
                throw IOException("codeide.sh existant non remplaçable")
            }
            if (!temporaire.renameTo(profil)) {
                throw IOException("bascule du codeide.sh impossible")
            }
        } finally {
            temporaire.delete()
        }
    }

    /** Ajoute l'inclusion au `.bashrc` — seulement si absente. */
    private fun ajouterInclusionBashrc(home: File) {
        val bashrc = File(home, ".bashrc")
        if (bashrc.isFile && bashrc.readLines().any { it.trim() == LIGNE_INCLUSION }) {
            return
        }
        bashrc.parentFile?.mkdirs()
        // Ajout en fin de fichier avec saut de garde : coller l'inclusion à
        // une dernière ligne non terminée la casserait (le HOME part vide —
        // le premier .bashrc ne contient QUE cette ligne).
        if (bashrc.exists() && bashrc.length() > 0) bashrc.appendText("\n")
        bashrc.appendText("$LIGNE_INCLUSION\n")
    }

    /**
     * Contenu canonique du profil, régénéré en entier — jamais patché.
     * La mise en page de la bannière reprend celle de l'ancien projet
     * (`BootstrapInstaller.java:756` et suivantes), référence de
     * comportement des correctifs C1/C2 ; seuls les ÉCHAPPEMENTS Kotlin
     * (dollar du shell) diffèrent de la source shell littérale.
     */
    private fun contenuProfil(): String {
        val dollar = "$"
        return """
            # Profil CodeIDE — régénéré intégralement à chaque installation
            # de base (ne pas éditer : la réinstallation le réécrira).

            # Invite personnalisée : codeide:<répertoire> (<branche Git>)$dollar —
            # exactement la forme de l'ancien projet (C1).
            PS1='\[\e[1;32m\]codeide\[\e[0m\]:\[\e[34m\]\w\[\e[33m\]$(__codeide_git_branch)\[\e[0m\]\$ '

            # Branche Git courante, silencieuse hors dépôt (C1).
            __codeide_git_branch() {
              branche=$(git symbolic-ref --short HEAD 2>/dev/null) || return
              printf ' (%s)' "${dollar}branche"
            }

            # Message de bienvenue : l'état RÉEL des outils du bootstrap —
            # JAVA_HOME et ANDROID_HOME sont injectés par CodeIDE dans
            # chaque session, le profil les rend enfin VISIBLES (C2).
            # Garde : $- contient « i » en session INTERACTIVE seulement —
            # silencieux pour les shells non interactifs (scripts du
            # tooling). ([ -n « PS1 » ] ne convient pas : le profil vient de
            # POSER PS1, la garde serait toujours vraie.)
            case ${'$'}- in
              *i*)
                printf '\033[36m╭──────────────────────────────────────────╮\033[0m\n'
                printf '\033[36m│\033[0m  \033[1mCodeIDE\033[0m — terminal intégré              \033[36m│\033[0m\n'
                printf '\033[36m╰──────────────────────────────────────────╯\033[0m\n'
                printf '  \033[32mJDK\033[0m      : %s\n' "$dollar{JAVA_HOME:-non installé}"
                printf '  \033[32mAndroid\033[0m  : %s\n' "$dollar{ANDROID_HOME:-non installé}"
                printf '  \033[32mGradle\033[0m   : %s\n' "$(command -v gradle 2>/dev/null || echo 'via ./gradlew dans un projet')"
                printf '\n'
                ;;
            esac
            """.trimIndent() + "\n"
    }

    private companion object {
        private const val TAG = "ProfilShell"

        /** Inclusion posée dans le `.bashrc` (jamais dupliquée). */
        private const val LIGNE_INCLUSION =
            "[ -f \"\$PREFIX/etc/codeide.sh\" ] && . \"\$PREFIX/etc/codeide.sh\""
    }
}
