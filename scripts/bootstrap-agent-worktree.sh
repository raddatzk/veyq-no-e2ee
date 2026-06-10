#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/bootstrap-agent-worktree.sh [options]

Prepare a fresh local agent/Codex worktree for veyq development.

Options:
  --idea-only        Copy IntelliJ local project files only.
  --no-idea         Skip IntelliJ local project files.
  --no-deps         Skip npm dependency installation.
  --no-openapi      Skip frontend OpenAPI client generation.
  --template PATH   Source checkout for local IntelliJ files.
  -h, --help        Show this help.

Environment:
  VEYQ_WORKTREE_TEMPLATE       Source checkout for local IntelliJ files.
  VEYQ_BOOTSTRAP_NPM_COMMAND   npm install command, default: install.

The template checkout is expected to contain ignored local IntelliJ files such as
.idea/runConfigurations/*.xml. Those files may contain secrets and are copied
only into the current worktree.
USAGE
}

repo_root() {
  git rev-parse --show-toplevel
}

main_worktree_path() {
  git worktree list --porcelain | awk '
    /^worktree / { path = substr($0, 10) }
    /^branch refs\/heads\/main$/ { print path; exit }
  '
}

copy_if_exists() {
  local source_file="$1"
  local target_file="$2"

  if [ -f "$source_file" ]; then
    mkdir -p "$(dirname "$target_file")"
    cp "$source_file" "$target_file"
    echo "Copied ${source_file#$template_root/}"
  fi
}

copy_intellij_files() {
  local template_root="$1"
  local target_root="$2"

  if [ ! -d "$template_root/.idea" ]; then
    echo "No .idea directory found in template checkout: $template_root" >&2
    return 1
  fi

  mkdir -p "$target_root/.idea/runConfigurations"
  copy_if_exists "$template_root/.idea/gradle.xml" "$target_root/.idea/gradle.xml"
  copy_if_exists "$template_root/.idea/misc.xml" "$target_root/.idea/misc.xml"
  copy_if_exists "$template_root/.idea/runConfigurations/Run_Backend.xml" "$target_root/.idea/runConfigurations/Run_Backend.xml"
  copy_if_exists "$template_root/.idea/runConfigurations/Run_Frontend.xml" "$target_root/.idea/runConfigurations/Run_Frontend.xml"
  copy_if_exists "$template_root/.idea/runConfigurations/Run_Collaboration.xml" "$target_root/.idea/runConfigurations/Run_Collaboration.xml"
}

run_npm_install() {
  local package_dir="$1"
  local npm_command="${VEYQ_BOOTSTRAP_NPM_COMMAND:-install}"

  if [ ! -f "$package_dir/package.json" ]; then
    return 0
  fi

  echo "Installing npm dependencies in ${package_dir#$root/}"
  (cd "$package_dir" && npm "$npm_command")
}

copy_idea=true
install_deps=true
generate_openapi=true
template_root="${VEYQ_WORKTREE_TEMPLATE:-}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --idea-only)
      copy_idea=true
      install_deps=false
      generate_openapi=false
      ;;
    --no-idea)
      copy_idea=false
      ;;
    --no-deps)
      install_deps=false
      ;;
    --no-openapi)
      generate_openapi=false
      ;;
    --template)
      if [ "$#" -lt 2 ]; then
        echo "--template requires a path" >&2
        exit 2
      fi
      template_root="$2"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

root="$(repo_root)"
if [ -z "$template_root" ]; then
  template_root="$(main_worktree_path)"
fi
if [ -z "$template_root" ]; then
  template_root="$root"
fi

if [ "$copy_idea" = true ]; then
  copy_intellij_files "$template_root" "$root"
fi

if [ "$install_deps" = true ]; then
  if ! command -v npm >/dev/null 2>&1; then
    echo "npm is required for dependency installation" >&2
    exit 1
  fi
  run_npm_install "$root/frontend"
  run_npm_install "$root/collaboration"
fi

if [ "$generate_openapi" = true ]; then
  echo "Generating frontend OpenAPI client"
  (cd "$root/frontend" && npm run generate:api)
fi

echo "Worktree bootstrap complete: $root"
