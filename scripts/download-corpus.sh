#!/usr/bin/env bash
# Downloads the official Journal texts (English and German) of the five acts
# from the Publications Office of the EU, the repository behind EUR-Lex.
# Each file is the XHTML rendition of the act as published in the Official Journal.
set -euo pipefail

cd "$(dirname "$0")/../corpus"

# key | CELEX number | short title
ACTS=(
  "idd|32016L0097|Insurance Distribution Directive (EU) 2016/97"
  "solvency2|32009L0138|Solvency II Directive 2009/138/EC"
  "gdpr|32016R0679|General Data Protection Regulation (EU) 2016/679"
  "dora|32022R2554|Digital Operational Resilience Act, Regulation (EU) 2022/2554"
  "ai-act|32024R1689|Artificial Intelligence Act, Regulation (EU) 2024/1689"
)
LANGS=("en|eng|EN" "de|deu|DE")
TODAY=$(date -u +%Y-%m-%d)

entries=()
for act in "${ACTS[@]}"; do
  IFS='|' read -r key celex title <<<"$act"
  for lang in "${LANGS[@]}"; do
    IFS='|' read -r iso2 iso3 upper <<<"$lang"
    out="${key}.${iso2}.html"
    # The resource URL answers with a redirect to the language specific rendition.
    location=$(curl -fsS -o /dev/null -D - --max-time 60 \
      -H "Accept: application/xhtml+xml" -H "Accept-Language: ${iso3}" \
      "https://publications.europa.eu/resource/celex/${celex}" \
      | tr -d '\r' | awk 'tolower($1)=="location:" {print $2}' | sed 's#^http://#https://#')
    if [ -z "$location" ]; then
      echo "No ${upper} rendition found for ${celex}" >&2
      exit 1
    fi
    curl -fsS --max-time 300 -H "Accept: application/xhtml+xml" -o "$out" "$location"
    sha=$(shasum -a 256 "$out" | awk '{print $1}')
    bytes=$(wc -c <"$out" | tr -d ' ')
    echo "${out}  ${bytes} bytes"
    entries+=("    {\"key\": \"${key}\", \"celex\": \"${celex}\", \"title\": \"${title}\", \"lang\": \"${iso2}\", \"file\": \"${out}\", \"eurlex_url\": \"https://eur-lex.europa.eu/legal-content/${upper}/TXT/?uri=CELEX:${celex}\", \"download_url\": \"${location}\", \"retrieved\": \"${TODAY}\", \"sha256\": \"${sha}\", \"bytes\": ${bytes}}")
  done
done

{
  echo "["
  last=$((${#entries[@]} - 1))
  for i in "${!entries[@]}"; do
    if [ "$i" -lt "$last" ]; then echo "${entries[$i]},"; else echo "${entries[$i]}"; fi
  done
  echo "]"
} >manifest.json
echo "Wrote corpus/manifest.json"
