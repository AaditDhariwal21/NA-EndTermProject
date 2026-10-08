@echo off
cd /d "%~dp0"
call build.bat || exit /b 1
javac -d out -cp out test\bhtp\*.java || exit /b 1
java -cp out bhtp.ProtocolTest
