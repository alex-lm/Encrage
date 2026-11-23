<#
.SYNOPSIS
    Control the Encrage backend (API + admin console containers) on the NAS over SSH.

.EXAMPLE
    .\deploy\encrage-nas.ps1 status
    .\deploy\encrage-nas.ps1 stop                     # stop API and console
    .\deploy\encrage-nas.ps1 stop -Service console    # turn only the console off
    .\deploy\encrage-nas.ps1 start -Service console
    .\deploy\encrage-nas.ps1 logs -Service api
    .\deploy\encrage-nas.ps1 deploy                   # push current Backend code, rebuild, restart
    .\deploy\encrage-nas.ps1 console-admin -Name root # create/reset a console admin (prompts on the NAS)

.NOTES
    Where the backend lives is configurable. Copy deploy\nas.example.env to deploy\nas.local.env
    (gitignored) and fill it in, or set ENCRAGE_NAS_HOST, ENCRAGE_NAS_USER, ENCRAGE_NAS_KEY and
    ENCRAGE_NAS_DIR in your environment, or pass -HostName/-User/-KeyPath/-RemoteDir.
    Ports and the data directory are set in the NAS's <RemoteDir>/.env (ENCRAGE_API_PORT,
    ENCRAGE_CONSOLE_PORT, ENCRAGE_DATA_DIR); run "deploy" or "start" after changing them.
    "deploy" only replaces code files; it never touches .env or data/.
    "stop" keeps containers stopped across NAS reboots until "start".
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet("start", "stop", "restart", "status", "logs", "deploy", "console-admin")]
    [string]$Command = "status",

    [ValidateSet("all", "api", "console")]
    [string]$Service = "all",

    [string]$Name,

    [string]$HostName,
    [string]$User,
    [string]$KeyPath,
    [string]$RemoteDir
)

$ErrorActionPreference = "Stop"

# Connection settings: parameter > environment variable > deploy\nas.local.env (gitignored) > default.
$localConfig = @{}
$localFile = Join-Path $PSScriptRoot "nas.local.env"
if (Test-Path $localFile) {
    foreach ($line in Get-Content $localFile) {
        if ($line -match '^\s*(ENCRAGE_NAS_[A-Z]+)\s*=\s*(.*?)\s*$') { $localConfig[$Matches[1]] = $Matches[2] }
    }
}
function Get-Setting([string]$Value, [string]$Key, [string]$Default) {
    if ($Value) { return $Value }
    $fromEnv = [Environment]::GetEnvironmentVariable($Key)
    if ($fromEnv) { return $fromEnv }
    if ($localConfig[$Key]) { return $localConfig[$Key] }
    return $Default
}
$HostName = Get-Setting $HostName "ENCRAGE_NAS_HOST" $null
$User = Get-Setting $User "ENCRAGE_NAS_USER" $null
$KeyPath = Get-Setting $KeyPath "ENCRAGE_NAS_KEY" (Join-Path $env:USERPROFILE ".ssh\id_ed25519")
$RemoteDir = Get-Setting $RemoteDir "ENCRAGE_NAS_DIR" "encrage"
if ($KeyPath.StartsWith("~")) { $KeyPath = Join-Path $env:USERPROFILE $KeyPath.Substring(1).TrimStart("\", "/") }
if (-not $HostName -or -not $User) {
    throw "Set the NAS host and user: copy deploy\nas.example.env to deploy\nas.local.env, set ENCRAGE_NAS_* variables, or pass -HostName and -User."
}
$target = "$User@$HostName"
$sshArgs = @("-i", $KeyPath, "-o", "IdentitiesOnly=yes", "-o", "ConnectTimeout=10")
$services = @{ all = ""; api = "encrage-backend"; console = "encrage-console" }[$Service]

function Invoke-Remote([string]$Script, [switch]$Tty) {
    $extra = @("-o", "BatchMode=yes")
    if ($Tty) { $extra = @("-t") }
    # Windows PowerShell 5.1 strips embedded double quotes from native-command arguments, so ship
    # the script base64-encoded and run it from a temp file (keeps the TTY for interactive prompts).
    $full = ("cd ~/$RemoteDir || exit 1`n" + $Script).Replace("`r", "")
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($full))
    & ssh.exe @sshArgs @extra $target "f=`$(mktemp); echo $encoded | base64 -d > `$f; bash `$f; r=`$?; rm -f `$f; exit `$r"
    if ($LASTEXITCODE -ne 0) { throw "Remote command failed (exit $LASTEXITCODE)" }
}

# Prints each container's state and health, then the URLs built from the NAS's .env.
$statusScript = @'
docker compose ps -a
echo
for c in encrage-backend encrage-console; do
  printf '%-17s %s\n' "$c" "$(docker inspect --format '{{.State.Status}} / {{if .State.Health}}{{.State.Health.Status}}{{else}}no healthcheck{{end}}' $c 2>/dev/null || echo 'not created')"
done
api_port=$(sed -n 's/^ENCRAGE_API_PORT=//p' .env | tail -1); console_port=$(sed -n 's/^ENCRAGE_CONSOLE_PORT=//p' .env | tail -1)
echo
echo "API:     http://HOST:${api_port:-8000}/"
echo "Console: http://HOST:${console_port:-8001}/"
'@.Replace("HOST", $HostName).Replace("`r", "")

switch ($Command) {
    "start"   { Invoke-Remote "docker compose up -d $services && docker compose ps" }
    "stop"    { Invoke-Remote "docker compose stop $services && docker compose ps -a" }
    "restart" { Invoke-Remote "docker compose restart $services && docker compose ps" }
    "status"  { Invoke-Remote $statusScript }
    "logs"    { Invoke-Remote "docker compose logs --tail 200 -f $services" -Tty }
    "console-admin" {
        if (-not $Name) { throw "Pass -Name <admin username>" }
        if ($Name -notmatch '^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$') { throw "Invalid admin username" }
        Invoke-Remote "docker compose exec encrage-console python manage.py set-console-admin $Name" -Tty
    }
    "deploy"  {
        $backend = Split-Path -Parent $PSScriptRoot
        $archive = Join-Path $env:TEMP "encrage-backend-deploy.tgz"
        if (Test-Path $archive) { Remove-Item $archive -Force -Confirm:$false }

        # ustar format: no Windows-specific pax headers (SCHILY.fflags) for GNU tar to warn about.
        & tar.exe --format ustar -czf $archive --exclude "__pycache__" -C $backend `
            Dockerfile .dockerignore docker-compose.yml requirements.txt manage.py app
        if ($LASTEXITCODE -ne 0) { throw "Packaging failed" }

        & ssh.exe @sshArgs -o BatchMode=yes $target "mkdir -p ~/$RemoteDir"
        # -O = legacy SCP protocol; the NAS's SFTP subsystem can't write into the home dir.
        & scp.exe -O -i $KeyPath -o IdentitiesOnly=yes -o BatchMode=yes $archive "${target}:$RemoteDir/.deploy.tgz"
        if ($LASTEXITCODE -ne 0) { throw "Upload failed" }
        Remove-Item $archive -Force -Confirm:$false

        # Windows tar can record folders as read-only (r-x), so restore owner write access
        # before deleting the old code and after unpacking the new code.
        Invoke-Remote ("(chmod -R u+w app 2>/dev/null || true) && rm -rf app && tar -xzf .deploy.tgz && rm .deploy.tgz" +
            " && chmod -R u+rwX,go+rX app Dockerfile .dockerignore docker-compose.yml requirements.txt manage.py" +
            " && docker compose up -d --build --remove-orphans && docker compose ps")
    }
}
