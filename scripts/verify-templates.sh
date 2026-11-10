#!/usr/bin/env bash
# CodeIDE — validation réelle des modèles embarqués (étape 9, section 11 ;
# refonte L2 ADR 0157 : la matrice est DÉRIVÉE des manifestes par l'outil).
#
# Pipeline :
#   1. `generateur matrice` dérive les combinaisons des manifestes (lecture
#      réelle des `template.json` — stratégies exhaustif/paires/bornes) ;
#   2. les cas nommés de régression (`scripts/verify-templates-cas-nomes.json`,
#      écrits à la main : noms et descriptions hostiles, renommages, langues,
#      licences, dépendances) s'ajoutent à la matrice dérivée ;
#   3. `generateur generer` déverse chaque combinaison sur disque ;
#   4. `generateur determinisme` compare deux générations octet pour octet ;
#   5. chaque projet est vérifié structurellement puis COMPILÉ, TESTÉ et
#      EXÉCUTÉ avec les vrais outils (Gradle Wrapper, Maven, javac, SDK
#      Android) — le tableau final et les rapports (Markdown + JUnit XML)
#      portent le détail combinaison par combinaison.
#
# Usage : scripts/verify-templates.sh
# Surcharge d'environnement :
#   CODEIDE_JDK17, CODEIDE_JDK21, CODEIDE_MAVEN, CODEIDE_TRAVAIL,
#   CODEIDE_ANDROID_SDK (modèles android-*, défaut $ANDROID_HOME)
#   CODEIDE_STRATEGIE (exhaustif|paires|bornes — défaut paires)
#   CODEIDE_SEUIL (seuil de la stratégie exhaustive — défaut 64)
# Filtre optionnel (expression rationnelle sur l'identifiant de combinaison) :
#   CODEIDE_COMBINAISONS='^(mx-android|sb-|andr)' — vérification ciblée ou
#   par tranches ; les combinaisons déjà vérifiées (marqueur `.verifie` +
#   empreinte des assets) sont sautées à chaque relance, la reprise est donc
#   gratuite.
# Outils absents : annoncés en tête avec la conduite à tenir — les
#   combinaisons qui les exigent échouent explicitement (code de sortie non
#   nul) ; JAMAIS de contournement (ADR 0157 §2).

set -euo pipefail

