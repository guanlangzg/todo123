@echo off
REM ---------------------------------------------------------------------------------------------
REM Rust gate suite. Reproduces the four cargo gates plus the panic-strategy gate from
REM docs/设计/工程布局与版本锁定.md §7, from inside this workspace (the N11 remedy: no D:\tmp).
REM
REM Usage:  scripts\cmd\run-rust-gates.cmd
REM Exits non-zero on the first failure.
REM ---------------------------------------------------------------------------------------------
setlocal
set "ROOT=%~dp0..\.."
pushd "%ROOT%" || exit /b 1

echo === cargo test --test occurrences ===
cargo test --manifest-path rust/Cargo.toml --test occurrences
if errorlevel 1 goto :fail

echo === cargo test (whole crate) ===
cargo test --manifest-path rust/Cargo.toml
if errorlevel 1 goto :fail

echo === cargo fmt --check ===
cargo fmt --manifest-path rust/Cargo.toml --check
if errorlevel 1 goto :fail

echo === cargo clippy -D warnings ===
cargo clippy --manifest-path rust/Cargo.toml --all-targets -- -D warnings
if errorlevel 1 goto :fail

echo === panic-strategy gate (must print panic="unwind") ===
REM `--lib` is mandatory: this crate has both a lib and a bin target (uniffi-bindgen).
REM The regex uses `.` for the quote characters so cmd needs no escaping.
set "CFG=%TEMP%\arttodo-panic-cfg.txt"
cargo rustc --manifest-path rust/Cargo.toml --release --lib -- --print cfg > "%CFG%" 2>&1
if errorlevel 1 goto :fail
findstr /R /C:"panic=.unwind." "%CFG%" >nul
if errorlevel 1 goto :panicfail
echo OK: panic=unwind

echo === UniFFI binding generation ===
cargo build --manifest-path rust/Cargo.toml --release --lib
if errorlevel 1 goto :fail
pushd rust
cargo run --release --bin uniffi-bindgen -- generate --library target/release/arttodo_core.dll ^
  --language kotlin --no-format --out-dir ..\app\build\generated\uniffi
set "BINDGEN=%errorlevel%"
popd
if not "%BINDGEN%"=="0" goto :fail

echo.
echo ALL RUST GATES PASSED
popd
exit /b 0

:panicfail
REM This branch is fatal for the UniFFI panic containment (架构契约 §10). Print the cfg only
REM after the failure is established, so the diagnostic cannot overwrite the gate verdict.
echo FAILED: release profile does not unwind. UniFFI cannot contain a Rust panic without it.
type "%CFG%"
goto :fail

:fail
echo.
echo RUST GATES FAILED
popd
exit /b 1
