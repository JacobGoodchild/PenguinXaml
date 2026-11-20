#!/usr/bin/env bash
# Mouse/keyboard on the Xvfb display :99 (test machine only). See tools/Ui.java.
DISPLAY=:99 exec java "$(dirname "$0")/Ui.java" "$@" 2>/dev/null
