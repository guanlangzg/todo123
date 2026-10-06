@echo off
REM ---------------------------------------------------------------------------------------------
REM Builds the Android .so for both declared ABIs and then the debug APK.
REM
REM *** CURRENTLY BLOCKED ON THIS MACHINE ***
REM The Android NDK is not installed and cannot be installed without accepting the Android SDK
REM licence, which is a user action (see the delivery report). Install it once with:
REM
REM     sdkmanager "ndk;27.2.12479018"
REM
REM after the SDK licence has been accepted. Then this script completes the pipeline.
REM
REM The ABIs and the NDK version are pinned to match app/build.gradle.kts.
REM ---------------------------------------------------------------------------------------------
setlocal
set "ROOT=%~dp0..\.."
pushd "%ROOT%" || exit /b 1

if not defined ANDROID_HOME set "ANDROID_HOME=E:\Android\Sdk"
set "NDK_VERSION=27.2.12479018"
set "NDK=%ANDROID_HOME%\ndk\%NDK_VERSION%"
set "TOOLCHAIN=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\bin"
set "API=26"

if not exist "%TOOLCHAIN%" (
  echo FAILED: NDK %NDK_VERSION% not found at %NDK%
  echo Install it with:  sdkmanager "ndk;%NDK_VERSION%"
  echo This requires the Android SDK licence to have been accepted by the user.
  popd
  exit /b 1
)

set "JNI=%ROOT%\app\build\generated\jniLibs"
set "CARGO_TARGET_DIR=%ROOT%\app\build\rust-android"
if not exist "%JNI%\arm64-v8a" mkdir "%JNI%\arm64-v8a"
if not exist "%JNI%\x86_64" mkdir "%JNI%\x86_64"

pushd rust

echo === arm64-v8a (aarch64-linux-android) ===
set "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=%TOOLCHAIN%\aarch64-linux-android%API%-clang.cmd"
cargo build --release --lib --target aarch64-linux-android
if errorlevel 1 goto :fail
copy /y "%CARGO_TARGET_DIR%\aarch64-linux-android\release\libarttodo_core.so" "%JNI%\arm64-v8a\libarttodo_core.so" >nul
if errorlevel 1 goto :fail

echo === x86_64 (x86_64-linux-android) ===
set "CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER=%TOOLCHAIN%\x86_64-linux-android%API%-clang.cmd"
cargo build --release --lib --target x86_64-linux-android
if errorlevel 1 goto :fail
copy /y "%CARGO_TARGET_DIR%\x86_64-linux-android\release\libarttodo_core.so" "%JNI%\x86_64\libarttodo_core.so" >nul
if errorlevel 1 goto :fail

popd

echo === assembleDebug ===
call "%ROOT%\gradlew.bat" --no-daemon assembleDebug
if errorlevel 1 goto :fail

echo.
echo Produced: app\build\outputs\apk\debug\app-debug.apk
popd
exit /b 0

:fail
echo.
echo NATIVE BUILD FAILED (exit code %errorlevel%)
popd
exit /b 1
