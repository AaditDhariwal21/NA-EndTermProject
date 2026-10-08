@echo off
java -cp "%~dp0out" bhtp.BCurl %*
exit /b %ERRORLEVEL%
