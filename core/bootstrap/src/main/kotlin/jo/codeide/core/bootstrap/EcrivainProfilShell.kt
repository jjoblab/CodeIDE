package jo.codeide.core.bootstrap

import jo.codeide.core.domain.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Écrivain du profil shell de CodeIDE (correctifs C1/C2 du prompt Terminal
 * — retour utilisateur : « aucune invite personnalisée, les chemins ne sont
 * visibles nulle part » ; v0.37.3 — retour d'appareil réel : « ce n'est pas
 * le vrai chemin de gradle ») :
 *
 * - `$PREFIX/etc/codeide.sh`, régénéré ENTIÈREMENT à chaque écriture
 *   (idempotent par construction : jamais de bloc marqué à parser dans
 *   un fichier existant) : `PS1` personnalisé (« codeide » à la place de
 *   l'utilisateur/hôte par défaut), branche Git courante dans l'invite
 *   (silencieuse hors dépôt), et message de bienvenue montrant l'état RÉEL
 *   des outils (JDK, SDK Android, Gradle) ;
 * - **v0.37.3 — vrais chemins** : la ligne Gradle ne montre PLUS le script
 *   de découverte `$PREFIX/bin/gradle` (`command -v gradle` — trompeur) mais
 *   la DISTRIBUTION réellement présente : le wrapper du projet, sinon la
 *   distribution du cache `wrapper/dists` (TROIS niveaux — posée par le
 *   tooling), sinon `opt/` ; la ligne Android montre `$ANDROID_HOME` ou le
 *   SDK du HOME (`android-sdk/`, posé par la commande `android-sdk`) ;
 * - **v0.51.0 — pont `ide-environment.properties`** : le profil respecte
 *   les clés posées par l'installeur autonome du dépôt `codeide-tools`
 *   (`codeidesetup`, exécuté au terminal sans passer par l'app) — chaque
 *   clé de la liste blanche (`JAVA_HOME`, `ANDROID_SDK_ROOT`,
 *   `ANDROID_HOME`) ne passe QUE si absente de l'environnement : le
 *   ballotage de l'app reste la source la plus fraîche, le fichier
 *   comble les blancs (JDK d'un emplacement non scanné, par exemple) ;
 * - LA ligne d'inclusion dans le `.bashrc` du HOME du bootstrap, ajoutée
 *   SEULEMENT si absente (jamais dupliquée — comparaison ligne à ligne sur
 *   le texte rogné, pas un grep naïf) ;
 * - l'affichage de bienvenue est gardé par l'interactivité du shell
 *   (`case $- in *i*)` — le test POSIX canonique) : silencieux pour les
 *   shells non interactifs (scripts lancés par le tooling).
 *
 * Le profil est VERSIONNÉ ([VersionneurScriptsTerminal]) : l'app le
 * régénère quand son contenu évolue — une mise à jour de l'application
 * prend effet SANS réinstaller le bootstrap.
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
     * (dollar du shell) diffèrent de la source shell littérale. v0.37.3 :
     * les lignes Gradle et Android résolvent les VRAIS emplacements (cache
     * du wrapper à trois niveaux, SDK du HOME).
     *
     * Exemption ciblée (LongMethod) : profil shell d'un seul tenant — les
     * fonctions `__codeide_*` s'appellent depuis le `case` de bienvenue ;
     * découper le littéral fragmenterait la lecture unitaire du shell.
     */
    @Suppress("LongMethod")
    private fun contenuProfil(): String {
        val dollar = "$"
        return """
            # Profil CodeIDE — régénéré intégralement, VERSIONNÉ (l'app le
            # réécrit quand son contenu évolue : ne pas éditer).

            # Invite personnalisée : codeide:<répertoire> (<branche Git>)$dollar —
            # exactement la forme de l'ancien projet (C1).
            PS1='\[\e[1;32m\]codeide\[\e[0m\]:\[\e[34m\]\w\[\e[33m\]$(__codeide_git_branch)\[\e[0m\]\$ '

            # Branche Git courante, silencieuse hors dépôt (C1).
            __codeide_git_branch() {
              branche=$(git symbolic-ref --short HEAD 2>/dev/null) || return
              printf ' (%s)' "${dollar}branche"
            }

            # Gradle réellement présent (v0.37.3 — fin du faux chemin
            # ${dollar}PREFIX/bin/gradle) : wrapper du projet, sinon distribution du
            # cache wrapper/dists (TROIS niveaux : nom/empreinte/gradle-X),
            # sinon une distribution opt/ — jamais le script de découverte.
            __codeide_gradle_info() {
              if [ -x "./gradlew" ]; then
                printf '%s' "wrapper du projet (./gradlew)"
                return 0
              fi
              dist=$(ls -dt "${dollar}GRADLE_USER_HOME"/wrapper/dists/*/*/gradle-*/ 2>/dev/null | head -n1)
              if [ -n "${dollar}dist" ] && [ -x "${dollar}dist/bin/gradle" ]; then
                printf '%s' "$(basename "${dollar}dist") — ${dollar}dist"
                return 0
              fi
              dist=$(ls -dt "${dollar}PREFIX"/opt/gradle/*/ "${dollar}PREFIX"/opt/gradle-*/ 2>/dev/null | head -n1)
              if [ -n "${dollar}dist" ] && [ -x "${dollar}dist/bin/gradle" ]; then
                printf '%s' "$(basename "${dollar}dist") — ${dollar}dist"
                return 0
              fi
              printf '%s' "aucune — commande gradle ou sync depuis l'app"
            }

            # SDK Android réellement présent (v0.37.3) : ANDROID_HOME injecté
            # par CodeIDE quand il est détecté, sinon le SDK du HOME posé par
            # la commande android-sdk.
            __codeide_android_info() {
              if [ -n "${dollar}ANDROID_HOME" ]; then
                printf '%s' "${dollar}ANDROID_HOME"
                return 0
              fi
              if [ -d "${dollar}HOME/android-sdk/platforms" ]; then
                printf '%s' "${dollar}HOME/android-sdk (rouvre la session pour ANDROID_HOME)"
                return 0
              fi
              printf '%s' "non installé — commande : android-sdk installer"
            }

            # v0.51.0 — PONT avec l'installeur du dépôt codeide-tools : le
            # script `codeidesetup` (curl … | bash, sans passer par l'app)
            # écrit JAVA_HOME et ANDROID_SDK_ROOT dans
            # ${dollar}PREFIX/etc/ide-environment.properties. Le profil respecte ce
            # fichier SANS écraser ce que CodeIDE a déjà injecté dans la
            # session (le ballotage de l'app est la source la plus fraîche) :
            # chaque clé de la LISTE BLANCHE ne passe QUE si absente de
            # l'environnement. Lecture ligne à ligne CLE=VALEUR — jamais de
            # `.` sourcé (une valeur hostile ne serait pas évaluée) et
            # commentaires (#) ignorés.
            __codeide_environment_props() {
              [ -f "${dollar}PREFIX/etc/ide-environment.properties" ] || return 0
              while IFS='=' read -r cle valeur; do
                case "${dollar}cle" in
                  JAVA_HOME)
                    [ -n "${dollar}JAVA_HOME" ] || export JAVA_HOME="${dollar}valeur"
                    ;;
                  ANDROID_SDK_ROOT)
                    [ -n "${dollar}ANDROID_SDK_ROOT" ] || export ANDROID_SDK_ROOT="${dollar}valeur"
                    ;;
                  ANDROID_HOME)
                    [ -n "${dollar}ANDROID_HOME" ] || export ANDROID_HOME="${dollar}valeur"
                    ;;
                esac
              done < "${dollar}PREFIX/etc/ide-environment.properties"
              return 0
            }
            __codeide_environment_props
            unset __codeide_environment_props

            # Message de bienvenue : l'état RÉEL des outils du bootstrap —
            # JAVA_HOME et ANDROID_HOME sont injectés par CodeIDE dans
            # chaque session, le profil les rend enfin VISIBLES (C2).
            # Garde : $- contient « i » en session INTERACTIVE seulement —
            # silencieux pour les shells non interactifs (scripts du
            # tooling).
            case ${'$'}- in
              *i*)
                printf '\033[36m╭──────────────────────────────────────────╮\033[0m\n'
                printf '\033[36m│\033[0m  \033[1mCodeIDE\033[0m — terminal intégré              \033[36m│\033[0m\n'
                printf '\033[36m╰──────────────────────────────────────────╯\033[0m\n'
                printf '  \033[32mJDK\033[0m      : %s\n' "$dollar{JAVA_HOME:-non installé}"
                printf '  \033[32mAndroid\033[0m  : %s\n' "$(__codeide_android_info)"
                printf '  \033[32mGradle\033[0m   : %s\n' "$(__codeide_gradle_info)"
                printf '  \033[2mCommandes : gradle · android-sdk\033[0m\n'
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
