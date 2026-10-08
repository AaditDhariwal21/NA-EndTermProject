@echo off
rem compiles everything into .\out
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
javac -d out src\bhtp\*.java || exit /b 1
echo built -^> out\
