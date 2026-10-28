param([switch]$SelfTest,[switch]$Foreground,[ValidateSet('fabric','neoforge')][string]$Primary='fabric',[ValidateSet('DEBUG','INFO','WARN','ERROR')][string]$LogLevel='DEBUG')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
 $env:MMLF_LOG_LEVEL=$LogLevel
 if ($Foreground) {
  $launcherArgs="--primary=$Primary"
  if ($SelfTest) { $launcherArgs+=' --self-test' }
  & ./gradlew.bat -PcoreOnly :launcher:run "--args=$launcherArgs"
  if ($LASTEXITCODE -ne 0) { throw "MMLF exited with code $LASTEXITCODE" }
 } else {
  & ./gradlew.bat -PcoreOnly :launcher:installDist
  if ($LASTEXITCODE -ne 0) { throw 'Launcher build failed' }
  New-Item -ItemType Directory -Force runtime | Out-Null
  $javaPath=(Get-Command java.exe -ErrorAction Stop).Source
  $libraryPath=Join-Path $projectRoot 'launcher/build/install/launcher/lib/*'
  $arguments=@(('"-Dmmlf.root='+$projectRoot+'"'),('-Dmmlf.logLevel='+$LogLevel),'-cp',('"'+$libraryPath+'"'),'dev.mmlf.launcher.Launcher')
  $arguments+="--primary=$Primary"
  if ($SelfTest) { $arguments+='--self-test' }
  $process=Start-Process -FilePath $javaPath -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot 'runtime/launcher-output.log') -RedirectStandardError (Join-Path $projectRoot 'runtime/launcher-error.log') -PassThru
  @{pid=$process.Id;started=[DateTime]::UtcNow.ToString('o');level=$LogLevel} | ConvertTo-Json | Set-Content -Encoding utf8 runtime/launcher-process.json
  Write-Output "MMLF launcher started (PID $($process.Id), primary $Primary, level $LogLevel). Session path: runtime/latest-session.txt"
 }
} finally { Pop-Location }
