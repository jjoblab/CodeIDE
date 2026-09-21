#!/usr/bin/env bash
# CodeIDE — installation et validation de l'environnement de build (section 4).
# Idempotent : peut être relancé sans risque ; il installe ce qui manque
# et valide ce qui existe. Cible : Linux (Ubuntu/Debian).

set -euo pipefail

# ---------------------------------------------------------------------------
# Paramètres (surchargeables par variables d'environnement).
# ---------------------------------------------------------------------------
JDK_HOME_CIBLE="${JDK_HOME_CIBLE:-/home/z/tools/jdk-21}"
SDK_CIBLE="${SDK_CIBLE:-/home/z/android-sdk}"
JDK_VERSION_ATTENDUE="21"
CMDLINE_TOOLS_BUILD="13114758"
PLATEFORME="platforms;android-37.2"
BUILD_TOOLS="build-tools;36.0.0"

# ---------------------------------------------------------------------------
# Fonctions utilitaires.
# ---------------------------------------------------------------------------
log()  { printf '\033[1;32m[setup]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[setup]\033[0m %s\n' "$*"; }
err()  { printf '\033[1;31m[setup]\033[0m %s\n' "$*" >&2; }
verif_commande() { command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------------------
# 1. Outils système de base.
# ---------------------------------------------------------------------------
verifier_outils_systeme() {
    local manquants=0
    for outil in git zip unzip curl sha256sum; do
        if verif_commande "$outil"; then
            log "outil '$outil' : présent"
        else
            err "outil '$outil' : INTROUVABLE — installez-le (paquet $outil) puis relancez"
            manquants=1
        fi
    done
    [ "$manquants" -eq 0 ] || exit 1
}

# ---------------------------------------------------------------------------
# 2. JDK 21.
# ---------------------------------------------------------------------------
verifier_ou_installer_jdk() {
    if [ -x "$JDK_HOME_CIBLE/bin/javac" ]; then
        log "JDK : $($JDK_HOME_CIBLE/bin/java -version 2>&1 | head -1) (dans $JDK_HOME_CIBLE)"
        return
    fi

    log "JDK absent : téléchargement de Temurin $JDK_VERSION_ATTENDUE LTS…"
    local racine
    racine="$(dirname "$JDK_HOME_CIBLE")"
    mkdir -p "$racine"
    local reponse url
    reponse=$(curl -fsS -m 60 \
        "https://api.adoptium.net/v3/assets/latest/${JDK_VERSION_ATTENDUE}/hotspot?architecture=x64&image_type=jdk&os=linux") || {
        err "impossible d'interroger api.adoptium.net — vérifiez le réseau"
        exit 1
    }
    url=$(printf '%s' "$reponse" | grep -o '"link": *"[^"]*"' | head -1 | sed 's/"link": *"//;s/"$//')
    curl -fsSL -m 600 -o "$racine/temurin.tar.gz" "$url"
    tar -xzf "$racine/temurin.tar.gz" -C "$racine"
    rm -f "$racine/temurin.tar.gz"
    local extrait
    extrait=$(ls -d "$racine"/jdk-"${JDK_VERSION_ATTENDUE}"* | head -1)
    rm -rf "$JDK_HOME_CIBLE"
    mv "$extrait" "$JDK_HOME_CIBLE"
    log "JDK installé : $($JDK_HOME_CIBLE/bin/java -version 2>&1 | head -1)"
}

# ---------------------------------------------------------------------------
# 3. SDK Android (cmdline-tools, plateforme, build-tools).
# ---------------------------------------------------------------------------
verifier_ou_installer_sdk() {
    local sdkmanager="$SDK_CIBLE/cmdline-tools/latest/bin/sdkmanager"

    if [ ! -x "$sdkmanager" ]; then
        log "cmdline-tools absents : téléchargement (build $CMDLINE_TOOLS_BUILD)…"
        mkdir -p "$SDK_CIBLE"
        curl -fsSL -m 600 -o "$SDK_CIBLE/cmdtools.zip" \
            "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
        unzip -q -o "$SDK_CIBLE/cmdtools.zip" -d "$SDK_CIBLE"
        rm -rf "$SDK_CIBLE/cmdline-tools-tmp" "$SDK_CIBLE/cmdline-tools/latest"
        mv "$SDK_CIBLE/cmdline-tools" "$SDK_CIBLE/cmdline-tools-tmp"
        mkdir -p "$SDK_CIBLE/cmdline-tools"
        mv "$SDK_CIBLE/cmdline-tools-tmp" "$SDK_CIBLE/cmdline-tools/latest"
        rm -f "$SDK_CIBLE/cmdtools.zip"
    fi
    log "cmdline-tools : $($sdkmanager --version 2>/dev/null | tail -1 | tr -d ' ' )"

    log "acceptation des licences du SDK…"
    export JAVA_HOME="$JDK_HOME_CIBLE"
    yes | "$sdkmanager" --licenses >/dev/null 2>&1 || true

    log "installation de platform-tools, $PLATEFORME, $BUILD_TOOLS…"
    "$sdkmanager" --install "platform-tools" "$PLATEFORME" "$BUILD_TOOLS" >/dev/null
    log "SDK Android : installé dans $SDK_CIBLE"
}

# ---------------------------------------------------------------------------
# 4. Wrapper Gradle (jamais un Gradle système — section 4 du prompt).
# ---------------------------------------------------------------------------
verifier_wrapper() {
    if [ ! -x ./gradlew ]; then
        err "gradlew absent à la racine du projet — le wrapper est versionné, exécutez :"
        err "  git checkout gradlew gradle/wrapper/  # ou régénérez-le"
        exit 1
    fi
    log "wrapper Gradle : présent (./gradlew)"
}

# ---------------------------------------------------------------------------
# 5. local.properties (jamais versionné).
# ---------------------------------------------------------------------------
generer_local_properties() {
    if [ -f local.properties ]; then
        log "local.properties : déjà présent (non versionné)"
    else
        printf 'sdk.dir=%s\n' "$SDK_CIBLE" > local.properties
        log "local.properties : généré (sdk.dir=$SDK_CIBLE)"
    fi
}

# ---------------------------------------------------------------------------
# 6. Vérifications finales (sorties collées dans le rapport d'étape).
# ---------------------------------------------------------------------------
verifications_finales() {
    log "=== VÉRIFICATIONS FINALES ==="
    "$JDK_HOME_CIBLE/bin/java" -version
    "$JDK_HOME_CIBLE/bin/javac" -version
    "$SDK_CIBLE/cmdline-tools/latest/bin/sdkmanager" --list_installed | head -20
    ./gradlew --version | head -6
    git --version
    echo "Maven (validation des templates, étape 9 uniquement) :"
    verif_commande mvn && mvn -v | head -2 || warn "mvn absent — requis uniquement à partir de l'étape 9"
    echo "Émulateur : non vérifié ici (optionnel ; KVM requis)"
}

# ---------------------------------------------------------------------------
# Point d'entrée.
# ---------------------------------------------------------------------------
main() {
    log "démarrage de la configuration de l'environnement CodeIDE"
    verifier_outils_systeme
    verifier_ou_installer_jdk
    verifier_ou_installer_sdk
    verifier_wrapper
    generer_local_properties
    verifications_finales
    log "environnement prêt : source scripts/env.sh puis ./gradlew check"
}

main "$@"
