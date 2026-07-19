@echo off
REM ============================================================
REM  PMCL Windows 启动脚本（原生 cmd / PowerShell 双击即可运行）
REM  用法:
REM    pmcl.bat              启动 PMCL GUI
REM    pmcl.bat gui          启动 PMCL GUI
REM    pmcl.bat cli          进入 PMCL Shell 交互模式
REM    pmcl.bat cli versions 执行单条 CLI 命令
REM    pmcl.bat help         查看帮助
REM
REM  前置条件: 已安装 Java 21+
REM    验证: java -version
REM ============================================================

setlocal enabledelayedexpansion

REM ---------- 定位脚本所在目录 ----------
set "SCRIPT_DIR=%~dp0"
REM 去掉末尾的反斜杠
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

REM ---------- Java 是否存在 ----------
where java >nul 2>nul
if errorlevel 1 (
    echo [错误] 未检测到 java 命令
    echo 请先安装 Java 21+:
    echo   Windows ^(winget^):  winget install EclipseAdoptium.Temurin.21.JDK
    echo   Windows ^(choco^):   choco install temurin21 -y
    echo   macOS:              brew install openjdk@21
    echo   Linux:              sdk install java 21.0.5-tem
    exit /b 1
)

REM ---------- 解析 Java 主版本号 ----------
REM java -version 输出到 stderr，需用 2^>^&1 重定向
for /f "tokens=*" %%i in ('java -version 2^>^&1 ^| findstr /r "version"') do (
    set "JAVA_VER_LINE=%%i"
    goto :got_version
)
:got_version

REM 提取 "version "x.y.z"" 中的主版本号 x
set "JAVA_MAJOR="
for /f "tokens=2 delims=\"" %%a in ("%JAVA_VER_LINE%") do (
    for /f "tokens=1 delims=." %%b in ("%%a") do (
        set "JAVA_MAJOR=%%b"
    )
)

REM 处理 Java 8 的 "1.8.0_xxx" 格式
if "%JAVA_MAJOR%"=="1" (
    REM 从 "1.8.0_xxx" 提取 8
    for /f "tokens=2 delims=." %%a in ("%JAVA_VER_LINE%") do (
        for /f "tokens=1 delims=_" %%b in ("%%a") do (
            set "JAVA_MAJOR=%%b"
        )
    )
    REM 此时 JAVA_MAJOR 应为 8
)

if "%JAVA_MAJOR%"=="" (
    echo [错误] 无法解析 Java 版本，请检查 java -version 输出
    exit /b 1
)

REM 版本比较（需 set /a 转数字）
set /a "JV=%JAVA_MAJOR%" 2>nul
if "%JV%"=="" set "JV=0"
if %JV% LSS 21 (
    echo [错误] 当前 Java 版本为 %JAVA_MAJOR%，PMCL 需要 Java 21+
    echo 请升级后重试
    exit /b 1
)

REM ---------- 模式分发 ----------
set "MODE=%~1"
if "%MODE%"=="" set "MODE=gui"

if /i "%MODE%"=="gui"        goto :run_gui
if /i "%MODE%"=="cli"        goto :run_cli
if /i "%MODE%"=="-h"         goto :show_help
if /i "%MODE%"=="--help"     goto :show_help
if /i "%MODE%"=="help"       goto :show_help

echo [错误] 未知模式: %MODE%
echo 使用 pmcl.bat help 查看帮助
exit /b 1

:run_gui
set "JAR_PATH=%SCRIPT_DIR%\ui\build\libs\pmcl-1.0.0-all.jar"
if not exist "%JAR_PATH%" (
    echo [错误] 未找到 PMCL fat jar: %JAR_PATH%
    echo 请先运行: .\gradlew :ui:fatJar
    exit /b 1
)
echo 启动 PMCL GUI (Java %JAVA_MAJOR%)...
REM JVM 性能参数：G1GC + 初始/最大堆 + 字符串去重 + 软最大堆
java -Xms512m -Xmx2g -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+DisableExplicitGC -XX:MaxGCPauseMillis=200 -XX:SoftMaxHeapSize=1536m -jar "%JAR_PATH%"
exit /b %errorlevel%

:run_cli
shift
set "JAR_PATH=%SCRIPT_DIR%\cli\build\libs\cli.jar"
if not exist "%JAR_PATH%" (
    echo [错误] 未找到 PMCL CLI jar: %JAR_PATH%
    echo 请先运行: .\gradlew :cli:jar
    exit /b 1
)
REM 传递剩余参数给 CLI（shift 后 %1 即原 %2）
:cli_loop
if "%~1"=="" goto :cli_run
set "CLI_ARGS=%CLI_ARGS% %~1"
shift
goto :cli_loop
:cli_run
java -jar "%JAR_PATH%" %CLI_ARGS%
exit /b %errorlevel%

:show_help
echo PMCL Windows 启动脚本
echo.
echo 用法:
echo   pmcl.bat              启动 PMCL GUI（默认）
echo   pmcl.bat gui          启动 PMCL GUI
echo   pmcl.bat cli          进入 PMCL Shell 交互模式
echo   pmcl.bat cli ^<命令^>   执行单条 CLI 命令（如 pmcl.bat cli versions）
echo.
echo 前置条件:
echo   - 已安装 Java 21+（验证: java -version）
echo   - 已构建产物（.\gradlew :ui:fatJar 或 .\gradlew :cli:jar）
echo.
echo 一键安装 Java 21:
echo   winget install EclipseAdoptium.Temurin.21.JDK
exit /b 0
