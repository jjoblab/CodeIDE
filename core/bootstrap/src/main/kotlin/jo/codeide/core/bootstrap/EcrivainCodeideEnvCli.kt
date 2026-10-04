package jo.codeide.core.bootstrap

import jo.codeide.core.domain.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Écrivain de la commande `codeide-env` du terminal (v0.52.0 — comportement
 * demandé par l'utilisateur : « une fois que le bootstrap installé et
 * `pkg update`, la configuration de l'environnement avec l'installation de
 * java, android sdk, etc. » ; ADR 0083).
 *
 * `codeide-env` est l'**orchestrateur** de la configuration automatique de
 * l'environnement — le journal live défile dans le terminal où il
 * s'exécute (l'application « tape » la commande dans une session dédiée,
 * voir `ConfigurationEnvTermux` dans `core:terminal-runtime`) :
 *
 * **v0.53.0 — le JDK est vérifié au DÉMARRAGE** (retour d'appareil réel :
 * l'installation tombait « sdkmanager non fonctionnel » sans indice) :
 * `java_fonctionnel` exige que la JVM démarre réellement (`-version`) —
 * un JDK présent mais cassé est **réinstallé automatiquement**, et
 * l'échec final affiche la sortie `-version` de chaque candidat. La
 * complétude (`environnement_complet`) exige un java fonctionnel : un
 * environnement à JVM cassée se répare en relançant `codeide-env`.
 *
 * 1. mise à jour des listes de paquets (`pkg update`, non fatal en échec) ;
 * 2. **OpenJDK 17** via le gestionnaire de paquets (dépôt `codeide-packages`)
 *    — sauté si un `java` fonctionnel existe déjà ; **git n'est PAS
 *    installé** (retrait demandé : « pas vraiment urgent », v0.52.0) ;
 * 3. **SDK Android par DÉLÉGATION à la commande `android-sdk`**
 *    ([EcrivainSdkAndroidCli], ADR 0082) : binaires de l'architecture du
 *    dépôt `codeide-tools` (SHA-256 vérifiées), cmdline-tools rev 12.0,
 *    plateformes via `sdkmanager` — source de vérité unique, jamais de
 *    duplication du shell d'installation ;
 * 4. **pont d'environnement** : `JAVA_HOME` et `ANDROID_SDK_ROOT` posés
 *    dans `$PREFIX/etc/ide-environment.properties` (upsert — les autres
 *    lignes sont conservées, même contrat que `codeidesetup` du dépôt
 *    `codeide-tools` ; le profil shell les honnore depuis la v0.51.0) ;
 * 5. **vérifications** (`java -version`, `aapt2`, `sdkmanager`, liste des
 *    plateformes) puis **marqueur d'achèvement**
 *    (`$PREFIX/etc/codeide-env.terminee`, horodaté).
 *
 * Idempotent à tous les étages : environnement complet = sortie immédiate
 * (« rien à faire »), chaque composant déjà en place est conservé ;
 * `codeide-env refaire` réinstalle tout (supprime le SDK d'abord),
 * `codeide-env statut` expose l'état. Une interruption (Ctrl+C) ne casse
 * rien : la relance reprend où elle en était.
 *
 * Le script est VERSIONNÉ ([VersionneurScriptsTerminal]) : l'app le
 * régénère quand son contenu évolue — jamais de réinstallation du
 * bootstrap pour un changement de script. Le shebang pointe vers le `sh`
 * DU bootstrap (chemin absolu calculé à l'écriture) ; bit 0755 via
 * [OperationsSysteme.chmod], précédents `Aapt2Deployeur` et
 * `EcrivainSdkAndroidCli`. POSIX sh strict (dash) : `tr` plutôt que la
 * substitution de motif, jambe de repli explicite pour chaque outil.
 *
 * @param operations opérations système natives (chmod).
 * @param journal journal applicatif (règle 15 : identifiants seulement).
 */
internal class EcrivainCodeideEnvCli(
    private val operations: OperationsSysteme,
    private val journal: AppLogger,
) {
    /**
     * Écrit (ou réécrit — idempotent par contenu) la commande `codeide-env`.
     *
     * @param racine racine du bootstrap (`filesDir` — le préfixe et le HOME
     *        s'en dérivent, le shebang et les chemins de repli y sont résolus
     *        en absolu).
     */
    suspend fun ecrire(racine: File) {
        withContext(Dispatchers.IO) {
            val prefixe = DispositionsBootstrap.prefix(racine)
            val cible = File(prefixe, "bin/codeide-env")
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

            val temporaire = File(cible.parentFile, "codeide-env.codeide")
            try {
                temporaire.bufferedWriter().use { ecrivain -> ecrivain.write(attendu) }
                // 0o755 : lisible et exécutable par tous, inscriptible par
                // l'app seule — même pose que les binaires du bootstrap.
                operations.chmod(temporaire, MODE_EXECUTABLE_PARTAGE)
                if (cible.exists() && !cible.delete()) {
                    throw IOException("codeide-env existant non remplaçable")
                }
                if (!temporaire.renameTo(cible)) {
                    throw IOException("bascule du codeide-env impossible")
                }
            } finally {
                temporaire.delete()
            }

            journal.i(TAG) { "commande codeide-env posée (configuration automatique de l'environnement)" }
        }
    }

    /**
     * Contenu canonique du script d'orchestration de la configuration.
     *
     * Exemption ciblée (LongMethod) : littéral de script shell d'un seul
     * tenant — les fonctions s'appellent depuis le `case` final et la
     * lecture unitaire du shell prime (`cmd_configurer` orchestre les cinq
     * étapes dans l'ordre) ; le découper en fragments Kotlin casserait la
     * review d'un script qui s'exécute seul sur l'appareil.
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
            # Commande codeide-env de CodeIDE — configuration AUTOMATIQUE de
            # l'environnement du terminal (v0.52.0, ADR 0083 ; script VERSIONNÉ :
            # l'app le régénère quand son contenu évolue — ne pas éditer).
            #
            # Une fois le bootstrap installé et le dépôt de paquets mis à jour,
            # cette commande installe tout ce qu'il faut pour développer :
            #   1. la mise à jour des listes de paquets (pkg update) ;
            #   2. OpenJDK (openjdk-17, dépôt codeide-packages) — git n'est PAS
            #      installé (pas urgent, v0.52.0) ;
            #   3. le SDK Android via la commande android-sdk (binaires <archi>
            #      du dépôt codeide-tools, cmdline-tools, plateformes) ;
            #   4. le pont d'environnement (JAVA_HOME, ANDROID_SDK_ROOT dans
            #      ide-environment.properties — les autres lignes conservées) ;
            #   5. les vérifications finales et le marqueur d'achèvement.
            #
            # Le journal EST ce terminal : chaque étape s'affiche au fil de
            # l'eau, Ctrl+C interrompt, chaque relance reprend où elle en
            # était (idempotence à tous les étages).
            #
            # Emplois :
            #   codeide-env              configure l'environnement (idempotent)
            #   codeide-env statut       état de l'environnement
            #   codeide-env refaire      réinstalle tout (supprime le SDK d'abord)
            #   codeide-env aide         cette aide
            #
            # Variables de surcharge (tests, appareils atypiques) :
            #   CODEIDE_JDK              paquet OpenJDK (défaut : openjdk-17)
            #   CODEIDE_ESPACE_REQUIS_KO seuil d'espace disque (défaut : 1,5 Gio)

            PREFIX="$dollar{PREFIX:-$prefixeAbsolu}"
            ACCUEIL="$dollar{HOME:-$homeAbsolu}"
            SDK_HOME="${dollar}ACCUEIL/android-sdk"
            SDKMANAGER="${dollar}SDK_HOME/cmdline-tools/latest/bin/sdkmanager"
            COMMANDE_ANDROID_SDK="${dollar}PREFIX/bin/android-sdk"
            PROPS="${dollar}PREFIX/etc/ide-environment.properties"
            MARQUEUR="${dollar}PREFIX/etc/codeide-env.terminee"
            PAQUET_JDK="$dollar{CODEIDE_JDK:-openjdk-17}"
            # JDK (~200 Mio installé) + cmdline-tools (~130) + plateforme (~60)
            # + marge : 1,5 Gio.
            ESPACE_REQUIS_KO="$dollar{CODEIDE_ESPACE_REQUIS_KO:-1572864}"

            etape() { printf '\n\033[1;36m→ Étape %s — %s\033[0m\n' "${dollar}1" "${dollar}2"; }
            ok() { printf '  \033[32mOK\033[0m %s\n' "${dollar}1"; }
            attention() { printf '  \033[33mATTENTION\033[0m %s\n' "${dollar}1"; }
            echec() { printf '\033[31mÉCHEC\033[0m %s\n' "${dollar}1" >&2; exit 1; }

            # pkg (Termux/codeide-packages) sinon apt — jamais d'échec muet.
            gestionnaire() {
              if command -v pkg >/dev/null 2>&1; then
                echo pkg
              else
                echo apt
              fi
            }

            java_present() {
              if [ -n "$dollar{JAVA_HOME:-}" ] && [ -x "$dollar{JAVA_HOME}/bin/java" ]; then
                return 0
              fi
              if command -v java >/dev/null 2>&1; then
                return 0
              fi
              for candidat in "${dollar}PREFIX"/lib/jvm/*/bin/java "${dollar}PREFIX"/opt/openjdk*/bin/java "${dollar}PREFIX"/opt/jdk*/bin/java; do
                if [ -x "${dollar}candidat" ]; then
                  return 0
                fi
              done
              return 1
            }

            # v0.53.0 : présent ne suffit pas — la JVM doit DÉMARRER. Un JDK
            # au bit exécutable posé mais à la bibliothèque manquante
            # survivrait à toutes les vérifications puis ferait échouer
            # sdkmanager bien plus tard, sans indice (retour d'appareil
            # réel : « sdkmanager non fonctionnel » après 146 Mio
            # téléchargés). Retient le binaire dans JAVA_BIN (consommé
            # par les diagnostics d'échec).
            java_fonctionnel() {
              JAVA_BIN=""
              if [ -n "$dollar{JAVA_HOME:-}" ] && [ -x "$dollar{JAVA_HOME}/bin/java" ]; then
                JAVA_BIN="$dollar{JAVA_HOME}/bin/java"
              else
                for candidat in "$dollar{PREFIX}"/lib/jvm/*/bin/java "$dollar{PREFIX}"/opt/openjdk*/bin/java "$dollar{PREFIX}"/opt/jdk*/bin/java; do
                  if [ -x "$dollar{candidat}" ]; then
                    JAVA_BIN="$dollar{candidat}"
                  fi
                done
                if [ -z "$dollar{JAVA_BIN}" ] && command -v java >/dev/null 2>&1; then
                  JAVA_BIN="$dollar(command -v java)"
                fi
              fi
              [ -n "$dollar{JAVA_BIN}" ] || return 1
              "$dollar{JAVA_BIN}" -version >/dev/null 2>&1
            }

            # Écho le répertoire du JDK résolu : JAVA_HOME de la session
            # d'abord (le plus frais), candidats du préfixe ensuite (lib/jvm
            # du dépôt APT, puis opt/openjdk* Termux — le DERNIER valide
            # gagne), PATH enfin. Rien si absent : mieux vaut pas de
            # JAVA_HOME qu'un chemin fantôme (même règle que codeidesetup).
            resoudre_java_home() {
              if [ -n "$dollar{JAVA_HOME:-}" ] && [ -x "$dollar{JAVA_HOME}/bin/java" ]; then
                printf '%s\n' "$dollar{JAVA_HOME}"
                return 0
              fi
              java_trouve=""
              for candidat in "${dollar}PREFIX"/lib/jvm/*/bin/java "${dollar}PREFIX"/opt/openjdk*/bin/java "${dollar}PREFIX"/opt/jdk*/bin/java; do
                if [ -x "${dollar}candidat" ]; then
                  java_trouve="${dollar}candidat"
                fi
              done
              if [ -n "${dollar}java_trouve" ]; then
                dirname "$(dirname "${dollar}java_trouve")"
                return 0
              fi
              if command -v java >/dev/null 2>&1; then
                dirname "$(dirname "$(command -v java)")"
                return 0
              fi
              return 1
            }

            aapt2_present() {
              for binaire in "${dollar}SDK_HOME"/build-tools/*/aapt2; do
                if [ -x "${dollar}binaire" ]; then
                  return 0
                fi
              done
              return 1
            }

            plateforme_presente() {
              for jar in "${dollar}SDK_HOME"/platforms/*/android.jar; do
                if [ -f "${dollar}jar" ]; then
                  return 0
                fi
              done
              return 1
            }

            sdk_complet() {
              [ -x "${dollar}SDKMANAGER" ] || return 1
              aapt2_present || return 1
              plateforme_presente
            }

            environnement_complet() {
              java_fonctionnel && sdk_complet
            }

            maj_paquets() {
              etape 1 "mise à jour des listes de paquets"
              if "${dollar}GESTIONNAIRE" update; then
                ok "listes de paquets à jour"
              else
                attention "échec de la mise à jour (réseau ?) — poursuite avec les listes présentes"
              fi
            }

            installer_java() {
              etape 2 "OpenJDK (${dollar}PAQUET_JDK)"
              if java_fonctionnel; then
                ok "déjà en place et fonctionnel — conservé"
                return 0
              fi
              if java_present; then
                attention "JDK présent mais la JVM ne démarre pas — réinstallation"
                if ! "${dollar}GESTIONNAIRE" install -y --reinstall "${dollar}PAQUET_JDK"; then
                  echec "réinstallation de ${dollar}PAQUET_JDK impossible — dépôt codeide-packages joignable ? Relance plus tard : codeide-env"
                fi
              else
                echo "  Installation du paquet (~200 Mio installé)…"
                if ! "${dollar}GESTIONNAIRE" install -y "${dollar}PAQUET_JDK"; then
                  echec "installation de ${dollar}PAQUET_JDK impossible — dépôt codeide-packages joignable ? Relance plus tard : codeide-env"
                fi
              fi
              if java_fonctionnel; then
                ok "OpenJDK installé et fonctionnel"
              else
                echec "la JVM ne démarre toujours pas — installation interrompue (sdkmanager en a besoin). Diagnostics :"
                for candidat in "$dollar{PREFIX}"/lib/jvm/*/bin/java; do
                  [ -x "$dollar{candidat}" ] || continue
                  echo "    $dollar{candidat} :" >&2
                  "$dollar{candidat}" -version 2>&1 | head -n 2 >&2
                done
              fi
            }

            installer_sdk() {
              etape 3 "SDK Android (binaires, cmdline-tools, plateformes)"
              if [ ! -x "${dollar}COMMANDE_ANDROID_SDK" ]; then
                echec "commande android-sdk absente (${dollar}COMMANDE_ANDROID_SDK) — réinstalle l'environnement de base depuis l'application"
              fi
              if sdk_complet; then
                ok "déjà complet — conservé"
                return 0
              fi
              # DÉLÉGATION à la commande android-sdk (ADR 0082) : manifeste
              # du dépôt codeide-tools (binaires <archi> avec SHA-256),
              # cmdline-tools rev 12.0, plateformes via sdkmanager. Son
              # journal s'affiche ici, au fil de l'eau.
              "${dollar}COMMANDE_ANDROID_SDK" installer || echec "l'installation du SDK a échoué (détails ci-dessus)"
              ok "SDK Android en place"
            }

            # Upsert d'une clé dans ide-environment.properties — les autres
            # lignes sont conservées (même contrat que codeidesetup).
            poser_propriete() { # cle valeur
              mkdir -p "$(dirname "${dollar}PROPS")"
              provisoire="${dollar}PROPS.codeide"
              if [ -f "${dollar}PROPS" ]; then
                grep -v "^${dollar}1=" "${dollar}PROPS" >"${dollar}provisoire" || true
              else
                : >"${dollar}provisoire"
              fi
              printf '%s=%s\n' "${dollar}1" "${dollar}2" >>"${dollar}provisoire"
              mv "${dollar}provisoire" "${dollar}PROPS"
            }

            ecrire_pont() {
              etape 4 "pont d'environnement (ide-environment.properties)"
              if java_home_resolu="$(resoudre_java_home)"; then
                poser_propriete JAVA_HOME "${dollar}java_home_resolu"
                ok "JAVA_HOME = ${dollar}java_home_resolu"
              else
                attention "aucun JDK résolu — JAVA_HOME non écrit"
              fi
              poser_propriete ANDROID_SDK_ROOT "${dollar}SDK_HOME"
              ok "ANDROID_SDK_ROOT = ${dollar}SDK_HOME"
            }

            verifier() {
              etape 5 "vérifications"
              if java_home_resolu="$(resoudre_java_home)"; then
                "${dollar}java_home_resolu/bin/java" -version 2>&1 | head -1 | sed 's/^/  /'
              else
                attention "Java introuvable après installation"
              fi
              for binaire in "${dollar}SDK_HOME"/build-tools/*/aapt2; do
                if [ -x "${dollar}binaire" ]; then
                  echo "  aapt2 : ${dollar}binaire"
                  break
                fi
              done
              if [ -x "${dollar}SDKMANAGER" ]; then
                printf '  sdkmanager : %s\n' "$("${dollar}SDKMANAGER" --version 2>/dev/null | tail -1)"
              fi
              plateformes="$(ls "${dollar}SDK_HOME/platforms" 2>/dev/null | tr '\n' ' ')"
              printf '  plateformes : %s\n' "$dollar{plateformes:-aucune}"
              mkdir -p "$(dirname "${dollar}MARQUEUR")"
              date -u '+%Y-%m-%dT%H:%M:%SZ' >"${dollar}MARQUEUR"
              echo ""
              ok "environnement CodeIDE prêt"
              echo "  Ouvre une NOUVELLE session du terminal : CodeIDE y injectera"
              echo "  l'environnement complet (JAVA_HOME, ANDROID_HOME)."
            }

            cmd_configurer() {
              printf '\n\033[1m%s\033[0m\n' "Configuration de l'environnement CodeIDE"
              if [ "${dollar}MODE_REFAIRE" != "vrai" ] && environnement_complet; then
                ok "environnement déjà configuré — rien à faire"
                echo "  « codeide-env statut » pour le détail,"
                echo "  « codeide-env refaire » pour tout réinstaller."
                exit 0
              fi

              # Pré-vol : l'espace disque AVANT tout téléchargement.
              libres="$(df -k "${dollar}ACCUEIL" 2>/dev/null | awk 'NR==2 {print ${dollar}4}')"
              case "${dollar}libres" in
                ''|*[!0-9]*) ;;
                *) if [ "${dollar}libres" -lt "${dollar}ESPACE_REQUIS_KO" ]; then
                     echec "espace insuffisant (${dollar}libres Kio libres, ~${dollar}ESPACE_REQUIS_KO Kio requis)"
                   fi ;;
              esac

              GESTIONNAIRE="$(gestionnaire)"
              maj_paquets
              installer_java
              installer_sdk
              ecrire_pont
              verifier
            }

            cmd_statut() {
              printf '\033[36m%s\033[0m\n' "Environnement CodeIDE — état"
              if java_present; then
                printf '  \033[32mJava\033[0m : %s\n' "$(resoudre_java_home)"
              else
                printf '  \033[31mJava\033[0m : %s\n' "absent — « codeide-env » l'installera"
              fi
              if sdk_complet; then
                printf '  \033[32mSDK Android\033[0m : %s\n' "complet (${dollar}SDK_HOME)"
              elif [ -d "${dollar}SDK_HOME" ]; then
                printf '  \033[33mSDK Android\033[0m : %s\n' "partiel (${dollar}SDK_HOME) — « codeide-env » le complètera"
              else
                printf '  \033[31mSDK Android\033[0m : %s\n' "absent — « codeide-env » l'installera"
              fi
              if [ -f "${dollar}MARQUEUR" ]; then
                printf '  \033[32mConfiguration\033[0m : %s\n' "achevée le $(cat "${dollar}MARQUEUR")"
              else
                printf '  \033[33mConfiguration\033[0m : %s\n' "jamais achevée (ou interrompue)"
              fi
              exit 0
            }

            cmd_refaire() {
              echo "→ Réinstallation complète de l'environnement…"
              if [ -x "${dollar}COMMANDE_ANDROID_SDK" ]; then
                "${dollar}COMMANDE_ANDROID_SDK" desinstaller || true
              else
                rm -rf "${dollar}SDK_HOME"
              fi
              rm -f "${dollar}MARQUEUR"
              MODE_REFAIRE="vrai"
              cmd_configurer
            }

            cmd_aide() {
              echo "codeide-env — configuration automatique de l'environnement CodeIDE"
              echo ""
              echo "  codeide-env              configure tout (idempotent) : mise à jour"
              echo "                           des paquets, OpenJDK, SDK Android, pont"
              echo "                           d'environnement, vérifications"
              echo "  codeide-env statut       état de l'environnement"
              echo "  codeide-env refaire      réinstalle tout (supprime le SDK d'abord)"
              echo "  codeide-env aide         cette aide"
            }

            MODE_REFAIRE="faux"
            case "$dollar{1:-configurer}" in
              configurer|installer) cmd_configurer ;;
              statut|etat|état) cmd_statut ;;
              refaire|reinstaller|réinstaller) cmd_refaire ;;
              aide|help|-h|--help) cmd_aide ;;
              *)
                cmd_aide
                exit 127
                ;;
            esac
            """.trimIndent() + "\n"
    }

    private companion object {
        private const val TAG = "CodeideEnvCli"

        /** 0o755 — exécutable par tous (précédent `Aapt2Deployeur`). */
        private const val MODE_EXECUTABLE_PARTAGE = 493
    }
}
