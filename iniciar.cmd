@echo off
rem Sobe a API e o site, espera os dois e abre no Firefox (ou no navegador padrao).
setlocal
cd /d "%~dp0"
set "MAVEN_OPTS=-Xmx160m"

if not exist "web\node_modules" (
  echo Instalando as bibliotecas do site...
  pushd web
  call npm.cmd ci || (popd & echo Falha ao instalar. & pause & exit /b 1)
  popd
)

curl.exe -s -o nul http://localhost:8080/actuator/health
if errorlevel 1 start "Lastro API" /min /d "%~dp0core-bank" cmd /k ".\mvnw.cmd -q spring-boot:test-run"

curl.exe -s -o nul http://localhost:5173/
if errorlevel 1 start "Lastro site" /min /d "%~dp0web" cmd /k "npm.cmd run build && npm.cmd run preview"

echo Subindo o Lastro. A primeira vez pode levar uns 2 minutos...
set /a tentativas=0
:espera
set /a tentativas+=1
if %tentativas% gtr 120 (
  echo Nao subiu. Veja as janelas "Lastro API" e "Lastro site".
  pause
  exit /b 1
)
ping -n 3 127.0.0.1 >nul
curl.exe -s -f -o nul http://localhost:8080/actuator/health || goto espera
curl.exe -s -f -o nul http://localhost:5173/ || goto espera

if exist "%ProgramFiles%\Mozilla Firefox\firefox.exe" (
  start "" "%ProgramFiles%\Mozilla Firefox\firefox.exe" http://localhost:5173/
) else (
  start "" http://localhost:5173/
)
echo Pronto: http://localhost:5173
echo Para desligar, feche as janelas "Lastro API" e "Lastro site".
