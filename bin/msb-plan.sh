#!/usr/bin/env bash
# Dev launcher: run Stage 1 (planner) dry-run INSIDE microsandbox.
#
# Deliberate dev-time choice: ready `clojure` base image, PINNED BY DIGEST, so we
# do not rebuild an image on every code change. The universal Nix-built image is
# the deferred target (tracked separately).
#
# Best-practice constraints kept even in dev:
#  - NO secrets are injected (the planner needs none) and --no-net (no egress).
#  - NO live host mounts (-v): researcher code is copied in (tracked files only),
#    and data repos are CLONED at HEAD into a temp dir, then copied in. The guest
#    gets an immutable, isolated tree; the host working copies are never exposed.
set -euo pipefail

export PATH="$HOME/.microsandbox/bin:$PATH"

# clojure:temurin-21-tools-deps-bookworm, pinned by digest
IMAGE="docker.io/clojure:temurin-21-tools-deps-bookworm@sha256:1deda2766e490e41a575ad2b13a2fff1260a6d72fecc6cb1a993b8dbb84b36e7"

RESEARCHER_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BLOG_SRC="${RESEARCHER_BLOG_SRC:-$HOME/andysmith-ai/andysmith.ai}"
WIKI_SRC="${RESEARCHER_WIKI_SRC:-$HOME/sm-th/smith-wiki}"

tmp="$(mktemp -d)"
cleanup() { rm -rf "$tmp"; msb rm researcher-plan >/dev/null 2>&1 || true; }
trap cleanup EXIT

# Clone data repos at current HEAD: clean, pinned working tree (full history for
# the git delta), excluding gitignored build output (_site, node_modules).
git clone --quiet "file://$BLOG_SRC" "$tmp/blog"
git clone --quiet "file://$WIKI_SRC" "$tmp/wiki"

msb run --name researcher-plan --replace \
  --copy-dir  "$RESEARCHER_DIR/src:/work/src" \
  --copy-file "$RESEARCHER_DIR/deps.edn:/work/deps.edn" \
  --copy-file "$RESEARCHER_DIR/config.edn:/work/config.edn" \
  --copy-dir  "$tmp/blog:/repos/blog" \
  --copy-dir  "$tmp/wiki:/repos/wiki" \
  -w /work \
  -e RESEARCHER_BLOG_ROOT=/repos/blog \
  -e RESEARCHER_WIKI_ROOT=/repos/wiki \
  -e LANG=C.UTF-8 \
  --no-net \
  "$IMAGE" \
  -- clojure -M -m researcher.main plan