RACINE=$(cd "$(dirname "$0")/.." && pwd)
TRAVAIL="${CODEIDE_TRAVAIL:-$RACINE/build/verify-templates}"
JDK17="${CODEIDE_JDK17:-$(compgen -G '/home/z/tools/jdk-17*' | head -1 || true)}"
JDK21="${CODEIDE_JDK21:-$(compgen -G '/home/z/tools-jdk*' | head -1 || echo /home/z/tools/jdk-21)}"
MVN_BIN="${CODEIDE_MAVEN:-$(compgen -G '/home/z/tools/apache-maven-*/bin/mvn' | head -1 || command -v mvn || true)}"
# Distribution Gradle locale (projets générés SANS wrapper : build avec le
# Gradle système, comme un utilisateur qui n'embarque pas le wrapper).
GRADLE_DIS="$(compgen -G "$HOME/.gradle/wrapper/dists/gradle-9.7.1-bin/*/gradle-9.7.1/bin/gradle" | head -1 || true)"
# SDK Android pour les modèles android-app (combinaisons andr-*) : absent →
# ces combinaisons échouent avec un message explicite (le reste du script,
# JVM pur, reste exécutable).
SDK_ANDROID="${CODEIDE_ANDROID_SDK:-${ANDROID_HOME:-/home/z/android-sdk}}"
STRATEGIE="${CODEIDE_STRATEGIE:-paires}"
SEUIL="${CODEIDE_SEUIL:-64}"
FILTRE="${CODEIDE_COMBINAISONS:-}"
VERSION_CODEIDE=$(sed -n 's/^VERSION_NAME=//p' "$RACINE/version.properties" | head -1)

echec_global=0
declare -a lignes_tableau=()

note() { printf '\033[1;34m[verify-templates]\033[0m %s\n' "$*"; }
echec() { printf '\033[1;31m[verify-templates]\033[0m %s\n' "$*" >&2; }

# ---------------------------------------------------------------------------
# 0. Outils présents ? (annoncés — les combinaisons concernées échoueront
#    explicitement ; aucun contournement, ADR 0157 §2)
# ---------------------------------------------------------------------------
[ -d "$JDK21" ] || { echec "JDK 21 introuvable (CODEIDE_JDK21) : $JDK21 — requis par le harnais"; exit 1; }
note "JDK 21 : $JDK21"
if [ -d "$JDK17" ]; then
    note "JDK 17 : $JDK17"
else
    echec "JDK 17 absent (CODEIDE_JDK17) — les combinaisons jdkVersion=17 échoueront. Conduite à tenir : installer Temurin 17 et/ou exporter CODEIDE_JDK17."
    echec_global=1
fi
if [ -n "$MVN_BIN" ] && [ -x "$MVN_BIN" ]; then
    note "Maven  : $MVN_BIN"
else
    echec "Maven absent (CODEIDE_MAVEN) — les combinaisons buildSystem=maven échoueront. Conduite à tenir : installer Maven 3.9+ et/ou exporter CODEIDE_MAVEN."
    echec_global=1
fi
if [ -n "$GRADLE_DIS" ]; then
    note "Gradle (sans wrapper) : $GRADLE_DIS"
else
    note "Gradle système absent : les projets sans wrapper seront construits via un wrapper voisin"
fi
if [ -d "$SDK_ANDROID/platforms" ]; then
    note "SDK Android : $SDK_ANDROID"
else
    echec "SDK Android introuvable (CODEIDE_ANDROID_SDK) : $SDK_ANDROID — les combinaisons android-* échoueront. Conduite à tenir : installer le SDK et/ou exporter CODEIDE_ANDROID_SDK."
    echec_global=1
fi

mkdir -p "$TRAVAIL"

# ---------------------------------------------------------------------------
# 1. Matrice dérivée des manifestes (ADR 0157 §1) — plus aucun bloc de
#    combinaisons codé à la main ici : l'outil lit les template.json.
# ---------------------------------------------------------------------------
note "Matrice dérivée (stratégie $STRATEGIE, seuil $SEUIL) …"
(cd "$RACINE" && JAVA_HOME="$JDK21" ./gradlew -q -Pfreres.sources :tools:generateur:run \
    "--args=matrice --assets $RACINE/app/src/main/assets --strategie $STRATEGIE --seuil $SEUIL --sortie $TRAVAIL/combos-base.json" \
    2>"$TRAVAIL/matrice-exclusions.jsonl") || {
    echec "matrice : dérivation impossible (voir $TRAVAIL/matrice-exclusions.jsonl)"
    exit 1
}
[ -s "$TRAVAIL/matrice-exclusions.jsonl" ] && note "$(wc -l < "$TRAVAIL/matrice-exclusions.jsonl") candidat(s) exclu(s) par règle ERREUR ou validation (voir $TRAVAIL/matrice-exclusions.jsonl)"

# ---------------------------------------------------------------------------
# 2. Fusion matrice dérivée + cas nommés de régression, filtre optionnel et
#    tableau de pilotage des builds (aucune combinaison définie ici).
# ---------------------------------------------------------------------------
python3 - "$TRAVAIL" "$RACINE" "$FILTRE" <<'PY'
import json
import re
import sys

travail, racine, filtre = sys.argv[1], sys.argv[2], sys.argv[3]

base = json.load(open(f"{travail}/combos-base.json", encoding="utf-8"))
nomes = json.load(open(f"{racine}/scripts/verify-templates-cas-nomes.json", encoding="utf-8"))
entrees = base + nomes

ids = [e["id"] for e in entrees]
doublons = sorted({i for i in ids if ids.count(i) > 1})
if doublons:
    sys.exit(f"identifiants de combinaison en double : {doublons}")

if filtre:
    motif = re.compile(filtre)
    entrees = [e for e in entrees if motif.search(e["id"])]

def build_de(entree):
    modele, params = entree["templateId"], entree["parametres"]
    if modele in ("kotlin-jvm", "java"):
        return params.get("buildSystem", "gradle-kts")
    if modele == "spring-boot":
        # spring-boot 2.0.0 : buildSystem gradle|maven (L3) — le build Maven
        # d'un service Boot n'exécute pas exec:java (serveur web).
        return "maven-spring" if params.get("buildSystem", "gradle") == "maven" else "gradle-app"
    return {
        "kotlin-multiplatform": "gradle-kmp",
        "android-app": "gradle-android",
        "android-library": "gradle-android-lib",
        "gradle-plugin": "gradle-plugin",
    }.get(modele, "none")

def role_de(entree):
    modele, params = entree["templateId"], entree["parametres"]
    if modele in ("kotlin-jvm", "java"):
        return "app" if params.get("projectType", "application") == "application" else "lib"
    return "lib" if modele in ("android-library", "gradle-plugin") else "app"

lignes = []
for entree in entrees:
    params, options = entree["parametres"], entree["options"]
    build = build_de(entree)
    lignes.append("\t".join([
        entree["id"],
        entree["templateId"],
        build,
        params.get("jdkVersion", "21") if build != "none" else "-",
        "tests" if params.get("includeTests", "true") == "true" else "notests",
        "wrapper" if params.get("includeWrapper", "true") == "true" else "nowrapper",
        options.get("contentLanguage", "en"),
        options.get("license", "none"),
        role_de(entree),
    ]))

with open(f"{travail}/combos.json", "w", encoding="utf-8") as flot:
    json.dump(entrees, flot, ensure_ascii=False, indent=2)
with open(f"{travail}/matrix.tsv", "w", encoding="utf-8") as flot:
    flot.write("\n".join(lignes) + "\n")
print(f"{len(entrees)} combinaisons (matrice dérivée + cas nommés)")
PY

# ---------------------------------------------------------------------------
# 3. Génération sur disque (harnais JVM — ADR 0019) puis déterminisme.
# ---------------------------------------------------------------------------
# Empreinte des assets : un marqueur ne vaut que pour les assets qui l'ont produit.
# Calculée AVANT la purge — c'est elle qui décide quels répertoires conservent
# leur cache `.verifie`.
EMPREINTE_ASSETS=$( (find "$RACINE/app/src/main/assets/templates" -type f | sort | xargs sha256sum; \
    sha256sum "$RACINE/gradle/wrapper/gradle-wrapper.jar") 2>/dev/null | sha256sum | cut -d' ' -f1)

note "Génération des projets dans $TRAVAIL/modeles …"
# Les répertoires des combinaisons (re)générées sont purgés AVANT : un lot
# qui change le jeu de fichiers d'un cas (ex. Gradle → Maven) ne doit jamais
# hériter des fichiers de l'exécution précédente ; le cache `.verifie` des
# autres combinaisons est conservé (marqueur + empreinte des assets — la
# génération est déterministe, réécrire un répertoire déjà vérifié avec les
# MÊMES assets produit les mêmes octets et n'invalide rien).
while IFS=$'\t' read -r cid _; do
    projet_a_purger="$TRAVAIL/modeles/$cid"
    if [ -f "$projet_a_purger/.verifie" ] && \
       [ "$(cat "$projet_a_purger/.verifie" 2>/dev/null)" = "$EMPREINTE_ASSETS" ]; then
        continue
    fi
    rm -rf "$projet_a_purger"
done < "$TRAVAIL/matrix.tsv"
(cd "$RACINE" && JAVA_HOME="$JDK21" ./gradlew -q -Pfreres.sources :tools:generateur:run \
    "--args=generer --assets $RACINE/app/src/main/assets --sortie $TRAVAIL/modeles --combos $TRAVAIL/combos.json --annee 2026 --auteur CodeIDE --generateur $VERSION_CODEIDE")

note "Déterminisme (deux générations comparées octet pour octet) …"
(cd "$RACINE" && JAVA_HOME="$JDK21" ./gradlew -q -Pfreres.sources :tools:generateur:run \
    "--args=determinisme --assets $RACINE/app/src/main/assets --combos $TRAVAIL/combos.json --sortie $TRAVAIL/determinisme --annee 2026 --auteur CodeIDE --generateur $VERSION_CODEIDE") || {
    echec "déterminisme : une divergence octet pour octet a été détectée"
    exit 1
}

# ---------------------------------------------------------------------------
# 4. Vérifications structurelles + builds réels, combinaison par combinaison.
# ---------------------------------------------------------------------------
SOMME_WRAPPER=$(sha256sum "$RACINE/gradle/wrapper/gradle-wrapper.jar" | cut -d' ' -f1)
PREMIERE_LIGNE_FR="Bonjour, Ada!"
PREMIERE_LIGNE_EN="Hello, Ada!"

verifier_structure() {
    local projet="$1"
    local restants
    # Les artefacts de build (.gradle/, build/, target/) sont exclus : un
    # projet généré qui a déjà été construit les porte, et un binaire
    # Gradle peut contenir la séquence « {{ » par hasard.
    restants=$(grep -rIl --exclude=".verifie" --exclude-dir=.gradle --exclude-dir=build --exclude-dir=target '{{' "$projet" || true)
    if [ -n "$restants" ]; then
        echec "marqueur {{ résiduel : $restants"
        return 1
    fi
    local boms
    boms=$(grep -rIl --exclude=".verifie" --exclude-dir=.gradle --exclude-dir=build --exclude-dir=target $'\xef\xbb\xbf' "$projet" || true)
    if [ -n "$boms" ]; then
        echec "BOM UTF-8 interdit : $boms"
        return 1
    fi
    if [ -f "$projet/gradlew" ] && grep -q $'\r' "$projet/gradlew"; then
        echec "gradlew doit être en LF"
        return 1
    fi
    if [ -f "$projet/gradlew.bat" ]; then
        if ! grep -q $'\r' "$projet/gradlew.bat"; then
            echec "gradlew.bat doit être en CRLF"
            return 1
        fi
        local somme
        somme=$(sha256sum "$projet/gradle/wrapper/gradle-wrapper.jar" | cut -d' ' -f1)
        if [ "$somme" != "$SOMME_WRAPPER" ]; then
            echec "gradle-wrapper.jar altéré (checksum)"
            return 1
        fi
    fi
    return 0
}

lancer_sortie_attendue() { # $1 projet $2 première ligne attendue
    local projet="$1" attendu="$2" sortie
    sortie=$(cd "$projet" && JAVA_HOME="$JDK21" ./gradlew -q run 2>/dev/null | head -1 || true)
    if [ "$sortie" != "$attendu" ]; then
        echec "sortie inattendue : « $sortie » (attendu « $attendu »)"
        return 1
    fi
    return 0
}

# Commande Gradle d'un projet généré : son wrapper s'il embarque, sinon la
# distribution système (utilisateur sans wrapper) — vide si indisponible.
gradle_cmd_pour() { # $1 projet
    if [ -f "$1/gradlew" ]; then
        chmod +x "$1/gradlew"
        printf '%s\n' "$1/gradlew"
    elif [ -n "$GRADLE_DIS" ]; then
        printf '%s\n' "$GRADLE_DIS"
    fi
}

combinaison_ko() {
    lignes_tableau+=("$(printf '%-24s | %s' "$1" "KO — $2")")
    echec_global=1
}
combinaison_ok() {
    lignes_tableau+=("$(printf '%-24s | %s' "$1" "OK")")
}

note "Builds réels ($(wc -l < "$TRAVAIL/matrix.tsv") combinaisons) — ceci prend plusieurs minutes…"
mkdir -p "$TRAVAIL/junit"
rm -f "$TRAVAIL/junit/cases.xml.part"
while IFS=$'\t' read -r cid template build jdk tests wrapper langue licence role; do
    projet="$TRAVAIL/modeles/$cid"
    erreur=""
    debut=$(date +%s%3N)
    marqueur="$projet/.verifie"
    if [ -f "$marqueur" ] && [ "$(cat "$marqueur")" = "$EMPREINTE_ASSETS" ]; then
        combinaison_ok "$cid"
        note "OK   $cid (déjà vérifié)"
        continue
    fi

    jdk_home="$JDK21"
    if [ "$jdk" = "17" ]; then
        if [ -d "$JDK17" ]; then jdk_home="$JDK17"; else erreur="JDK 17 absent (CODEIDE_JDK17) — installer Temurin 17"; fi
    fi
    if [ -z "$erreur" ] && [ "$build" = "maven" ] && { [ -z "$MVN_BIN" ] || [ ! -x "$MVN_BIN" ]; }; then
        erreur="Maven absent (CODEIDE_MAVEN) — installer Maven 3.9+"
    fi

    if [ -z "$erreur" ]; then
        if ! verifier_structure "$projet"; then
            erreur="structure"
        fi
    fi

    # Option commune includeGitattributes (L3, plan A-3 #11) : le cas nommé
    # *sans-attributs* ne doit produire aucun .gitattributes.
    if [ -z "$erreur" ] && [ "$cid" = "kt-app-sans-attributs" ] && [ -f "$projet/.gitattributes" ]; then
        erreur=".gitattributes présent alors que l'option est désactivée"
    fi

    if [ -z "$erreur" ]; then
        case "$build" in
        gradle-kts)
            gradle_cmd=()
            if [ -f "$projet/gradlew" ]; then
                chmod +x "$projet/gradlew"
                gradle_cmd=("$projet/gradlew")
            elif [ -n "$GRADLE_DIS" ]; then
                gradle_cmd=("$GRADLE_DIS")
            else
                erreur="ni wrapper ni Gradle système"
            fi
            if [ -z "$erreur" ]; then
                taches="build"
                [ "$role" = "lib" ] && taches="build publishToMavenLocal"
                if ! (cd "$projet" && JAVA_HOME="$jdk_home" "${gradle_cmd[0]}" --no-daemon --console=plain -q $taches >/dev/null 2>"$TRAVAIL/$cid.log"); then
                    erreur="gradle ($taches)"
                elif [ "$tests" = "tests" ] && [ ! -f "$projet/build/reports/tests/test/index.html" ]; then
                    erreur="rapport de tests Gradle absent"
                elif [ "$role" = "app" ]; then
                    if ! (cd "$projet" && JAVA_HOME="$jdk_home" "${gradle_cmd[0]}" --no-daemon -q run >"$TRAVAIL/$cid.sortie" 2>>"$TRAVAIL/$cid.log"); then
                        erreur="gradle run"
                    elif [ "$langue" = "fr" ] && ! grep -q "$PREMIERE_LIGNE_FR" "$TRAVAIL/$cid.sortie"; then
                        erreur="sortie application inattendue (fr)"
                    elif [ "$langue" = "en" ] && ! grep -q "$PREMIERE_LIGNE_EN" "$TRAVAIL/$cid.sortie"; then
                        erreur="sortie application inattendue (en)"
                    fi
                fi
                # Renommage de l'aperçu (ADR 0077) : l'aperçu ne ment pas, le
                # disque suit — assertion des cas nommés *-renoms.
                if [ -z "$erreur" ] && [[ "$cid" == *-renoms ]]; then
                    if [ ! -f "$projet/NOTES.md" ]; then
                        erreur="README renommé introuvable (NOTES.md)"
                    elif [ -f "$projet/README.md" ]; then
                        erreur="README.md aurait dû disparaître au profit de NOTES.md"
                    fi
                fi
            fi
            ;;
        maven)
            taches="verify"
            [ "$role" = "lib" ] && taches="verify install"
            if ! (cd "$projet" && JAVA_HOME="$jdk_home" "$MVN_BIN" -q $taches >"$TRAVAIL/$cid.log" 2>&1); then
                erreur="maven ($taches)"
            elif [ "$role" = "app" ]; then
                if ! (cd "$projet" && JAVA_HOME="$jdk_home" "$MVN_BIN" -q compile exec:java >"$TRAVAIL/$cid.sortie" 2>>"$TRAVAIL/$cid.log"); then
                    erreur="maven exec:java"
                elif [ "$langue" = "fr" ] && ! grep -q "$PREMIERE_LIGNE_FR" "$TRAVAIL/$cid.sortie"; then
                    erreur="sortie application inattendue (fr)"
                elif [ "$langue" = "en" ] && ! grep -q "$PREMIERE_LIGNE_EN" "$TRAVAIL/$cid.sortie"; then
                    erreur="sortie application inattendue (en)"
                fi
            fi
            if [ -z "$erreur" ] && [ "$tests" = "tests" ]; then
                rapports=$(ls "$projet"/target/surefire-reports/*.txt 2>/dev/null | head -1 || true)
                if [ -z "$rapports" ] || ! grep -q "Tests run: 3" "$rapports"; then
                    erreur="tests Maven non exécutés"
                fi
            fi
            ;;
        none)
            if [ "$template" = "java" ]; then
                if ! (cd "$projet" && mkdir -p out && "$jdk_home/bin/javac" -encoding UTF-8 -Xlint:all -Werror \
                    -d out $(find src/main/java -name '*.java')); then
                    erreur="javac"
                elif [ "$role" = "app" ]; then
                    paquet=$(grep -rh '^package ' "$projet/src/main/java" | head -1 | sed 's/package //;s/;//')
                    if ! (cd "$projet" && "$jdk_home/bin/java" -cp out "$paquet.Main" >"$TRAVAIL/$cid.sortie" 2>&1); then
                        erreur="java"
                    elif [ "$langue" = "fr" ] && ! grep -q "$PREMIERE_LIGNE_FR" "$TRAVAIL/$cid.sortie"; then
                        erreur="sortie application inattendue (fr)"
                    elif [ "$langue" = "en" ] && ! grep -q "$PREMIERE_LIGNE_EN" "$TRAVAIL/$cid.sortie"; then
                        erreur="sortie application inattendue (en)"
                    fi
                fi
            else
                # Kotlin sans build : compilation par copie dans un projet Gradle jetable.
                paquet=$(grep -rh '^package ' "$projet/src/main/kotlin" | head -1 | sed 's/package //;s/;//')
                jetable=$(mktemp -d)
                mkdir -p "$jetable/src/main"
                cp -r "$projet/src/main/kotlin" "$jetable/src/main/"
                { printf 'plugins { kotlin("jvm") version "2.2.21"\n'
                  if [ "$role" = "app" ]; then printf 'application\n}\napplication { mainClass.set("%s.MainKt") }\n' "$paquet"
                  else printf '}\n'; fi
                  printf 'repositories { mavenCentral() }\n'; } > "$jetable/build.gradle.kts"
                printf 'rootProject.name = "jetable-%s"\n' "$cid" > "$jetable/settings.gradle.kts"
                tache=build
                [ "$role" = "app" ] && tache=run
                if [ -n "$GRADLE_DIS" ]; then
                    if ! (cd "$jetable" && JAVA_HOME="$jdk_home" "$GRADLE_DIS" --no-daemon -q $tache >"$TRAVAIL/$cid.sortie" 2>"$TRAVAIL/$cid.log"); then
                        erreur="projet jetable ($tache)"
                    fi
                else
                    erreur="Gradle système absent pour le projet jetable"
                fi
                if [ -z "$erreur" ] && [ "$role" = "app" ]; then
                    if [ "$langue" = "fr" ] && ! grep -q "$PREMIERE_LIGNE_FR" "$TRAVAIL/$cid.sortie"; then
                        erreur="sortie application inattendue (fr)"
                    elif [ "$langue" = "en" ] && ! grep -q "$PREMIERE_LIGNE_EN" "$TRAVAIL/$cid.sortie"; then
                        erreur="sortie application inattendue (en)"
                    fi
                fi
                rm -rf "$jetable"
            fi
            ;;
        gradle-app)
            # Spring Boot : build + tests — @SpringBootTest démarre le contexte,
            # le repository et le contrôleur sont couverts (ADR 0075).
            # L3 (spring-boot 2.0.0) : langage/buildSystem/jdkVersion et les
            # quatre dépendances câblées (JPA+H2, Security, Actuator,
            # Validation) — le contexte charge avec elles, dans les deux
            # langages (src/main/kotlin ou src/main/java).
            cmd=$(gradle_cmd_pour "$projet")
            if [ -z "$cmd" ]; then
                erreur="ni wrapper ni Gradle système"
            elif ! (cd "$projet" && JAVA_HOME="$jdk_home" "$cmd" --no-daemon --console=plain -q build >/dev/null 2>"$TRAVAIL/$cid.log"); then
                erreur="gradle (build spring)"
            fi
            if [ -z "$erreur" ] && [ "$tests" = "tests" ] && [ ! -f "$projet/build/reports/tests/test/index.html" ]; then
                erreur="rapport de tests Spring absent"
            fi
            if [ -z "$erreur" ] && [ "$langue" = "fr" ] && ! grep -rq "Bonjour" "$projet/src/main"; then
                erreur="contenu Spring non traduit (fr)"
            fi
            if [ -z "$erreur" ] && [ "$langue" = "en" ] && ! grep -rq "Hello" "$projet/src/main"; then
                erreur="contenu Spring non traduit (en)"
            fi
            # Dépendances Spring toutes activées (cas nommé sb-deps).
            if [ -z "$erreur" ] && [ "$cid" = "sb-deps" ]; then
                for attendu in data.jpa "runtimeOnly(libs.h2)" starter.security starter.actuator starter.validation; do
                    if ! grep -q "$attendu" "$projet/build.gradle.kts"; then
                        erreur="dépendance Spring absente ($attendu)"
                    fi
                done
            fi
            # JDK exigé par le manifeste (jvmToolchain / JavaLanguageVersion).
            if [ -z "$erreur" ] && [ -f "$projet/build.gradle.kts" ] && ! grep -q "$jdk" "$projet/build.gradle.kts"; then
                erreur="toolchain JDK $jdk absente du build généré"
            fi
            ;;
        maven-spring)
            # Spring Boot sous Maven (L3, buildSystem=maven) : verify (tests
            # inclus) — pas d'exec:java (le service démarre un serveur web).
            if ! (cd "$projet" && JAVA_HOME="$jdk_home" "$MVN_BIN" -q verify >"$TRAVAIL/$cid.log" 2>&1); then
                erreur="maven (verify spring)"
            fi
            if [ -z "$erreur" ] && [ "$tests" = "tests" ]; then
                rapports=$(ls "$projet"/target/surefire-reports/*.txt 2>/dev/null | head -1 || true)
                if [ -z "$rapports" ] || ! grep -q "Tests run" "$rapports"; then
                    erreur="tests Maven Spring non exécutés"
                fi
            fi
            if [ -z "$erreur" ] && [ "$langue" = "fr" ] && ! grep -rq "Bonjour" "$projet/src/main"; then
                erreur="contenu Spring non traduit (fr)"
            fi
            if [ -z "$erreur" ] && [ "$langue" = "en" ] && ! grep -rq "Hello" "$projet/src/main"; then
                erreur="contenu Spring non traduit (en)"
            fi
            # Le pom exige le JDK du manifeste (java.version).
            if [ -z "$erreur" ] && ! grep -q "<java.version>$jdk</java.version>" "$projet/pom.xml"; then
                erreur="java.version $jdk absente du pom généré"
            fi
            ;;
        gradle-kmp)
            # KMP : build + tests jvmTest + exécution (Main.kt via la tâche run
            # JavaExec) — le expect/actual du Greeter doit compiler et courir.
            cmd=$(gradle_cmd_pour "$projet")
            if [ -z "$cmd" ]; then
                erreur="ni wrapper ni Gradle système"
            elif ! (cd "$projet" && JAVA_HOME="$jdk_home" "$cmd" --no-daemon --console=plain -q build run >"$TRAVAIL/$cid.sortie" 2>"$TRAVAIL/$cid.log"); then
                erreur="gradle (build kmp)"
            elif [ "$tests" = "tests" ] && [ ! -f "$projet/build/reports/tests/jvmTest/index.html" ]; then
                erreur="rapport de tests KMP absent"
            elif ! grep -q "Running on: JVM" "$TRAVAIL/$cid.sortie"; then
                erreur="sortie JVM inattendue"
            elif [ "$langue" = "fr" ] && ! grep -q "Bonjour, Ada" "$TRAVAIL/$cid.sortie"; then
                erreur="salutation JVM inattendue (fr)"
            elif [ "$langue" = "en" ] && ! grep -q "Hello, Ada" "$TRAVAIL/$cid.sortie"; then
                erreur="salutation JVM inattendue (en)"
            fi
            # Dépendances KMP : plugin serialization + fichiers d'usage (cas nommé kmp-deps).
            if [ -z "$erreur" ] && [ "$cid" = "kmp-deps" ]; then
                if ! grep -q "kotlin.serialization" "$projet/build.gradle.kts"; then
                    erreur="plugin serialization absent (KMP)"
                elif ! find "$projet/src/commonMain" -name "ConfigurationSalutation.kt" | grep -q .; then
                    erreur="usage serialization absent (KMP)"
                elif ! find "$projet/src/commonMain" -name "DelaisSalutation.kt" | grep -q .; then
                    erreur="usage coroutines absent (KMP)"
                elif ! find "$projet/src/commonMain" -name "HorodatageSalutation.kt" | grep -q .; then
                    erreur="usage datetime absent (KMP)"
                fi
            fi
            ;;
        gradle-android)
            # Android app : assembleDebug + tests unitaires de :app avec le SDK
            # local (CODEIDE_ANDROID_SDK), APK contrôlé (ADR 0075). Les cas
            # nommés contrôlent aussi les variantes (langage, type de projet).
            cmd=$(gradle_cmd_pour "$projet")
            if [ ! -d "$SDK_ANDROID/platforms" ]; then
                erreur="SDK Android introuvable (CODEIDE_ANDROID_SDK)"
            elif [ -z "$cmd" ]; then
                erreur="ni wrapper ni Gradle système"
            elif ! (cd "$projet" && JAVA_HOME="$jdk_home" ANDROID_HOME="$SDK_ANDROID" "$cmd" --no-daemon --console=plain -q :app:assembleDebug >/dev/null 2>"$TRAVAIL/$cid.log"); then
                erreur="gradle (assembleDebug)"
            elif [ ! -f "$projet/app/build/outputs/apk/debug/app-debug.apk" ]; then
                erreur="APK de débogage absent"
            elif [ "$tests" = "tests" ]; then
                if ! (cd "$projet" && JAVA_HOME="$jdk_home" ANDROID_HOME="$SDK_ANDROID" "$cmd" --no-daemon --console=plain -q :app:testDebugUnitTest >/dev/null 2>>"$TRAVAIL/$cid.log"); then
                    erreur="gradle (testDebugUnitTest)"
                elif ! ls "$projet"/app/build/test-results/testDebugUnitTest/TEST-*.xml >/dev/null 2>&1; then
                    erreur="résultats de tests Android absents"
                fi
            fi
            # Variante Java : aucune source .kt ne doit exister.
            if [ -z "$erreur" ] && [[ "$projet" == *-java* ]] && find "$projet/app/src" -name "*.kt" | grep -q .; then
                erreur="sources .kt inattendues (variante Java)"
            fi
            # Variante sans activité : aucun MainActivity ne doit exister.
            if [ -z "$erreur" ] && [[ "$projet" == *sansact* ]] && find "$projet/app/src" -name "MainActivity.*" | grep -q .; then
                erreur="MainActivity inattendu (variante sans activité)"
            fi
            # Variante tiroir : le menu du tiroir doit exister.
            if [ -z "$erreur" ] && [[ "$projet" == *tiroir* ]] && [ ! -f "$projet/app/src/main/res/menu/tiroir.xml" ]; then
                erreur="menu du tiroir absent"
            fi
            # Dépendances : Application Hilt + base Room présentes et câblées
            # (KSP dans le build, nom dans le manifeste) — cas nommés andr-deps*.
            if [ -z "$erreur" ] && [[ "$cid" == andr-deps* ]]; then
                case "$cid" in
                andr-deps)
                    attendu_app="AppDepsApplication.kt"; attendu_bdd="BddLocale.kt" ;;
                andr-deps-java)
                    attendu_app="JavaDepsApplication.java"; attendu_bdd="BddLocale.java" ;;
                esac
                if ! find "$projet/app/src/main" -name "$attendu_app" | grep -q .; then
                    erreur="classe Application Hilt absente ($attendu_app)"
                elif ! find "$projet/app/src/main" -name "$attendu_bdd" | grep -q .; then
                    erreur="base Room absente ($attendu_bdd)"
                elif ! grep -q "alias(libs.plugins.ksp)" "$projet/app/build.gradle.kts"; then
                    erreur="plugin KSP absent du build"
                elif ! grep -q "ksp(libs" "$projet/app/build.gradle.kts"; then
                    erreur="processeurs KSP absents des dépendances"
                fi
            fi
            ;;
        gradle-android-lib)
            # Bibliothèque Android : :library:assembleRelease produit
            # le .aar, tests unitaires du module — aucun APK attendu.
            cmd=$(gradle_cmd_pour "$projet")
            if [ ! -d "$SDK_ANDROID/platforms" ]; then
                erreur="SDK Android introuvable (CODEIDE_ANDROID_SDK)"
            elif [ -z "$cmd" ]; then
                erreur="ni wrapper ni Gradle système"
            elif ! (cd "$projet" && JAVA_HOME="$jdk_home" ANDROID_HOME="$SDK_ANDROID" "$cmd" --no-daemon --console=plain -q :library:assembleRelease >/dev/null 2>"$TRAVAIL/$cid.log"); then
                erreur="gradle (assembleRelease aar)"
            elif [ ! -f "$projet/library/build/outputs/aar/library-release.aar" ]; then
                erreur="AAR de release absent"
            elif [ "$tests" = "tests" ]; then
                if ! (cd "$projet" && JAVA_HOME="$jdk_home" ANDROID_HOME="$SDK_ANDROID" "$cmd" --no-daemon --console=plain -q :library:testDebugUnitTest >/dev/null 2>>"$TRAVAIL/$cid.log"); then
                    erreur="gradle (testDebugUnitTest lib)"
                elif ! ls "$projet"/library/build/test-results/testDebugUnitTest/TEST-*.xml >/dev/null 2>&1; then
                    erreur="résultats de tests bibliothèque absents"
                fi
            fi
            ;;
        gradle-plugin)
            # Plugin Gradle : build (validatePlugins inclus) +
            # tests ProjectBuilder ; publication maven locale et marqueur
            # de plugin contrôlés quand les tests sont demandés.
            cmd=$(gradle_cmd_pour "$projet")
            if [ -z "$cmd" ]; then
                erreur="ni wrapper ni Gradle système"
            elif ! (cd "$projet" && JAVA_HOME="$jdk_home" "$cmd" --no-daemon --console=plain -q build >/dev/null 2>"$TRAVAIL/$cid.log"); then
                erreur="gradle (build plugin)"
            elif [ "$tests" = "tests" ] && [ ! -f "$projet/build/reports/tests/test/index.html" ]; then
                erreur="rapport de tests plugin absent"
            elif [ "$tests" = "tests" ]; then
                if ! (cd "$projet" && JAVA_HOME="$jdk_home" "$cmd" --no-daemon --console=plain -q publishToMavenLocal >/dev/null 2>>"$TRAVAIL/$cid.log"); then
                    erreur="gradle (publishToMavenLocal)"
                elif ! ls "$HOME"/.m2/repository/com/example/*/*.gradle.plugin*/*/*.pom >/dev/null 2>&1; then
                    erreur="marqueur de plugin non publié"
                fi
            fi
            ;;
        esac
    fi

    duree=$(( $(date +%s%3N) - debut ))
    if [ -z "$erreur" ]; then
        combinaison_ok "$cid"
        printf '%s' "$EMPREINTE_ASSETS" > "$marqueur"
        # Hygiène disque : les artefacts de build d'une combinaison vérifiée
        # ne servent plus (le marqueur `.verifie` court-circuite tout
        # re-contrôle à la relance ; les checks structurels excluent déjà
        # build/ et .gradle) — libérer l'espace pour les suivantes.
        find "$projet" -maxdepth 2 -type d \( -name build -o -name .gradle \) -exec rm -rf {} +
        note "OK   $cid (${duree} ms)"
        printf '%s\n' "<testcase name=\"$cid\" classname=\"verify-templates\" time=\"$(awk "BEGIN{printf \"%.3f\", $duree/1000}")\" />" >> "$TRAVAIL/junit/cases.xml.part"
    else
        combinaison_ko "$cid" "$erreur"
        echec "KO   $cid : $erreur (journal : $TRAVAIL/$cid.log)"
        printf '%s\n' "<testcase name=\"$cid\" classname=\"verify-templates\" time=\"$(awk "BEGIN{printf \"%.3f\", $duree/1000}")\"><failure message=\"$(printf '%s' "$erreur" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g; s/"/\&quot;/g')\">$erreur</failure></testcase>" >> "$TRAVAIL/junit/cases.xml.part"
    fi
done < "$TRAVAIL/matrix.tsv"

# ---------------------------------------------------------------------------
# 5. Tableau final + rapports Markdown et JUnit XML (CI).
# ---------------------------------------------------------------------------
echo
echo "═══ Validation réelle des modèles — tableau combinaison → résultat ═══"
printf '%-24s | %s\n' "combinaison" "résultat"
printf '%s\n' "-------------------------+-------------------------------------------"
for ligne in "${lignes_tableau[@]}"; do
    echo "$ligne"
done
echo "--------------------------------------------------------------------------"

{
    echo "# Rapport verify-templates"
    echo
    echo "- Version CodeIDE : \`$VERSION_CODEIDE\`"
    echo "- Stratégie de matrice : \`$STRATEGIE\` (seuil $SEUIL) — dérivée des manifestes par \`generateur matrice\` (ADR 0157)"
    echo "- Combinaisons : $(wc -l < "$TRAVAIL/matrix.tsv") (matrice dérivée + cas nommés \`scripts/verify-templates-cas-nomes.json\`)"
    echo
    echo "| combinaison | résultat |"
    echo "|---|---|"
    for ligne in "${lignes_tableau[@]}"; do
        cid="${ligne%% |*}"
        etat="${ligne#* | }"
        echo "| $cid | $etat |"
    done
} > "$TRAVAIL/rapport.md"

{
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<testsuite name="verify-templates" tests="'"$(wc -l < "$TRAVAIL/matrix.tsv")"'" hostname="local">'
    [ -f "$TRAVAIL/junit/cases.xml.part" ] && cat "$TRAVAIL/junit/cases.xml.part"
    echo '</testsuite>'
} > "$TRAVAIL/rapport-junit.xml"
rm -f "$TRAVAIL/junit/cases.xml.part"

if [ "$echec_global" -ne 0 ]; then
    echec "AU MOINS UNE COMBINAISON A ÉCHOUÉ (journaux dans $TRAVAIL — rapports : rapport.md, rapport-junit.xml)"
    exit 1
fi
note "Toutes les combinaisons sont vertes. Rapports : $TRAVAIL/rapport.md, $TRAVAIL/rapport-junit.xml"
