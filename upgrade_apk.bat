@echo off
setlocal
chcp 65001 >nul
if "%~1"=="" (
    echo ==============================================================
    echo   雷鸟 AI 自定义 LLM 一键升级补丁脚本
    echo   用法：将新版本官方 APK 直接拖拽到此脚本上，或命令行传入路径
    echo   示例：upgrade_apk.bat RayNeo_AI_1.0.21.apk
    echo ==============================================================
    pause
    exit /b 1
)

python "%~dp0custom_ai_mod\patch_apk.py" "%~1"
if errorlevel 1 (
    echo.
    echo [失败] 补丁构建出错，请查看上方日志。
    pause
    exit /b 1
)

echo.
echo [完成] 已在同目录生成 *_CustomAI.apk 与 custom_ai_mod\annotations\diffs\ 差异文件。
pause
