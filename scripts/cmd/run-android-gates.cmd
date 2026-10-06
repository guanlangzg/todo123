@echo off
REM ---------------------------------------------------------------------------------------------
REM Android gate suite: debug APK, JVM unit tests, lint. Reproduces
REM docs/设计/工程布局与版本锁定.md §7 from inside this workspace.
REM
REM Usage:  scripts\cmd\run-android-gates.cmd
REM
REM Environment (工程布局与版本锁定.md §7 prerequisites):
REM   JAVA_HOME        = E:\devtools\jdk-17
REM   ANDROID_HOME     = E:\Android\Sdk
REM   GRADLE_USER_HOME = E:\caches\gradle   (holds the mirror init script and the 8.13 wrapper)
REM
REM No local.properties is created on purpose: on this machine ANDROID_HOME is enough, and an
REM unescaped sdk.dir breaks lintDebug (PropertyEscape).
REM ---------------------------------------------------------------------------------------------
setlocal
set "ROOT=%~dp0..\.."
pushd "%ROOT%" || exit /b 1

if not defined JAVA_HOME set "JAVA_HOME=E:\devtools\jdk-17"
if not defined ANDROID_HOME set "ANDROID_HOME=E:\Android\Sdk"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=E:\caches\gradle"

echo === gradlew assembleDebug ===
call gradlew.bat --no-daemon assembleDebug
if errorlevel 1 goto :fail

if not exist "app\build\outputs\apk\debug\app-debug.apk" (
  echo FAILED: app\build\outputs\apk\debug\app-debug.apk was not produced.
  goto :fail
)
echo OK: app-debug.apk present

echo === gradlew testDebugUnitTest ===
call gradlew.bat --no-daemon testDebugUnitTest --rerun
if errorlevel 1 goto :fail

echo === gradlew lintDebug ===
call gradlew.bat --no-daemon lintDebug
if errorlevel 1 goto :fail

echo.
echo ALL ANDROID GATES PASSED
popd
exit /b 0

:fail
echo.
echo ANDROID GATES FAILED (exit code %errorlevel%)
popd
exit /b 1
