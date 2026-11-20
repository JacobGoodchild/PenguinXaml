#!/usr/bin/env bash
# Starts the sandbox IntelliJ IDEA (free tier) with Penguin XAML on the Xvfb display :99, opening $1.
# Test-machine helper for UI screenshots (the sandbox Rider needs a login). Not needed on your own computer.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
pgrep -x Xvfb >/dev/null || (Xvfb :99 -screen 0 1920x1080x24 -nolisten tcp >/dev/null 2>&1 &)
pkill -f "$root/.intellijPlatform/sandbox" || true  # only the test IDE started from this repo
sleep 2
export DISPLAY=:99 PATH="/opt/dotnet:$PATH"
cd "$root"
extra=()
[ -n "${3:-}" ] && extra=(-PopenFile="$3")
[ -n "${UISCALE:-}" ] && extra+=(-PuiScale="$UISCALE")  # UISCALE=2: as on a 200 % screen
nohup ./gradlew runIdea -PopenPath="$1" -PtestMachine "${extra[@]}" --console=plain > "${2:-/tmp/penguinxaml-runidea.log}" 2>&1 &
