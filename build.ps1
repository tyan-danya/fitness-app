# Обёртка для сборки: подставляет локальный тулчейн (JDK 17 + Android SDK + Gradle).
# Использование:  .\build.ps1 test
#                 .\build.ps1 assembleDebug
param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $GradleArgs)

if (-not $env:JAVA_HOME -and (Test-Path 'G:\toolchain\jdk\jdk-17.0.19+10')) {
    $env:JAVA_HOME = 'G:\toolchain\jdk\jdk-17.0.19+10'
}
if (-not $env:ANDROID_HOME -and (Test-Path 'G:\toolchain\android-sdk')) {
    $env:ANDROID_HOME = 'G:\toolchain\android-sdk'
}
if ($env:ANDROID_HOME) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }
if (-not $env:GRADLE_USER_HOME -and (Test-Path 'G:\toolchain\gradle-home')) {
    $env:GRADLE_USER_HOME = 'G:\toolchain\gradle-home'
}
if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME\bin;$env:Path" }

if (-not $GradleArgs) { $GradleArgs = @('tasks') }

if (($GradleArgs -join ' ') -match 'assembleRelease|bundleRelease' -and
    -not (Test-Path (Join-Path $PSScriptRoot 'signing.properties')) -and -not $env:FITDIARY_KEYSTORE) {
    throw 'Настройте локальную подпись: signing.properties.example → signing.properties либо FITDIARY_KEYSTORE и остальные FITDIARY_* переменные.'
}
& (Join-Path $PSScriptRoot 'gradlew.bat') -p $PSScriptRoot @GradleArgs
exit $LASTEXITCODE
