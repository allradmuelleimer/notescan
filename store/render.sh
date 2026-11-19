#!/usr/bin/env bash
# Rendert die Store-Grafiken mit Chrome headless: ./render.sh [name …]  (ohne Namen: alle)
# Ausgabe: en/<name>.png (Telefon 1080×1920, Feature-Grafik 1024×500, Symbol 512×512)
cd "$(dirname "$0")"
CHROME="/c/Program Files/Google/Chrome/Application/chrome.exe"
names=("$@"); [ ${#names[@]} -eq 0 ] && names=($(cd src && ls *.html | sed 's/\.html$//'))
for n in "${names[@]}"; do
  size="1080,1920"
  case "$n" in feature*) size="1024,500";; icon*) size="512,512";; esac
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --allow-file-access-from-files \
    --virtual-time-budget=3000 --window-size=$size --screenshot="$(cygpath -w "$PWD/en/$n.png")" "file:///$(cygpath -m "$PWD/src/$n.html")" 2>/dev/null
  echo "$n → en/$n.png"
done
