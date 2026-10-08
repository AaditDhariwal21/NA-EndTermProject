#!/bin/sh
set -e
cd "$(dirname "$0")"
./build.sh
javac -d out -cp out test/bhtp/*.java
java -cp out bhtp.ProtocolTest
