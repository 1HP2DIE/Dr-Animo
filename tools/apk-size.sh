#!/usr/bin/env bash
# Print the release APK's size, dex count and five largest entries.
#
#   tools/apk-size.sh [path/to/app-release.apk]
#
# Defaults to :app's release output. Exits 1 when the APK is missing, so a size
# check can gate a pipeline instead of silently passing on a stale file.
set -euo pipefail

APK="${1:-app/build/outputs/apk/release/app-release.apk}"

if [[ ! -f "$APK" ]]; then
    echo "apk-size: no APK at $APK (run ./gradlew :app:assembleRelease)" >&2
    exit 1
fi

FILE_BYTES=$(stat -c %s "$APK")

# unzip -v entry columns: Length Method Size Cmpr Date Time CRC-32 Name
unzip -v "$APK" | awk -v apk="$APK" -v fileBytes="$FILE_BYTES" '
NF >= 8 && $1 ~ /^[0-9]+$/ {
    raw = $1 + 0
    comp = $3 + 0
    name = $8
    if (name ~ /^classes[0-9]*\.dex$/)        cat = "dex"
    else if (name ~ /^res\//)                  cat = "res"
    else if (name == "resources.arsc")         cat = "resources.arsc"
    else if (name ~ /^assets\//)               cat = "assets"
    else if (name ~ /^lib\//)                  cat = "lib"
    else if (name ~ /^META-INF\//)             cat = "META-INF"
    else if (name == "AndroidManifest.xml")    cat = "manifest"
    else                                       cat = "other"

    if (cat == "dex") { dexCount++; dexComp += comp; dexRaw += raw }
    compSize[cat] += comp
    rawSize[cat] += raw
    entries[cat]++
    totalComp += comp
    found++
}

END {
    if (found == 0) { print "apk-size: unreadable archive: " apk > "/dev/stderr"; exit 1 }

    printf "APK      %s\n", apk
    printf "Size     %d bytes (%.2f MiB) on disk, %d bytes (%.2f MiB) in entries\n", \
        fileBytes, fileBytes / 1048576, totalComp, totalComp / 1048576
    printf "DEX      %d file(s), %d bytes compressed (%d raw)\n", dexCount, dexComp, dexRaw
    printf "\n%-15s %12s %12s %7s  %s\n", "CATEGORY", "COMPRESSED", "RAW", "SHARE", "ENTRIES"
    for (c in compSize)
        printf "%-15s %12d %12d %6.1f%%  %d\n", c, compSize[c], rawSize[c], 100 * compSize[c] / totalComp, entries[c]
}
'

echo
echo "Five largest entries (uncompressed bytes):"
unzip -v "$APK" \
    | awk 'NF >= 8 && $1 ~ /^[0-9]+$/ { printf "%12d  %s\n", $1, $8 }' \
    | sort -rn \
    | sed -n '1,5p'
