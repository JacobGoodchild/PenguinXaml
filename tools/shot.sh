#!/usr/bin/env bash
# Screenshot of the Xvfb display :99 (test machine only): tools/shot.sh out.png [crop like 1400x900+0+0]
DISPLAY=:99 import -window root ${2:+-crop "$2"} "$1"
