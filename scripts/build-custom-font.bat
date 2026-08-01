@echo off
setlocal EnableExtensions DisableDelayedExpansion

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
set "CONVERTER=%REPO_ROOT%\lib\EpdFont\scripts\fontconvert_sdcard.py"
set "REQUIREMENTS=%REPO_ROOT%\lib\EpdFont\scripts\requirements.txt"
set "ENV_DIR=%REPO_ROOT%\.conda-fontconvert"
set "INTERACTIVE=0"
if "%~1"=="" set "INTERACTIVE=1"

set "PYTHONUTF8=1"
set "PYTHONIOENCODING=utf-8"

call :ensure_python
if errorlevel 1 goto :failed

if "%INTERACTIVE%"=="1" goto :interactive

"%PYTHON%" "%CONVERTER%" %*
if errorlevel 1 goto :failed
exit /b 0

:interactive
echo.
echo CrossPoint custom font builder
echo Drag the folder containing one font family into this window.
echo.

set "FONT_DIR="
set /p "FONT_DIR=Font family folder: "
set "FONT_DIR=%FONT_DIR:"=%"
if not exist "%FONT_DIR%\" (
  echo ERROR: Font folder not found: %FONT_DIR%
  goto :failed
)

set "REGULAR="
for %%F in (
  "%FONT_DIR%\*-Regular.ttf"
  "%FONT_DIR%\*-Regular.otf"
  "%FONT_DIR%\*_Regular.ttf"
  "%FONT_DIR%\*_Regular.otf"
  "%FONT_DIR%\* Regular.ttf"
  "%FONT_DIR%\* Regular.otf"
) do if not defined REGULAR if exist "%%~fF" set "REGULAR=%%~fF"
if not defined REGULAR (
  echo ERROR: No Family-Regular.ttf or Family-Regular.otf file was found.
  goto :failed
)

for %%F in ("%REGULAR%") do set "DETECTED_NAME=%%~nF"
set "DETECTED_NAME=%DETECTED_NAME:~0,-8%"

set "BOLD="
for %%F in (
  "%FONT_DIR%\%DETECTED_NAME%-Bold.ttf"
  "%FONT_DIR%\%DETECTED_NAME%-Bold.otf"
  "%FONT_DIR%\%DETECTED_NAME%_Bold.ttf"
  "%FONT_DIR%\%DETECTED_NAME%_Bold.otf"
  "%FONT_DIR%\%DETECTED_NAME% Bold.ttf"
  "%FONT_DIR%\%DETECTED_NAME% Bold.otf"
) do if not defined BOLD if exist "%%~fF" set "BOLD=%%~fF"

set "ITALIC="
for %%F in (
  "%FONT_DIR%\%DETECTED_NAME%-Italic.ttf"
  "%FONT_DIR%\%DETECTED_NAME%-Italic.otf"
  "%FONT_DIR%\%DETECTED_NAME%_Italic.ttf"
  "%FONT_DIR%\%DETECTED_NAME%_Italic.otf"
  "%FONT_DIR%\%DETECTED_NAME% Italic.ttf"
  "%FONT_DIR%\%DETECTED_NAME% Italic.otf"
  "%FONT_DIR%\%DETECTED_NAME%-Oblique.ttf"
  "%FONT_DIR%\%DETECTED_NAME%-Oblique.otf"
) do if not defined ITALIC if exist "%%~fF" set "ITALIC=%%~fF"

set "BOLDITALIC="
for %%F in (
  "%FONT_DIR%\%DETECTED_NAME%-BoldItalic.ttf"
  "%FONT_DIR%\%DETECTED_NAME%-BoldItalic.otf"
  "%FONT_DIR%\%DETECTED_NAME%-Bold-Italic.ttf"
  "%FONT_DIR%\%DETECTED_NAME%-Bold-Italic.otf"
  "%FONT_DIR%\%DETECTED_NAME%_BoldItalic.ttf"
  "%FONT_DIR%\%DETECTED_NAME%_BoldItalic.otf"
  "%FONT_DIR%\%DETECTED_NAME% Bold Italic.ttf"
  "%FONT_DIR%\%DETECTED_NAME% Bold Italic.otf"
  "%FONT_DIR%\%DETECTED_NAME%-BoldOblique.ttf"
  "%FONT_DIR%\%DETECTED_NAME%-BoldOblique.otf"
) do if not defined BOLDITALIC if exist "%%~fF" set "BOLDITALIC=%%~fF"

echo.
echo Detected family: %DETECTED_NAME%
echo   Regular:     %REGULAR%
if defined BOLD (echo   Bold:        %BOLD%) else (echo   Bold:        not found)
if defined ITALIC (echo   Italic:      %ITALIC%) else (echo   Italic:      not found)
if defined BOLDITALIC (echo   Bold Italic: %BOLDITALIC%) else (echo   Bold Italic: not found)

