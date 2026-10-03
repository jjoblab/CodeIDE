package jo.codeide.core.bootstrap

import jo.codeide.core.domain.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Écrivain de la commande `android-sdk` du terminal (v0.37.3 — retour
 * utilisateur : « ajoute la section pour l'installation du SDK Android,
 * cmdline-tools, etc. » ; v0.47.0 — correctif de la disposition sur
 * première installation : le parent `cmdline-tools/` est créé avant le
 * déplacement, le `mv` n'échoue plus après le téléchargement).
 *
 * Le dépôt APT `codeide-packages` ne fournit AUCUN paquet `android-sdk`
 * (ADR 0032) : l'installation passe par les **commandline-tools** officiels
 * de Google (un zip pur Java — `sdkmanager` est un script shell qui pilote
 * une JVM, aucune dépendance à l'architecture). La commande posée ici
 * installe et pilote le SDK :
 *
 * - `android-sdk` / `android-sdk statut` : état réel (emplacement,
 *   plateformes, build-tools, Java) ;
 * - `android-sdk installer [paquets…]` : téléchargement des cmdline-tools,
 *   acceptation des licences, installation des paquets — par défaut
 *   `platform-tools`, `platforms;android-37.2` et `build-tools;37.0.0`
 *   (chemins VÉRIFIÉS sur `repository2-3.xml` de dl.google.com le
 *   2026-09-28, alignés sur le compileSdk de l'app) ;
 * - `android-sdk sdkmanager …` : relais direct vers l'outil ;
 * - `android-sdk desinstaller` : suppression propre.
 *
 * **Disposition (retour d'appareil réel)** : le SDK vit sous le HOME du
 * shell (`home/android-sdk`) — c'est là que [LocalisationOutils.trouverAndroidHome]
 * le retrouve et que `EnvironnementProcessus` injecte `ANDROID_HOME` aux
 * sessions suivantes. Les paquets du préfixe restent prioritaires si le
 * dépôt APT publiait un jour le SDK.
 *
 * **Partis pris techniques** :
 * - `sdkmanager` exige une JVM : le JDK du bootstrap est résolu depuis
 *   l'environnement de session (`JAVA_HOME` injecté par l'app), puis
 *   `PATH`, puis les candidats du préfixe (`lib/jvm`, `opt`) — jamais de
 *   devine muette, un message actionnable sinon ;
 * - le téléchargement utilise `curl` (présent dans le bootstrap type
 *   Termux), en repli `wget` ;
 * - l'extraction préfère `unzip`, en repli **`jar -xf` du JDK** (le zip
 *   est un format de jar — zéro dépendance supplémentaire) ;
 * - l'archive Google extrait un dossier racine `cmdline-tools/` : il est
 *   déplacé vers la disposition canonique `cmdline-tools/latest/` exigée
 *   par `sdkmanager` pour se localiser lui-même — le répertoire PARENT
 *   `cmdline-tools/` est créé AVANT le déplacement (v0.47.0, retour
 *   d'appareil réel : « mv: cannot move
 *   '…/.staging-cmdline-tools/cmdline-tools' to
 *   '…/android-sdk/cmdline-tools/latest': No such file or directory » —
 *   sur une PREMIÈRE installation le parent n'existe pas et le `mv`
 *   échouait APRÈS le téléchargement de 172,6 Mio, un gaspillage réseau
 *   que `mkdir -p` élimine pour toujours).
 *
 * Le script est VERSIONNÉ ([VersionneurScriptsTerminal]) : l'app le
 * régénère quand son contenu évolue — jamais de réinstallation du
 * bootstrap pour un changement de script. Le shebang pointe vers le `sh`
 * DU bootstrap (chemin absolu calculé à l'écriture) ; bit 0755 via
 * [OperationsSysteme.chmod], précédent `Aapt2Deployeur`.
 *
 * @param operations opérations système natives (chmod).
 * @param journal journal applicatif (règle 15 : identifiants seulement).
 */
internal class EcrivainSdkAndroidCli(
    private val operations: OperationsSysteme,
    private val journal: AppLogger,
) {
    /**
     * Écrit (ou réécrit — idempotent par contenu) la commande `android-sdk`.
     *
     * @param racine racine du bootstrap (`filesDir` — le préfixe et le HOME
     *        s'en dérivent, le shebang et les chemins de repli y sont résolus
     *        en absolu).
     */
    suspend fun ecrire(racine: File) {
        withContext(Dispatchers.IO) {
            val prefixe = DispositionsBootstrap.prefix(racine)
            val cible = File(prefixe, "bin/android-sdk")
            cible.parentFile?.mkdirs()

            val attendu =
                script(
                    shebang = "#!${File(prefixe, "bin/sh").absolutePath}",
                    prefixeAbsolu = prefixe.absolutePath,
                    homeAbsolu = DispositionsBootstrap.home(racine).absolutePath,
                )
            if (cible.isFile && cible.readText() == attendu && cible.canExecute()) {
                return@withContext
            }

            val temporaire = File(cible.parentFile, "android-sdk.codeide")
            try {
                temporaire.bufferedWriter().use { ecrivain -> ecrivain.write(attendu) }
                // 0o755 : lisible et exécutable par tous, inscriptible par
                // l'app seule — même pose que les binaires du bootstrap.
                operations.chmod(temporaire, MODE_EXECUTABLE_PARTAGE)
                if (cible.exists() && !cible.delete()) {
                    throw IOException("android-sdk existant non remplaçable")
                }
                if (!temporaire.renameTo(cible)) {
                    throw IOException("bascule du android-sdk impossible")
                }
            } finally {
                temporaire.delete()
            }

            journal.i(TAG) { "commande android-sdk posée (cmdline-tools + sdkmanager)" }
        }
    }

    /**
     * Contenu canonique du script d'installation et de pilotage du SDK.
     *
     * @param shebang shebang absolu vers le `sh` du bootstrap.
     * @param prefixeAbsolu préfixe du bootstrap (repli de `PREFIX` hors
     *        session pilotée par l'app).
     * @param homeAbsolu HOME du shell (repli de `HOME`, même règle).
     *
     * Exemption ciblée (LongMethod) : littéral de script shell d'un seul
     * tenant — sous-commandes shell qui s'appellent entre elles (`resoudre_java`
     * appelée par `cmd_installer`, garde `sdk_installe` du `case` final) ; le
     * découper en fragments Kotlin casserait la lecture unitaire du shell.
     */
    @Suppress("LongMethod")
    private fun script(
        shebang: String,
        prefixeAbsolu: String,
        homeAbsolu: String,
    ): String {
        val dollar = "$"
        return """
            $shebang
            # Commande android-sdk de CodeIDE — installation et pilotage du SDK
            # Android (v0.37.3 ; script VERSIONNÉ : l'app le régénère quand son
            # contenu évolue — ne pas éditer).
            #
            # Le SDK vit sous le HOME du shell : android-sdk/ — c'est là que
            # l'application le localise (ANDROID_HOME injecté aux sessions
            # suivantes, l'écran de configuration du tooling l'affiche).
            #
            # Emplois :
            #   android-sdk                       état du SDK
            #   android-sdk installer             cmdline-tools + licences + paquets par défaut
            #   android-sdk installer <paquets…>  idem, avec tes propres paquets
            #   android-sdk sdkmanager <args…>    relais direct vers sdkmanager
            #   android-sdk desinstaller          supprime le SDK
            #
            # cmdline-tools rev 23.0 — URL vérifiée sur dl.google.com
            # (repository2-3.xml, 2026-09-28).

            PREFIX="$dollar{PREFIX:-$prefixeAbsolu}"
            ACCUEIL="$dollar{HOME:-$homeAbsolu}"
            SDK_HOME="${dollar}ACCUEIL/android-sdk"
            SDKMANAGER="${dollar}SDK_HOME/cmdline-tools/latest/bin/sdkmanager"
            URL_CMDLINE_TOOLS="https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
            PAQUETS_DEFAUT="platform-tools platforms;android-37.2 build-tools;37.0.0"
            # ~1 Gio : cmdline-tools + plateforme + build-tools installés.
            ESPACE_REQUIS_KO=1048576

            # Résout la JVM (sdkmanager est un programme Java) : JAVA_HOME de
            # la session d'abord, PATH ensuite, candidats du préfixe enfin.
            # Exporte JAVA_HOME : les scripts de Google le lisent directement.
            resoudre_java() {
              if [ -n "${dollar}JAVA_HOME" ] && [ -x "${dollar}JAVA_HOME/bin/java" ]; then
                JAVA_BIN="${dollar}JAVA_HOME/bin/java"
                export JAVA_HOME
                return 0
              fi
              if command -v java >/dev/null 2>&1; then
                JAVA_BIN="$dollar(command -v java)"
                JAVA_HOME="$dollar(dirname "$dollar(dirname "${dollar}JAVA_BIN")")"
                export JAVA_HOME
                return 0
              fi
              for candidat in "${dollar}PREFIX"/lib/jvm/*/bin/java "${dollar}PREFIX"/opt/jdk*/bin/java "${dollar}PREFIX"/opt/openjdk*/bin/java; do
                if [ -x "${dollar}candidat" ]; then
                  JAVA_BIN="${dollar}candidat"
                  JAVA_HOME="$dollar(dirname "$dollar(dirname "${dollar}candidat")")"
                  export JAVA_HOME
                  return 0
                fi
              done
              return 1
            }

            sdk_installe() { [ -x "${dollar}SDKMANAGER" ]; }

            cmd_statut() {
              printf '\033[36m╭──────────────────────────────────────────╮\033[0m\n'
              printf '\033[36m│\033[0m  \033[1mSDK Android\033[0m — état                     \033[36m│\033[0m\n'
              printf '\033[36m╰──────────────────────────────────────────╯\033[0m\n'
              printf '  \033[32mEmplacement\033[0m : %s\n' "${dollar}SDK_HOME"
              if sdk_installe; then
                printf '  \033[32mcmdline-tools\033[0m : installé\n'
                liste=$(ls "${dollar}SDK_HOME/platforms" 2>/dev/null | tr '\n' ' ')
                printf '  \033[32mPlateformes\033[0m  : %s\n' "$dollar{liste:-aucune}"
                liste=$(ls "${dollar}SDK_HOME/build-tools" 2>/dev/null | tr '\n' ' ')
                printf '  \033[32mbuild-tools\033[0m  : %s\n' "$dollar{liste:-aucune}"
                if [ -d "${dollar}SDK_HOME/platform-tools" ]; then
                  printf '  \033[32mplatform-tools\033[0m : installé\n'
                fi
              else
                printf '  \033[33mcmdline-tools\033[0m : absent — installe avec : android-sdk installer\n'
              fi
              if resoudre_java; then
                printf '  \033[32mJava\033[0m         : %s\n' "${dollar}JAVA_BIN"
              else
                printf '  \033[31mJava\033[0m         : ABSENT — installe d'\''abord les outils du terminal\n'
                printf '                  (écran Installation de l'\''application)\n'
              fi
              return 0
            }

            cmd_aide() {
              echo "android-sdk — installation et pilotage du SDK Android de CodeIDE"
              echo ""
              echo "  android-sdk                état du SDK (emplacements, plateformes, Java)"
              echo "  android-sdk installer      cmdline-tools + licences + paquets par défaut"
              echo "  android-sdk installer platforms;android-36 …   paquets imposés"
              echo "  android-sdk sdkmanager --list              relais vers sdkmanager"
              echo "  android-sdk desinstaller   supprime ${dollar}SDK_HOME"
            }

            cmd_installer() {
              paquets="$dollar*"
              [ -n "${dollar}paquets" ] || paquets="${dollar}PAQUETS_DEFAUT"
              if ! resoudre_java; then
                echo "android-sdk: Java est requis (sdkmanager est un programme Java)." >&2
                echo "  Installe d'abord les outils du terminal : écran Installation de l'app," >&2
                echo "  puis rouvre une session et relance : android-sdk installer" >&2
                exit 1
              fi

              libres=$(df -k "${dollar}ACCUEIL" 2>/dev/null | awk 'NR==2 {print ${dollar}4}')
              case "${dollar}libres" in
                ''|*[!0-9]*) ;;
                *) if [ "${dollar}libres" -lt "${dollar}ESPACE_REQUIS_KO" ]; then
                     echo "android-sdk: espace insuffisant (${dollar}libres Kio libres, ~${dollar}ESPACE_REQUIS_KO Kio requis)." >&2
                     exit 1
                   fi ;;
              esac

              if ! sdk_installe; then
                echo "→ Téléchargement des cmdline-tools (rev 23.0, ~130 Mio)…"
                mkdir -p "${dollar}SDK_HOME"
                archive="${dollar}SDK_HOME/cmdline-tools.zip"
                if command -v curl >/dev/null 2>&1; then
                  curl -fL --retry 3 -o "${dollar}archive" "${dollar}URL_CMDLINE_TOOLS" || {
                    echo "android-sdk: téléchargement impossible (réseau ?)" >&2; exit 1; }
                elif command -v wget >/dev/null 2>&1; then
                  wget -O "${dollar}archive" "${dollar}URL_CMDLINE_TOOLS" || {
                    echo "android-sdk: téléchargement impossible (réseau ?)" >&2; exit 1; }
                else
                  echo "android-sdk: ni curl ni wget dans le bootstrap." >&2; exit 1
                fi

                echo "→ Extraction…"
                provisoire="${dollar}SDK_HOME/.staging-cmdline-tools"
                rm -rf "${dollar}provisoire"; mkdir -p "${dollar}provisoire"
                (
                  cd "${dollar}provisoire" &&
                  if command -v unzip >/dev/null 2>&1; then
                    unzip -q "${dollar}archive"
                  else
                    "${dollar}JAVA_HOME/bin/jar" -xf "${dollar}archive"
                  fi
                ) || { rm -rf "${dollar}provisoire" "${dollar}archive"; \
                       echo "android-sdk: extraction impossible (archive corrompue ?)" >&2; exit 1; }
                rm -f "${dollar}archive"
                # v0.47.0 (retour d'appareil réel) : le répertoire PARENT
                # doit exister AVANT le déplacement — sur une première
                # installation il n'a jamais été créé et le mv échouait
                # d'un sec « No such file or directory » APRÈS le
                # téléchargement de 172,6 Mio.
                mkdir -p "${dollar}SDK_HOME/cmdline-tools"
                rm -rf "${dollar}SDK_HOME/cmdline-tools/latest"
                mv "${dollar}provisoire/cmdline-tools" "${dollar}SDK_HOME/cmdline-tools/latest" || {
                  rm -rf "${dollar}provisoire"
                  echo "android-sdk: disposition cmdline-tools/latest impossible." >&2; exit 1
                }
                rm -rf "${dollar}provisoire"
                chmod +x "${dollar}SDKMANAGER" 2>/dev/null
              fi

              echo "→ Acceptation des licences…"
              yes 2>/dev/null | "${dollar}SDKMANAGER" --licenses >/dev/null 2>&1 || \
                echo "  (licences : avertissement — l'installation dira si l'une manque)" >&2

              echo "→ Installation des paquets : ${dollar}paquets"
              # shellcheck disable=SC2086
              "${dollar}SDKMANAGER" ${dollar}paquets
              code="$dollar?"
              if [ "${dollar}code" -ne 0 ]; then
                echo "" >&2
                echo "android-sdk: l'installation a échoué (code ${dollar}code)." >&2
                echo "  Paquets disponibles : android-sdk sdkmanager --list" >&2
                exit "${dollar}code"
              fi
              echo ""
              echo "SDK Android installé sous ${dollar}SDK_HOME."
              echo "Ouvre une NOUVELLE session du terminal : CodeIDE y injectera ANDROID_HOME."
            }

            cmd_sdkmanager() {
              if ! sdk_installe; then
                echo "android-sdk: sdkmanager n'est pas installé." >&2
                echo "  Lance d'abord : android-sdk installer" >&2
                exit 127
              fi
              exec "${dollar}SDKMANAGER" "$dollar@"
            }

            cmd_desinstaller() {
              if [ ! -d "${dollar}SDK_HOME" ]; then
                echo "android-sdk: aucun SDK sous ${dollar}SDK_HOME."
                exit 0
              fi
              rm -rf "${dollar}SDK_HOME"
              echo "SDK Android supprimé (${dollar}SDK_HOME)."
            }

            case "$dollar{1:-statut}" in
              statut|etat|état) cmd_statut ;;
              installer|install) shift; cmd_installer "$dollar@" ;;
              sdkmanager) shift; cmd_sdkmanager "$dollar@" ;;
              desinstaller|désinstaller|uninstall) cmd_desinstaller ;;
              aide|help|-h|--help) cmd_aide ;;
              *)
                if sdk_installe; then
                  cmd_sdkmanager "$dollar@"
                else
                  cmd_aide
                  exit 127
                fi ;;
            esac
            """.trimIndent() + "\n"
    }

    private companion object {
        private const val TAG = "SdkAndroidCli"

        /** 0o755 — exécutable par tous (précédent `Aapt2Deployeur`). */
        private const val MODE_EXECUTABLE_PARTAGE = 493
    }
}
