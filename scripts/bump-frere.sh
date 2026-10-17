#!/usr/bin/env bash
# bump-frere.sh — stabilisation d'un frère en UNE COMMANDE (mode binaire
# en premier, arbitrage du 2026-10-18).
#
#   scripts/bump-frere.sh <tooling|lsp|layoutlib> [patch|minor|major]
#
# Chaîne : version suivante calculée depuis version.properties → commit +
# tag v* dans le dépôt frère → push → la CI publish.yml publie les paquets
# → attente + vérification du registre (maven-metadata.xml, exactement ce
# que Gradle résout) → mise à jour de LA ligne du catalogue
# gradle/libs.versions.toml côté CodeIDE → commit + push.
#
# Le jeton vient de GITHUB_TOKEN, CODEIDE_PACKAGES_PAT ou
# <CodeIDE>/.gh-token (scopes repo, workflow — le push passe par l'URL).
set -euo pipefail

niveau="${2:-patch}"
case "$niveau" in
  patch|minor|major) ;;
  *) echo "niveau invalide : « $niveau » (patch|minor|major)" >&2 ; exit 2 ;;
esac

racine="$(cd "$(dirname "$0")/.." && pwd)"
case "${1:-}" in
  tooling)   depot="codeide-tooling" ; cle="codeide-tooling" ; temoin="com/github/jjoblab/codeide-tooling/server" ;;
  lsp)       depot="codeide-lsp"     ; cle="codeide-lsp"     ; temoin="com/github/jjoblab/codeide-lsp/host" ;;
  layoutlib) depot="LayoutLib"       ; cle="layoutlib"       ; temoin="com/github/jjoblab/LayoutLib/module-inflater" ;;
  *) echo "frère invalide : « ${1:-} » (tooling|lsp|layoutlib)" >&2 ; exit 2 ;;
esac
frere="$racine/../$depot"
[ -d "$frere/.git" ] || { echo "dépôt frère absent : $frere" >&2 ; exit 1 ; }

jeton="${GITHUB_TOKEN:-${CODEIDE_PACKAGES_PAT:-$(cat "$racine/.gh-token" 2>/dev/null || true)}}"
[ -n "$jeton" ] || { echo "aucun jeton (GITHUB_TOKEN, CODEIDE_PACKAGES_PAT ou .gh-token)" >&2 ; exit 1 ; }

# Le frère doit être propre : le script committe version.properties.
if [ -n "$(git -C "$frere" status --porcelain)" ] ; then
  echo "$depot a des modifications non commitées — commit ou stash d'abord" >&2 ; exit 1
fi

version_courante="$(sed -n 's/^VERSION_NAME=//p' "$frere/version.properties" | head -1)"
code_courant="$(sed -n 's/^VERSION_CODE=//p' "$frere/version.properties" | head -1)"
if ! [[ "$version_courante" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] ; then
  echo "version non sémantique dans $depot : « $version_courante »" >&2 ; exit 1
fi
major="${BASH_REMATCH[1]}" ; minor="${BASH_REMATCH[2]}" ; patch="${BASH_REMATCH[3]}"
case "$niveau" in
  major) major=$((major + 1)) ; minor=0 ; patch=0 ;;
  minor) minor=$((minor + 1)) ; patch=0 ;;
  patch) patch=$((patch + 1)) ;;
esac
nouvelle="$major.$minor.$patch"
nouveau_code=$((major * 10000 + minor * 100 + patch))
if [ "$nouveau_code" -le "$code_courant" ] ; then
  echo "VERSION_CODE non strictement croissant ($code_courant → $nouveau_code)" >&2 ; exit 1
fi

branche="$(git -C "$frere" branch --show-current)"
echo "→ $depot ($branche) : $version_courante → $nouvelle (VERSION_CODE $code_courant → $nouveau_code)"
sed -i "s/^VERSION_NAME=.*/VERSION_NAME=$nouvelle/" "$frere/version.properties"
sed -i "s/^VERSION_CODE=.*/VERSION_CODE=$nouveau_code/" "$frere/version.properties"
git -C "$frere" add version.properties
git -C "$frere" commit -q -m "release: v$nouvelle"
git -C "$frere" tag "v$nouvelle"
git -C "$frere" push -q "https://jjoblab:$jeton@github.com/jjoblab/$depot.git" "$branche" "v$nouvelle"

sha="$(git -C "$frere" rev-parse HEAD)"
echo "→ publication : attente du workflow publish.yml (sha ${sha:0:8})…"
publie=""
for _ in $(seq 1 60) ; do
  sleep 15
  reponse="$(curl -sf -H "Authorization: Bearer $jeton" \
    "https://api.github.com/repos/jjoblab/$depot/actions/workflows/publish.yml/runs?head_sha=$sha&per_page=1" || true)"
  statut="$(printf '%s' "$reponse" | python3 -c "
import json, sys
runs = json.load(sys.stdin).get('workflow_runs', [])
print(runs[0]['status'] + ':' + str(runs[0].get('conclusion')) if runs else 'absent')" 2>/dev/null || echo "erreur")"
  echo "   $statut"
  case "$statut" in
    completed:success) publie="oui" ; break ;;
    completed:*) echo "publication ÉCHOUÉE ($statut) — voir https://github.com/jjoblab/$depot/actions" >&2 ; exit 1 ;;
  esac
done
[ -n "$publie" ] || { echo "timeout : publication non confirmée après 15 min" >&2 ; exit 1 ; }

# Vérification du REGISTRE (maven-metadata.xml = ce que Gradle résout).
if ! curl -sf -u "jjoblab:$jeton" \
    "https://maven.pkg.github.com/jjoblab/$depot/$temoin/maven-metadata.xml" \
    | grep -q "<version>$nouvelle</version>" ; then
  echo "$nouvelle non visible dans le registre ($temoin)" >&2 ; exit 1
fi
echo "→ registre : $temoin $nouvelle visible"

# Catalogue CodeIDE : LA ligne à tenir alignée.
sed -i "s/^$cle = \".*\"/$cle = \"$nouvelle\"/" "$racine/gradle/libs.versions.toml"
git -C "$racine" add gradle/libs.versions.toml
git -C "$racine" commit -q -m "chore(deps): $cle $nouvelle (bump-frere)"
git -C "$racine" push -q "https://jjoblab:$jeton@github.com/jjoblab/CodeIDE.git" \
  "$(git -C "$racine" branch --show-current)"
echo "✓ terminé : $cle $nouvelle épinglé dans CodeIDE et poussé"
