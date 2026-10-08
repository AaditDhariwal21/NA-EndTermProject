#!/bin/sh
# compiles everything into ./out
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
javac -d out src/bhtp/*.java
echo "built -> out/"