set "FONT_NAME="
set /p "FONT_NAME=Output family name [%DETECTED_NAME%]: "
if not defined FONT_NAME set "FONT_NAME=%DETECTED_NAME%"

set "SIZES="
set /p "SIZES=Point sizes [12,14,16,18]: "
if not defined SIZES set "SIZES=12,14,16,18"

set "INTERVALS="
set "DEFAULT_INTERVALS=reading,builtin,latin-ext,vietnamese,symbols"
set /p "INTERVALS=Unicode coverage presets [%DEFAULT_INTERVALS%]: "
if not defined INTERVALS set "INTERVALS=%DEFAULT_INTERVALS%"

set "OUTPUT_DIR="
set /p "OUTPUT_DIR=Output folder [%USERPROFILE%\Downloads\CrossPointFonts\%FONT_NAME%]: "
if not defined OUTPUT_DIR set "OUTPUT_DIR=%USERPROFILE%\Downloads\CrossPointFonts\%FONT_NAME%"
set "OUTPUT_DIR=%OUTPUT_DIR:"=%"

set "AUTOHINT="
set /p "AUTOHINT=Force autohinting for consistent small stems? [Y/n]: "
set "AUTOHINT_ARG=--force-autohint"
if /I "%AUTOHINT%"=="N" set "AUTOHINT_ARG="

set "NOTO_DIR=%REPO_ROOT%\lib\EpdFont\builtinFonts\source\NotoSans"
set "BOLD_ARGS="
set "ITALIC_ARGS="
set "BOLDITALIC_ARGS="
if defined BOLD set BOLD_ARGS=--bold "%BOLD%" --fallback-bold "%NOTO_DIR%\NotoSans-Bold.ttf"
if defined ITALIC set ITALIC_ARGS=--italic "%ITALIC%" --fallback-italic "%NOTO_DIR%\NotoSans-Italic.ttf"
if defined BOLDITALIC set BOLDITALIC_ARGS=--bolditalic "%BOLDITALIC%" --fallback-bolditalic "%NOTO_DIR%\NotoSans-BoldItalic.ttf"

echo.
echo Building %FONT_NAME% at %SIZES% pt...
"%PYTHON%" "%CONVERTER%" ^
  --regular "%REGULAR%" ^
  --fallback-regular "%NOTO_DIR%\NotoSans-Regular.ttf" ^
  %BOLD_ARGS% ^
  %ITALIC_ARGS% ^
  %BOLDITALIC_ARGS% ^
  --intervals "%INTERVALS%" ^
  --sizes "%SIZES%" ^
  --name "%FONT_NAME%" ^
  %AUTOHINT_ARG% ^
  --output-dir "%OUTPUT_DIR%"
if errorlevel 1 goto :failed

echo.
echo Build complete: %OUTPUT_DIR%
if not defined CPFONT_NO_OPEN explorer "%OUTPUT_DIR%"
if not defined CPFONT_NO_PAUSE pause
exit /b 0

:ensure_python
if defined CPFONT_PYTHON (
  set "PYTHON=%CPFONT_PYTHON%"
  if not exist "%CPFONT_PYTHON%" (
    echo ERROR: CPFONT_PYTHON does not exist: %CPFONT_PYTHON%
    exit /b 1
  )
) else (
  set "PYTHON=%ENV_DIR%\python.exe"
)

if exist "%PYTHON%" goto :check_dependencies

set "CONDA_EXE="
if exist "%ProgramData%\miniconda3\Scripts\conda.exe" set "CONDA_EXE=%ProgramData%\miniconda3\Scripts\conda.exe"
if not defined CONDA_EXE for /f "delims=" %%I in ('where conda.exe 2^>nul') do if not defined CONDA_EXE set "CONDA_EXE=%%I"
if not defined CONDA_EXE (
  echo ERROR: Conda was not found. Install Miniconda or set CPFONT_PYTHON to a compatible Python executable.
  exit /b 1
)

echo Creating local font-conversion environment...
"%CONDA_EXE%" create -p "%ENV_DIR%" python=3.11 pip -y
if errorlevel 1 exit /b 1

:check_dependencies
"%PYTHON%" -c "import fontTools, freetype, yaml" >nul 2>&1
if errorlevel 1 (
  echo Installing font-conversion dependencies...
  "%PYTHON%" -m pip install -r "%REQUIREMENTS%"
  if errorlevel 1 exit /b 1
)
exit /b 0

:failed
echo.
echo Font build failed.
if "%INTERACTIVE%"=="1" if not defined CPFONT_NO_PAUSE pause
exit /b 1
