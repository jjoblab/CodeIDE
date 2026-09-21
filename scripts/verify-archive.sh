#!/usr/bin/env bash
# CodeIDE — vérifie qu'une archive de livraison est saine et autonome (section 9.2).
# Usage : scripts/verify-archive.sh <CodeIDE-vX.Y.Z-etapeNN.zip>
#   1. unzip -t : intégrité de l'archive ;
#   2. absence de fichiers interdits (build/, .gradle/, local.properties, dist/…) ;
#   3. extraction dans un répertoire temporaire puis ./gradlew assembleDebug
#      depuis cette copie : l'archive doit se suffire à elle-même.

set -euo pipefail

# ---------------------------------------------------------------------------
# Paramètres.
# ---------------------------------------------------------------------------
archive="${1:-}"
[ -n "$archive" ] && [ -f "$archive" ] || {
    echo "Usage : $0 <CodeIDE-vX.Y.Z-etapeNN.zip>" >&2
    exit 1
}

fichier_version="version.properties"
sdk_dir=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | head -1)
[ -n "$sdk_dir" ] || { echo "ERREUR : local.properties requis pour régénérer le sdk.dir de test" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Intégrité.
# ---------------------------------------------------------------------------
echo "[verify-archive] test d'intégrité (unzip -t)…"
unzip -tq "$archive" >/dev/null
echo "[verify-archive] archive intègre"

# ---------------------------------------------------------------------------
# 2. Fichiers interdits (section 9.3 : exclusions).
# ---------------------------------------------------------------------------
echo "[verify-archive] contrôle des exclusions…"
interdits=$(unzip -Z1 "$archive" | grep -E '(^|/)(build|\.gradle|\.idea|\.kotlin|dist|captures|\.cxx)/|(^|/)local\.properties$|\.iml$|\.keystore$|\.jks$|\.DS_Store$' || true)
if [ -n "$interdits" ]; then
    echo "ERREUR : fichiers interdits dans l'archive :" >&2
    echo "$interdits" >&2
    exit 1
fi
echo "[verify-archive] aucun fichier interdit"

# ---------------------------------------------------------------------------
# 3. Contenu obligatoire (wrapper, build-logic, sources).
# ---------------------------------------------------------------------------
echo "[verify-archive] contrôle du contenu obligatoire…"
for obligatoire in gradlew gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties build-logic/build.gradle.kts settings.gradle.kts version.properties; do
    unzip -Z1 "$archive" | grep -qx "$obligatoire" || {
        echo "ERREUR : $obligatoire manquant dans l'archive" >&2
        exit 1
    }
done
echo "[verify-archive] contenu obligatoire présent"

# ---------------------------------------------------------------------------
# 4. Build autonome depuis une copie extraite.
# ---------------------------------------------------------------------------
tmp=$(mktemp -d /tmp/codeide-verify.XXXXXX)
nettoyage() { rm -rf "$tmp"; }
trap nettoyage EXIT

echo "[verify-archive] extraction de contrôle dans $tmp…"
unzip -q "$archive" -d "$tmp"

printf 'sdk.dir=%s\n' "$sdk_dir" > "$tmp/local.properties"
chmod +x "$tmp/gradlew"

echo "[verify-archive] build assembleDebug depuis la copie extraite…"
( cd "$tmp" && ./gradlew --no-daemon assembleDebug --console=plain -q )

apk="$tmp/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$apk" ] || { echo "ERREUR : APK non produit depuis l'archive" >&2; exit 1; }

echo "[verify-archive] SUCCÈS : l'archive est saine et autonome"
