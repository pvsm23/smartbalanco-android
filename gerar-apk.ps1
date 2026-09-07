# Gera o APK de depuração.
#
# Duas armadilhas desta máquina, que este script existe para contornar:
#
# 1. O projeto mora em "Área de Trabalho". O gradlew.bat monta o caminho do
#    gradle-wrapper.jar com o acento corrompido e falha com
#    "Unable to access jarfile ...\Ürea de Trabalho\...". Por isso ele é
#    chamado pelo caminho CURTO 8.3, que não tem acento nem espaço.
#
# 2. O "java" do PATH é um Oracle Java 8, velho demais para o Gradle 8.14.
#    JAVA_HOME precisa apontar para o JBR do Android Studio.

$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
  throw "Não achei o JBR em $env:JAVA_HOME. O Android Studio mudou de lugar?"
}

$raiz = Split-Path -Parent $MyInvocation.MyCommand.Path
$fso  = New-Object -ComObject Scripting.FileSystemObject
$dir  = $fso.GetFolder("$raiz\android").ShortPath

Write-Host "Sincronizando o web para o Android..." -ForegroundColor Cyan
Push-Location $raiz
npx cap sync android
Pop-Location

Write-Host "Compilando (a primeira vez demora)..." -ForegroundColor Cyan
& "$dir\gradlew.bat" -p $dir assembleDebug

$apk = "$raiz\android\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
  $tamanho = [math]::Round((Get-Item $apk).Length / 1MB, 1)
  Write-Host ""
  Write-Host "Pronto: $apk  ($tamanho MB)" -ForegroundColor Green
} else {
  throw "O Gradle terminou mas o APK não apareceu em $apk"
}
