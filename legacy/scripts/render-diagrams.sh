#!/usr/bin/env bash
# Local preview of the wiki with inline PlantUML diagrams rendered to SVG.
# Copies docs/wiki to .wiki-preview/ and renders the inline ```plantuml blocks there,
# leaving the repository source untouched.
# Requires a `plantuml` command (brew/apt install plantuml graphviz), or set PLANTUML_CMD.
set -euo pipefail

cd "$(dirname "$0")/.."
PREVIEW=".wiki-preview"

rm -rf "$PREVIEW"
cp -r docs/wiki "$PREVIEW"
node scripts/render-wiki-diagrams.mjs "$PREVIEW"

echo "Preview rendered in $PREVIEW/ (diagrams under $PREVIEW/diagrams/auto/)."
