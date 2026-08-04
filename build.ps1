# Обёртка для сборки: подставляет локальный тулчейн (JDK 17 + Android SDK + Gradle).
# Использование:  .\build.ps1 test
#                 .\build.ps1 assembleDebug
param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $GradleArgs)

$env:JAVA_HOME = 'G:\toolchain\jdk\jdk-17.0.19+10'
$env:ANDROID_HOME = 'G:\toolchain\android-sdk'
$env:ANDROID_SDK_ROOT = 'G:\toolchain\android-sdk'
$env:GRADLE_USER_HOME = 'G:\toolchain\gradle-home'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

if (-not $GradleArgs) { $GradleArgs = @('tasks') }

& 'G:\toolchain\gradle\gradle-8.11.1\bin\gradle.bat' -p 'G:\projects\fitness-app' @GradleArgs
exit $LASTEXITCODE
