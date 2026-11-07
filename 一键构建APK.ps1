$ErrorActionPreference = "Stop"
$Project = Split-Path -Parent $MyInvocation.MyCommand.Path
$Tools = Join-Path $Project ".build-tools"
New-Item -ItemType Directory -Force -Path $Tools | Out-Null

function Say($s) { Write-Host "[K90] $s" -ForegroundColor Cyan }

$javaOk = $false
try {
    $v = (& java -version 2>&1 | Select-Object -First 1)
    if ($v) { $javaOk = $true; Say "Java: $v" }
} catch {}

if (-not $javaOk) {
    $studioJbr = "${env:ProgramFiles}\Android\Android Studio\jbr"
    if (Test-Path (Join-Path $studioJbr "bin\java.exe")) {
        $env:JAVA_HOME = $studioJbr
        $env:Path = "$studioJbr\bin;" + $env:Path
        Say "使用 Android Studio 自带 JDK: $studioJbr"
    } else {
        throw "未找到 Java。请先安装 Android Studio（推荐），然后重新运行。"
    }
}

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
New-Item -ItemType Directory -Force -Path $sdk | Out-Null

$sdkmanager = Join-Path $sdk "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkmanager)) {
    Say "下载 Android 官方命令行工具（约 156MB）..."
    $zip = Join-Path $Tools "commandlinetools-win.zip"
    $url = "https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip"
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    $tmp = Join-Path $Tools "cmdline-unzip"
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $latest = Join-Path $sdk "cmdline-tools\latest"
    Remove-Item -Recurse -Force $latest -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Split-Path $latest -Parent) | Out-Null
    Move-Item (Join-Path $tmp "cmdline-tools") $latest
}

Say "安装 Android SDK 36 / Build Tools 36.0.0..."
1..20 | ForEach-Object { "y" } | & $sdkmanager --licenses | Out-Host
& $sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools" | Out-Host

$gradleHome = Join-Path $Tools "gradle-8.13"
$gradleBat = Join-Path $gradleHome "bin\gradle.bat"
if (-not (Test-Path $gradleBat)) {
    Say "下载 Gradle 8.13（约 131MB）..."
    $gzip = Join-Path $Tools "gradle-8.13-bin.zip"
    Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-8.13-bin.zip" -OutFile $gzip -UseBasicParsing
    Expand-Archive -Path $gzip -DestinationPath $Tools -Force
}

Say "开始编译 Debug APK..."
Push-Location $Project
try {
    & $gradleBat --no-daemon assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败，退出码 $LASTEXITCODE" }
} finally {
    Pop-Location
}

$srcApk = Join-Path $Project "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $srcApk)) { throw "构建结束但未找到 APK：$srcApk" }
$outApk = Join-Path $Project "K90性能悬浮监控-debug.apk"
Copy-Item $srcApk $outApk -Force
Say "APK 已生成：$outApk"
Start-Process explorer.exe "/select,`"$outApk`""
