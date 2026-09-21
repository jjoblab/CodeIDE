#!/usr/bin/env bash
# CodeIDE — incrémente la version (section 9.1 du prompt maître).
# Usage : scripts/bump-version.sh <major|minor|patch>
#   - met à jour version.properties (VERSION_NAME et VERSION_CODE
#     = major*10000 + minor*100 + patch, strictement croissant) ;
#   - prépare une entrée de CHANGELOG.md non publiée.

set -euo pipefail

# ---------------------------------------------------------------------------
# Lecture de la version courante.
# ---------------------------------------------------------------------------
fichier_version="version.properties"
[ -f "$fichier_version" ] || { echo "ERREUR : $fichier_version introuvable" >&2; exit 1; }

version_courante=$(sed -n 's/^VERSION_NAME=//p' "$fichier_version" | head -1)
[ -n "$version_courante" ] || { echo "ERREUR : VERSION_NAME illisible" >&2; exit 1; }

IFS='.' read -r major minor patch <<<"$version_courante"

# ---------------------------------------------------------------------------
# Calcul de la nouvelle version.
# ---------------------------------------------------------------------------
palier="${1:-}"
case "$palier" in
    major) major=$((major + 1)); minor=0; patch=0 ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    patch) patch=$((patch + 1)) ;;
    *) echo "Usage : $0 <major|minor|patch>" >&2; exit 1 ;;
esac

nouvelle_version="$major.$minor.$patch"
nouveau_code=$((major * 10000 + minor * 100 + patch))
code_courant=$(sed -n 's/^VERSION_CODE=//p' "$fichier_version" | head -1)
if [ "$nouveau_code" -le "$code_courant" ]; then
    echo "ERREUR : VERSION_CODE ne décroît pas ($nouveau_code <= $code_courant)" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Écriture de version.properties.
# ---------------------------------------------------------------------------
cat > "$fichier_version" <<FIN
# Source unique de vérité pour la version de CodeIDE (section 9.1 du prompt maître).
# Lue par les convention plugins de build-logic.
# VERSION_CODE = major * 10000 + minor * 100 + patch (strictement croissant).
VERSION_NAME=$nouvelle_version
VERSION_CODE=$nouveau_code
FIN

# ---------------------------------------------------------------------------
# Préparation de l'entrée de CHANGELOG.md.
# ---------------------------------------------------------------------------
date_du_jour=$(date +%F)
marqueur="## [Non publié]"
if [ -f CHANGELOG.md ] && ! grep -q "^## \[$nouvelle_version\]" CHANGELOG.md; then
    if ! grep -qF "$marqueur" CHANGELOG.md; then
        printf '\n%s\n\n### Ajouté\n\n- (à compléter)\n' "$marqueur" >> CHANGELOG.md
    fi
    sed -i "s|^$marqueur|## [$nouvelle_version] – $date_du_jour|" CHANGELOG.md
fi

echo "version : $version_courante → $nouvelle_version (VERSION_CODE $code_courant → $nouveau_code)"
echo "CHANGELOG.md : entrée ouverte pour $nouvelle_version — complétez-la avant la livraison."
