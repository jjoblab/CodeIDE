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
 * **v0.48.0 — le sdkmanager redevient exécutable sur aarch64** (retour
 * d'appareil réel : « /…/cmdline-tools/latest/bin/android: not
 * executable: 64-bit ELF file » puis « android-sdk: l'installation a
 * échoué (code 1) ») : les cmdline-tools RÉCENTS (rev 19+, ex.
 * 16111833) font de `sdkmanager` un simple relais vers un NOUVEAU
 * binaire natif `android` — et Google ne publie ce binaire Linux qu'en
 * **x86_64** : sur un appareil **aarch64** le noyau refuse l'exécution
 * (ENOEXEC, le shell mksh rend « not executable: 64-bit ELF file »).
 * La rev **12.0** (11076708) est ÉPINGLÉE : son `sdkmanager` est un
 * script shell 100 % Java — aucune dépendance d'architecture, VÉRIFIÉ de
 * bout en bout. La commande **GUÉRIT** les installations cassées : un
 * cmdline-tools portant le binaire natif `bin/android` (ou dont
 * `sdkmanager --version` échoue) est REMPLACÉ — l'appareil qui a déjà
 * tenté la rev 23.0 n'est pas condamné à son échec.
 *
 * **v0.51.0 — fin du contournement x86_64 : les build-tools et
 * platform-tools viennent du dépôt `jjoblab/codeide-tools`** (ADR 0082).
 * Le `sdkmanager` de Google installe des binaires Linux **x86_64** —
 * `aapt`, `aapt2`, `aidl`, `zipalign`, `dexdump`, `adb`… INEXÉCUTABLES
 * sur un téléphone aarch64 ; seule `aapt2` était contournée (asset de
 * l'app, [Aapt2Deployeur]). Le nouveau dépôt `codeide-tools`
 * (l'équivalent CodeIDE d'`androidide-tools`, volontairement séparé de
 * `codeide-packages`) publie des binaires **recompilés pour Android**
 * par architecture (aarch64, arm, x86_64 — source
 * `lzhiyong/android-sdk-tools`), avec manifeste d'URLs et **sommes
 * SHA-256**. `android-sdk installer` :
 *
 * 1. lit le manifeste (`raw.githubusercontent.com/…/codeide-tools/
 *    main/manifest.json` — surcharges `CODEIDE_TOOLS_REPO` /
 *    `CODEIDE_TOOLS_MANIFEST`) et résout la version la plus récente
 *    publiée pour l'architecture de l'appareil (`uname -m`, surcharge
 *    `CODEIDE_ARCH` pour les tests) ;
 * 2. télécharge `build-tools-X.Y.Z-<arch>.tar.xz` et
 *    `platform-tools-X.Y.Z-<arch>.tar.xz` (~8 Mio au total — le parcours
 *    équivalent par le sdkmanager en pesait ~172), **vérifie chaque
 *    SHA-256** et les extrait sous `$HOME/android-sdk` — idempotent (un
 *    `aapt2` déjà en place saute le téléchargement) ;
 * 3. pose les cmdline-tools : reconditionnés du dépôt si le manifeste
 *    les publie (miroir GitHub + SHA-256), sinon la rev 12.0 de Google
 *    épinglée — la vérification fonctionnelle fait foi dans les deux
 *    cas, et la guérison v0.48.0 est conservée ;
 * 4. installe les **plateformes** via `sdkmanager` (`android.jar` est
 *    pur Java : aucune dépendance d'architecture — le dépôt Google
 *    reste LA source des plateformes), `platforms;android-37.2` par
 *    défaut.
 *
 * Le dépôt APT `codeide-packages` ne fournit AUCUN paquet `android-sdk`
 * (ADR 0032) ; le lecteur du manifeste est `jq` (présent dans le dépôt
 * APT, installé par la commande s'il manque — jamais d'analyse JSON à
 * la main en shell). La disposition reste le HOME du shell
 * (`home/android-sdk`) — [LocalisationOutils.trouverAndroidHome] y
 * retrouve le SDK et `EnvironnementProcessus` injecte `ANDROID_HOME`
 * aux sessions suivantes.
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

            journal.i(TAG) { "commande android-sdk posée (codeide-tools + cmdline-tools + sdkmanager)" }
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
     * appelée par `cmd_installer`, gardes `sdk_installe` / `sdk_fonctionnel`
     * du `case` final) ; le découper en fragments Kotlin casserait la
     * lecture unitaire du shell.
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
            # Android (v0.51.0 : binaires Android depuis le dépôt codeide-tools ;
            # script VERSIONNÉ : l'app le régénère quand son contenu évolue —
            # ne pas éditer).
            #
            # Le SDK vit sous le HOME du shell : android-sdk/ — c'est là que
            # l'application le localise (ANDROID_HOME injecté aux sessions
            # suivantes, l'écran de configuration du tooling l'affiche).
            #
            # Emplois :
            #   android-sdk                       état du SDK
            #   android-sdk installer             binaires <archi> + cmdline-tools + plateformes
            #   android-sdk installer <paquets…>  idem, plateformes imposées via sdkmanager
            #   android-sdk sdkmanager <args…>    relais direct vers sdkmanager
            #   android-sdk desinstaller          supprime le SDK
            #
            # cmdline-tools rev 12.0 — URL vérifiée sur dl.google.com
            # (repository2-3.xml) ; rev ÉPINGLÉE : voir URL_CMDLINE_TOOLS.
            #
            # Variables de surcharge (tests, appareils atypiques) :
            #   CODEIDE_TOOLS_REPO      dépôt des binaires (défaut : jjoblab/codeide-tools)
            #   CODEIDE_TOOLS_MANIFEST  URL du manifeste (défaut : raw.githubusercontent…)
            #   CODEIDE_ARCH            architecture imposée (défaut : uname -m)

            PREFIX="$dollar{PREFIX:-$prefixeAbsolu}"
            ACCUEIL="$dollar{HOME:-$homeAbsolu}"
            SDK_HOME="${dollar}ACCUEIL/android-sdk"
            SDKMANAGER="${dollar}SDK_HOME/cmdline-tools/latest/bin/sdkmanager"
            PROVISOIRE="${dollar}SDK_HOME/.staging-android-sdk"
            # v0.48.0 : rev 12.0 ÉPINGLÉE — les cmdline-tools récents (rev 19+)
            # délèguent `sdkmanager` à un binaire natif `android` que Google
            # ne publie Linux qu'en x86_64 : INEXÉCUTABLE sur aarch64
            # (ENOEXEC). La rev 12.0 est un script 100 % Java, éprouvé avec
            # les paquets par défaut ci-dessous. Ne PAS passer au « latest »
            # sans avoir vérifié `bin/android` sur l'archi cible.
            URL_CMDLINE_TOOLS="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
            # v0.51.0 : manifeste du dépôt codeide-tools — URLs et sommes
            # SHA-256 des archives de binaires Android, par architecture.
            DEPOT_OUTILS="$dollar{CODEIDE_TOOLS_REPO:-jjoblab/codeide-tools}"
            URL_MANIFEST="$dollar{CODEIDE_TOOLS_MANIFEST:-https://raw.githubusercontent.com/$dollar{DEPOT_OUTILS}/main/manifest.json}"
            # Plateformes par défaut via sdkmanager : android.jar est pur
            # Java (aucune dépendance d'architecture) — le dépôt Google
            # reste LA source des plateformes ; build-tools et
            # platform-tools, EUX, viennent du manifeste (binaires Android).
            PAQUETS_DEFAUT="platforms;android-37.2"
            # ~1 Gio : cmdline-tools + plateforme + binaires installés.
            ESPACE_REQUIS_KO="$dollar{CODEIDE_ESPACE_REQUIS_KO:-1048576}"

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

            normaliser_archi() {
              case "$dollar{1:-}" in
                aarch64|arm64) echo aarch64 ;;
                arm|armv7*|armv8l) echo arm ;;
                x86_64|amd64) echo x86_64 ;;
                *) return 1 ;;
              esac
            }

            sdk_installe() { [ -x "${dollar}SDKMANAGER" ]; }

            # v0.48.0 : le sdkmanager installé est-il FONCTIONNEL sur CETTE
            # architecture ? Un binaire `android` aux côtés du script = rev
            # 19+ (son relais natif, x86_64 sous Linux → ENOEXEC sur aarch64)
            # ; un `sdkmanager --version` qui échoue = installation cassée.
            # Exige que `resoudre_java` ait déjà exporté JAVA_HOME (le script
            # de Google le lit directement).
            sdk_fonctionnel() {
              sdk_installe || return 1
              [ ! -x "${dollar}SDK_HOME/cmdline-tools/latest/bin/android" ] || return 1
              "${dollar}SDKMANAGER" --version >/dev/null 2>&1
            }

            # Téléchargement réseau : curl (présent dans le bootstrap), en
            # repli wget — jamais de dépendance silencieuse.
            telecharger() { # url destination
              if command -v curl >/dev/null 2>&1; then
                curl -fL --retry 3 -o "$dollar{2}" "$dollar{1}" || return 1
              elif command -v wget >/dev/null 2>&1; then
                wget -O "$dollar{2}" "$dollar{1}" || return 1
              else
                echo "android-sdk: ni curl ni wget dans le bootstrap." >&2
                return 1
              fi
            }

            # v0.51.0 : jq lit le manifeste — absent du bootstrap de base,
            # présent dans le dépôt APT codeide-packages. Message
            # actionnable plutôt qu'un échec muet d'analyse.
            assurer_jq() {
              command -v jq >/dev/null 2>&1 && return 0
              echo "→ Installation de jq (lecture du manifeste)…"
              apt update >/dev/null 2>&1 || true
              apt install -y jq >/dev/null 2>&1 && return 0
              pkg install -y jq >/dev/null 2>&1 && return 0
              echo "android-sdk: jq est requis pour lire le manifeste." >&2
              echo "  Installe-le : pkg install jq" >&2
              return 1
            }

            charger_manifest() {
              MANIFEST="${dollar}PROVISOIRE/manifest.json"
              telecharger "$dollar{URL_MANIFEST}" "$dollar{MANIFEST}" || {
                echo "android-sdk: manifeste inaccessible ($dollar{URL_MANIFEST})." >&2
                echo "  Vérifie le réseau, ou publie une release depuis le dépôt" >&2
                echo "  https://github.com/$dollar{DEPOT_OUTILS}/actions" >&2
                return 1
              }
            }

            # Versions X.Y.Z publiées pour l'architecture, tri croissant.
            versions_disponibles() {
              jq -r --arg a "$dollar{ARCH}" \
                '(.build_tools[${dollar}a] // {}) | keys[] | ltrimstr("_") | gsub("_"; ".")' \
                "$dollar{MANIFEST}" 2>/dev/null | sort -V
            }

            url_composant() { # section version -> URL ou vide
              # tr plutôt que la substitution de motif : celle-ci est un
              # bashisme — le sh du bootstrap est un dash POSIX (Bad
              # substitution sinon).
              cle="_$dollar(printf '%s' "$dollar{2}" | tr '.' '_')"
              jq -r --arg a "$dollar{ARCH}" --arg s "$dollar{1}" --arg k "$dollar{cle}" \
                '.[${dollar}s][${dollar}a][${dollar}k] // empty' "$dollar{MANIFEST}" 2>/dev/null
            }

            somme_archive() { # nom de fichier -> SHA-256 attendue ou vide
              jq -r --arg f "$dollar{1}" '.sha256[${dollar}f] // empty' "$dollar{MANIFEST}" 2>/dev/null
            }

            manifest_cmdline() { # URL des cmdline-tools reconditionnés ou vide
              [ -f "$dollar{MANIFEST}" ] || return 0
              jq -r '.cmdline_tools // empty' "$dollar{MANIFEST}" 2>/dev/null
            }

            # Télécharge une archive du manifeste, VÉRIFIE sa somme SHA-256,
            # l'extrait à la racine du SDK puis la supprime.
            installer_archive() { # libellé url
              arch_libelle="${dollar}1"
              arch_url="${dollar}2"
              arch_nom="$dollar{arch_url##*/}"
              arch_fichier="${dollar}PROVISOIRE/$dollar{arch_nom}"
              echo "→ Téléchargement : $dollar{arch_libelle}"
              telecharger "$dollar{arch_url}" "$dollar{arch_fichier}" || {
                echo "android-sdk: échec du téléchargement ($dollar{arch_url})" >&2
                return 1
              }
              arch_somme="$dollar(somme_archive "$dollar{arch_nom}")"
              if [ -n "$dollar{arch_somme}" ]; then
                arch_calculee="$(sha256sum "$dollar{arch_fichier}" | cut -d' ' -f1)"
                if [ "$dollar{arch_calculee}" != "$dollar{arch_somme}" ]; then
                  echo "android-sdk: SHA-256 invalide pour $dollar{arch_nom}" >&2
                  echo "  (attendu $dollar{arch_somme}, obtenu $dollar{arch_calculee})" >&2
                  rm -f "$dollar{arch_fichier}"
                  return 1
                fi
                echo "  SHA-256 vérifié ($dollar{arch_nom})"
              else
                echo "  (pas de SHA-256 pour $dollar{arch_nom} dans le manifeste)" >&2
              fi
              tar -xJf "$dollar{arch_fichier}" -C "$dollar{SDK_HOME}" || {
                echo "android-sdk: extraction impossible ($dollar{arch_nom})." >&2
                rm -f "$dollar{arch_fichier}"
                return 1
              }
              rm -f "$dollar{arch_fichier}"
              return 0
            }

            # v0.51.0 — LE CŒUR DU CORRECTIF : build-tools et platform-tools
            # ANDROID (pas les x86_64 du dépôt Google) depuis le manifeste
            # de codeide-tools, pour l'architecture de l'appareil.
            installer_binaires_android() {
              assurer_jq || return 1
              mkdir -p "$dollar{PROVISOIRE}"
              charger_manifest || return 1

              VERSION_SDK="$dollar(versions_disponibles | tail -n1)"
              if [ -z "$dollar{VERSION_SDK}" ]; then
                echo "android-sdk: aucun build-tools publié pour '$dollar{ARCH}' dans le manifeste." >&2
                echo "  Lance le workflow « Publier build-tools et platform-tools »" >&2
                echo "  du dépôt https://github.com/$dollar{DEPOT_OUTILS}/actions (ex. 35.0.2)." >&2
                return 1
              fi

              if [ -x "${dollar}SDK_HOME/build-tools/${dollar}VERSION_SDK/aapt2" ]; then
                echo "→ Build-tools $dollar{VERSION_SDK} déjà en place — conservés."
              else
                installer_archive "build-tools $dollar{VERSION_SDK} ($dollar{ARCH})" \
                  "$dollar(url_composant build_tools "$dollar{VERSION_SDK}")" || return 1
              fi
              if [ -x "${dollar}SDK_HOME/platform-tools/adb" ]; then
                echo "→ Platform-tools déjà en place — conservés."
              else
                installer_archive "platform-tools $dollar{VERSION_SDK} ($dollar{ARCH})" \
                  "$dollar(url_composant platform_tools "$dollar{VERSION_SDK}")" || return 1
              fi
              echo "→ Binaires Android $dollar{VERSION_SDK} ($dollar{ARCH}) en place (codeide-tools)."
              return 0
            }

            # v0.51.0 : cmdline-tools — reconditionnés du dépôt si le
            # manifeste les publie (miroir GitHub + SHA-256), sinon rev
            # 12.0 de Google épinglée. La vérification fonctionnelle fait
            # foi dans les deux cas ; la GUÉRISON v0.48.0 est conservée.
            installer_cmdline_tools() {
              if sdk_installe && ! sdk_fonctionnel; then
                echo "→ cmdline-tools installé mais NON FONCTIONNEL sur cette architecture"
                echo "  (binaire natif x86_64 ou sdkmanager cassé) — remplacement par la rev 12.0…"
                rm -rf "${dollar}SDK_HOME/cmdline-tools"
              fi

              if ! sdk_installe; then
                url_cmdline="$dollar(manifest_cmdline)"
                if [ -n "$dollar{url_cmdline}" ]; then
                  echo "→ cmdline-tools reconditionnés trouvés dans le manifeste…"
                  if installer_archive "cmdline-tools (codeide-tools)" "$dollar{url_cmdline}"; then
                    chmod +x "$dollar{SDKMANAGER}" 2>/dev/null || true
                  fi
                fi
              fi

              if ! sdk_fonctionnel; then
                rm -rf "${dollar}SDK_HOME/cmdline-tools"
                echo "→ Téléchargement des cmdline-tools (rev 12.0, ~130 Mio)…"
                mkdir -p "${dollar}SDK_HOME" "$dollar{PROVISOIRE}"
                archive="${dollar}PROVISOIRE/cmdline-tools.zip"
                telecharger "$dollar{URL_CMDLINE_TOOLS}" "$dollar{archive}" || {
                  echo "android-sdk: téléchargement impossible (réseau ?)" >&2
                  return 1
                }

                echo "→ Extraction…"
                provisoire="${dollar}PROVISOIRE/extraction-cmdline"
                rm -rf "$dollar{provisoire}"; mkdir -p "$dollar{provisoire}"
                (
                  cd "$dollar{provisoire}" &&
                  if command -v unzip >/dev/null 2>&1; then
                    unzip -q "$dollar{archive}"
                  else
                    "${dollar}JAVA_HOME/bin/jar" -xf "$dollar{archive}"
                  fi
                ) || { rm -rf "$dollar{provisoire}" "${dollar}archive"; \
                       echo "android-sdk: extraction impossible (archive corrompue ?)" >&2; return 1; }
                rm -f "$dollar{archive}"
                # v0.47.0 (retour d'appareil réel) : le répertoire PARENT
                # doit exister AVANT le déplacement — sur une première
                # installation il n'a jamais été créé et le mv échouait
                # d'un sec « No such file or directory » APRÈS le
                # téléchargement de 172,6 Mio.
                mkdir -p "${dollar}SDK_HOME/cmdline-tools"
                rm -rf "${dollar}SDK_HOME/cmdline-tools/latest"
                mv "$dollar{provisoire}/cmdline-tools" "${dollar}SDK_HOME/cmdline-tools/latest" || {
                  rm -rf "$dollar{provisoire}"
                  echo "android-sdk: disposition cmdline-tools/latest impossible." >&2
                  return 1
                }
                rm -rf "$dollar{provisoire}"
                chmod +x "$dollar{SDKMANAGER}" 2>/dev/null
                # v0.48.0 : vérification FONCTIONNELLE après installation —
                # le téléchargement ne suffit pas, le sdkmanager doit
                # DÉMARRER (JVM) avant d'engager les paquets : un échec
                # s'explique ici AVANT les 172 Mio de paquets.
                if ! sdk_fonctionnel; then
                  echo "" >&2
                  echo "android-sdk: sdkmanager non fonctionnel après installation." >&2
                  echo "  (Java : $dollar{JAVA_BIN} ; détail : $dollar{SDKMANAGER} --version)" >&2
                  return 1
                fi
              fi
              return 0
            }

            cmd_statut() {
              printf '\033[36m╭──────────────────────────────────────────╮\033[0m\n'
              printf '\033[36m│\033[0m  \033[1mSDK Android\033[0m — état                     \033[36m│\033[0m\n'
              printf '\033[36m╰──────────────────────────────────────────╯\033[0m\n'
              printf '  \033[32mEmplacement\033[0m : %s\n' "${dollar}SDK_HOME"
              printf '  \033[32mArchitecture\033[0m : %s\n' "$dollar{ARCH}"
              if sdk_installe; then
                printf '  \033[32mcmdline-tools\033[0m : installé\n'
                liste=$(ls "${dollar}SDK_HOME/platforms" 2>/dev/null | tr '\n' ' ')
                printf '  \033[32mPlateformes\033[0m  : %s\n' "$dollar{liste:-aucune}"
                liste=$(ls "${dollar}SDK_HOME/build-tools" 2>/dev/null | tr '\n' ' ')
                printf '  \033[32mbuild-tools\033[0m  : %s\n' "$dollar{liste:-aucun}"
                if [ -d "${dollar}SDK_HOME/platform-tools" ]; then
                  printf '  \033[32mplatform-tools\033[0m : installé\n'
                fi
                for binaire in "${dollar}SDK_HOME"/build-tools/*/aapt2; do
                  if [ -x "$dollar{binaire}" ]; then
                    printf '  \033[32maapt2\033[0m        : exécutable (%s)\n' "$dollar{ARCH}"
                    break
                  fi
                done
              else
                printf '  \033[33mcmdline-tools\033[0m : absent — installe avec : android-sdk installer\n'
              fi
              if resoudre_java; then
                printf '  \033[32mJava\033[0m         : %s\n' "$dollar{JAVA_BIN}"
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
              echo "  android-sdk installer      binaires <archi> (codeide-tools) + cmdline-tools"
              echo "                             + plateformes par défaut via sdkmanager"
              echo "  android-sdk installer platforms;android-36 …   plateformes imposées"
              echo "  android-sdk sdkmanager --list              relais vers sdkmanager"
              echo "  android-sdk desinstaller   supprime ${dollar}SDK_HOME"
            }

            cmd_installer() {
              paquets="$dollar*"
              [ -n "$dollar{paquets}" ] || paquets="$dollar{PAQUETS_DEFAUT}"
              if ! resoudre_java; then
                echo "android-sdk: Java est requis (sdkmanager est un programme Java)." >&2
                echo "  Installe d'abord les outils du terminal : écran Installation de l'app," >&2
                echo "  puis rouvre une session et relance : android-sdk installer" >&2
                exit 1
              fi

              libres=$(df -k "$dollar{ACCUEIL}" 2>/dev/null | awk 'NR==2 {print ${dollar}4}')
              case "$dollar{libres}" in
                ''|*[!0-9]*) ;;
                *) if [ "$dollar{libres}" -lt "$dollar{ESPACE_REQUIS_KO}" ]; then
                     echo "android-sdk: espace insuffisant ($dollar{libres} Kio libres, ~$dollar{ESPACE_REQUIS_KO} Kio requis)." >&2
                     exit 1
                   fi ;;
              esac

              # v0.51.0 : d'abord les binaires ANDROID du dépôt codeide-tools
              # (build-tools + platform-tools de l'architecture de l'appareil)
              # — le sdkmanager de Google n'est plus la source de ces binaires
              # x86_64 inexécutables sur téléphone.
              installer_binaires_android || exit 1

              # Puis les cmdline-tools (manifeste si publié, rev 12.0 Google
              # sinon) et les plateformes via sdkmanager (pur Java).
              installer_cmdline_tools || exit 1

              echo "→ Acceptation des licences…"
              yes 2>/dev/null | "$dollar{SDKMANAGER}" --licenses >/dev/null 2>&1 || \
                echo "  (licences : avertissement — l'installation dira si l'une manque)" >&2

              echo "→ Installation des plateformes : $dollar{paquets}"
              # shellcheck disable=SC2086
              "$dollar{SDKMANAGER}" $dollar{paquets}
              code="$dollar?"
              if [ "$dollar{code}" -ne 0 ]; then
                echo "" >&2
                echo "android-sdk: l'installation a échoué (code $dollar{code})." >&2
                echo "  Paquets disponibles : android-sdk sdkmanager --list" >&2
                exit "$dollar{code}"
              fi
              rm -rf "$dollar{PROVISOIRE}"
              echo ""
              echo "SDK Android installé sous $dollar{SDK_HOME}."
              echo "  Binaires $dollar{VERSION_SDK} ($dollar{ARCH}) depuis codeide-tools —"
              echo "  plateformes via sdkmanager (rev 12.0)."
              echo "Ouvre une NOUVELLE session du terminal : CodeIDE y injectera ANDROID_HOME."
            }

            cmd_sdkmanager() {
              if ! sdk_installe; then
                echo "android-sdk: sdkmanager n'est pas installé." >&2
                echo "  Lance d'abord : android-sdk installer" >&2
                exit 127
              fi
              exec "$dollar{SDKMANAGER}" "$dollar@"
            }

            cmd_desinstaller() {
              if [ ! -d "${dollar}SDK_HOME" ]; then
                echo "android-sdk: aucun SDK sous $dollar{SDK_HOME}."
                exit 0
              fi
              rm -rf "$dollar{SDK_HOME}"
              echo "SDK Android supprimé ($dollar{SDK_HOME})."
            }

            ARCH="$dollar(normaliser_archi "$dollar{CODEIDE_ARCH:-$dollar(uname -m)}")" || {
              echo "android-sdk: architecture non supportée ($dollar(uname -m))." >&2
              exit 1
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
