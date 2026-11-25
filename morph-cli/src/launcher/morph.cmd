@echo off
rem Starts Morph with a bundled runtime when there is one, otherwise with JAVA_HOME or the java on the PATH (21+).
setlocal
set "MORPH_HOME=%~dp0.."
set "MORPH_JAVA=java"
if defined JAVA_HOME set "MORPH_JAVA=%JAVA_HOME%\bin\java.exe"
if exist "%MORPH_HOME%\runtime\bin\java.exe" set "MORPH_JAVA=%MORPH_HOME%\runtime\bin\java.exe"
"%MORPH_JAVA%" --enable-native-access=ALL-UNNAMED -cp "%MORPH_HOME%\lib\*" io.github.henryklayvin.morph.cli.Main %*
exit /b %ERRORLEVEL%
