#!/usr/bin/env bash
# CodeIDE — modules Gradle touchés par les changements en cours
# (docs/STRATEGIE_VERIFICATION.md §3).
#
# Usage :
#   ./scripts/modules-touches.sh                      # arbre de travail vs HEAD
#   ./scripts/modules-touches.sh origin/main...HEAD   # série de commits vs main
#
# Sortie : une coordonnée Gradle par module touché (`:feature:editor` …),
# ou `RACINE` si un fichier de build partagé est touché (→ chaîne RACINE
# obligatoire). Les docs (*.md, docs/**), .github/** et scripts/** ne
# produisent rien : ils n'affectent pas le graphe de modules.
# Code de sortie : 0 = modules seulement ; 1 = chaîne RACINE requise.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

base="${1:-HEAD}"
tmp_list=$(mktemp)
tmp_out=$(mktemp)
trap 'rm -f "$tmp_list" "$tmp_out"' EXIT

# Union : fichiers modifiés/supprimés + fichiers NON SUIVIS (nouveaux)
# +, si une base git est fournie, les commits de la série.
git diff --name-only HEAD >> "$tmp_list" 2>/dev/null || true
git ls-files --others --exclude-standard >> "$tmp_list" 2>/dev/null || true
if [ "$base" != "HEAD" ]; then
    git diff --name-only "$base" >> "$tmp_list" 2>/dev/null || true
fi
sort -u "$tmp_list" -o "$tmp_list"

status=0
while IFS= read -r fichier; do
    [ -n "$fichier" ] || continue
    case "$fichier" in
        # Partagés de build → chaîne RACINE obligatoire (§3) :
        # réglages, catalogues, plugins et tout build.gradle.kts.
        gradle.properties|settings.gradle.kts|gradle/libs.versions.toml|\
        *.gradle.kts|gradle/*|build-logic/*|config/*|*.toml)
            echo "RACINE"
            status=1
            continue ;;
        # Ne touche pas le graphe de modules.
        *.md|docs/*|.github/*|scripts/*|*.txt|*.json|*.png|*.html)
            continue ;;
    esac
    # Module = répertoire le plus proche contenant un build.gradle.kts.
    dir=$(dirname "$fichier")
    module=""
    while [ "$dir" != "." ] && [ -n "$dir" ]; do
        if [ -f "$dir/build.gradle.kts" ]; then
            module="$dir"
            break
        fi
        dir=$(dirname "$dir")
    done
    if [ -n "$module" ]; then
        echo ":$(echo "$module" | tr '/' ':')"
    else
        # Hors module (fichier racine, bunndle, etc.) → prudence : RACINE.
        echo "RACINE"
        status=1
    fi
done < "$tmp_list" >> "$tmp_out"

sort -u "$tmp_out"
exit $status
