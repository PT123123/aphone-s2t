@echo off
REM Android语音转写应用构建脚本 (Windows版本)
REM 使用方法: build.bat [debug|release|clean]

setlocal enabledelayedexpansion

set BUILD_TYPE=%1
if "%BUILD_TYPE%"=="" set BUILD_TYPE=debug

echo ================================================
echo   Android语音转写应用构建脚本
echo   构建类型: %BUILD_TYPE%
echo ================================================
echo.

REM 检查Java环境
echo [检查Java环境...]
where java >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [错误] 未找到Java。请安装Java 17或更高版本。
    exit /b 1
)

for /f "tokens=3" %%i in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VERSION=%%i
    set JAVA_VERSION=!JAVA_VERSION:"=!
)
echo Java版本: !JAVA_VERSION!

REM 检查Android SDK
echo [检查Android SDK...]
if not exist "local.properties" (
    echo [错误] 未找到local.properties文件
    exit /b 1
)

findstr "sdk.dir=" local.properties > temp_sdk.txt
set /p SDK_LINE=<temp_sdk.txt
set SDK_PATH=%SDK_LINE:~7%
del temp_sdk.txt

if not exist "%SDK_PATH%" (
    echo [错误] Android SDK路径不正确: %SDK_PATH%
    exit /b 1
)
echo Android SDK路径: %SDK_PATH%

REM 清理构建（如果指定）
if "%BUILD_TYPE%"=="clean" (
    echo [清理构建...]
    gradlew.bat clean
    echo [清理完成]
    exit /b 0
)

REM 构建项目
echo [开始构建...]
if "%BUILD_TYPE%"=="release" (
    gradlew.bat assembleRelease
    echo [Release构建完成]
    echo APK位置: app\build\outputs\apk\release\app-release.apk
) else (
    gradlew.bat assembleDebug
    echo [Debug构建完成]
    echo APK位置: app\build\outputs\apk\debug\app-debug.apk
)

REM 安装到设备（可选）
echo.
set /p INSTALL_CHOICE=是否安装到连接的设备？(y/n): 
if /i "%INSTALL_CHOICE%"=="y" (
    echo [安装APK到设备...]
    if "%BUILD_TYPE%"=="release" (
        adb install -r app\build\outputs\apk\release\app-release.apk
    ) else (
        adb install -r app\build\outputs\apk\debug\app-debug.apk
    )
    echo [安装完成]
)

echo.
echo ================================================
echo   构建完成
echo ================================================

endlocal