@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem  Fail-fast behavior: as soon as Gradle prints its result (BUILD
@rem  SUCCESSFUL / BUILD FAILED) and the wrapper JVM exits, this script
@rem  exits immediately with Gradle's exit code. Missing java, missing
@rem  wrapper jar, or a failed build all return a clear error and a
@rem  non-zero exit code right away instead of leaving a hung console.
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
@rem Force UTF-8 so Gradle/javac messages render correctly in UTF-8 consoles (e.g. PowerShell/opencode).
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m" "-Dfile.encoding=UTF-8" "-Dsun.jnu.encoding=UTF-8"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:execute
@rem Setup the command line

@rem Fail fast: don't spawn a JVM if the wrapper jar is missing.
if not exist "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" (
    echo.
    echo ERROR: Gradle wrapper jar not found at "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar"
    echo.
    echo Restore the wrapper files or re-run 'gradle wrapper' to fix this.
    goto fail
)

@rem Execute Gradle.
@rem  - stdout goes straight to the console so live progress keeps rendering.
@rem  - stderr is captured to a TEMP FILE, NOT merged into a pipe with 2>&1:
@rem    a merged pipe can stay open because the spawned Gradle daemon inherits
@rem    it, so callers keep waiting long after BUILD SUCCESSFUL/FAILED was
@rem    printed. A plain file handle cannot be held against us like that, so
@rem    the script returns the moment Gradle finishes.
@rem  - On failure the captured stderr is dumped below and we exit non-zero
@rem    immediately.
set "GRADLE_STDERR=%TEMP%\gradlew-stderr-%RANDOM%.log"
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %* 2>"%GRADLE_STDERR%"
set EXIT_CODE=%ERRORLEVEL%

if %EXIT_CODE% neq 0 (
    echo.
    echo ERROR: Gradle build failed with exit code %EXIT_CODE%.
    if exist "%GRADLE_STDERR%" type "%GRADLE_STDERR%" 2>NUL
)

@rem Clean up the temp capture and exit right away - no lingering work.
if exist "%GRADLE_STDERR%" del /q "%GRADLE_STDERR%" 2>NUL

if %EXIT_CODE% neq 0 goto fail

:mainEnd
@rem End local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" endlocal
@rem Success: exit immediately with code 0.
exit /b 0

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
if not defined EXIT_CODE set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if "%OS%"=="Windows_NT" endlocal
if not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:omega
