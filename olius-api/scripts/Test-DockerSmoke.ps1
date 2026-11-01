#requires -Version 7.0
param([string]$PostgresImage = 'postgres:16.15')
$ErrorActionPreference = 'Stop'
$apiDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$network = "olius-smoke-$suffix"
$database = "olius-smoke-db-$suffix"
$application = "olius-smoke-api-$suffix"
$image = "olius-core:smoke-$suffix"
$password = [Guid]::NewGuid().ToString('N')
$networkCreated = $false
$imageCreated = $false
$databaseCreated = $false
$applicationCreated = $false

function Invoke-Docker {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker falhou: $($Arguments[0])" }
}

try {
    Push-Location $apiDirectory
    Invoke-Docker -Arguments @('build', '--platform', 'linux/amd64', '-t', $image, '.')
    $imageCreated = $true
    Invoke-Docker -Arguments @('network', 'create', $network)
    $networkCreated = $true
    Invoke-Docker -Arguments @('run', '-d', '--name', $database, '--network', $network,
        '-e', 'POSTGRES_DB=olius_smoke', '-e', 'POSTGRES_USER=smoke_admin',
        '-e', "POSTGRES_PASSWORD=$password", $PostgresImage)
    $databaseCreated = $true
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker exec $database pg_isready -U smoke_admin -d olius_smoke *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'PostgreSQL descartável não ficou pronto.' }
    Invoke-Docker -Arguments @('cp', 'src/test/resources/db/olius', "${database}:/tmp/olius")
    # Mesma ordem do fixture OfficialPostgres; não usa dados de bancos reais.
    foreach ($script in @('01_structure.sql', '02_check_constraints.sql', '03_indexes.sql',
        '04_functions_procedures.sql', '05_triggers.sql', '06_ctes.sql',
        '07_window_functions.sql', '08_materialized_views.sql',
        'data_catalog/01_structure.sql', 'data_catalog/02_check_constraints.sql',
        'data_catalog/03_view.sql', 'data_catalog/04_load.sql',
        'access_control/01_roles.sql', 'data_catalog/05_validate.sql')) {
        Invoke-Docker -Arguments @('exec', $database, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
            '-U', 'smoke_admin', '-d', 'olius_smoke', '-f', "/tmp/olius/$script")
    }
    $provision = "CREATE ROLE olius_smoke_api LOGIN PASSWORD '$password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; GRANT olius_api TO olius_smoke_api;"
    $provision | & docker exec -i $database psql -X -v ON_ERROR_STOP=1 -U smoke_admin -d olius_smoke
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao criar usuário limitado descartável.' }
    $role = & docker exec $database psql -X -At -U smoke_admin -d olius_smoke -c "SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND pg_has_role(rolname, 'olius_api', 'member') FROM pg_roles WHERE rolname='olius_smoke_api'"
    if ($LASTEXITCODE -ne 0 -or $role -ne 't') { throw 'Privilégios inesperados no usuário da API.' }
    Invoke-Docker -Arguments @('run', '-d', '--name', $application, '--network', $network,
        '-p', '127.0.0.1::8080', '-e', 'SPRING_PROFILES_ACTIVE=local',
        '-e', "CONTAINER_DEV_URL=jdbc:postgresql://${database}:5432/olius_smoke",
        '-e', 'CONTAINER_DEV_USER=olius_smoke_api', '-e', "CONTAINER_DEV_PASSWORD=$password", $image)
    $applicationCreated = $true
    $binding = (& docker port $application 8080/tcp).Trim()
    if ($LASTEXITCODE -ne 0 -or $binding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Porta local não encontrada.' }
    $url = "http://127.0.0.1:$($Matches[1])/api/v1/establishment-types"
    $response = $null
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $url -TimeoutSec 3
            if ($response.StatusCode -eq 200) { break }
        } catch { }
        if ((& docker inspect --format '{{.State.Running}}' $application) -ne 'true') {
            throw 'Container da API encerrou antes de ficar pronto.'
        }
        Start-Sleep -Seconds 2
    }
    if ($null -eq $response -or $response.StatusCode -ne 200) { throw 'Consulta pública não retornou HTTP 200.' }
    $logs = & docker logs $application 2>&1
    if (-not ($logs -match 'Started OliusApiApplication')) { throw 'Inicialização Spring não confirmada nos logs.' }
    Write-Output "SMOKE OK: $PostgresImage; esquema oficial; usuário limitado; Spring iniciado; GET /api/v1/establishment-types HTTP 200."
} finally {
    # Remove somente os recursos exclusivos desta execução; nenhum banco externo.
    if ($applicationCreated) { & docker rm -f $application | Out-Null }
    if ($databaseCreated) { & docker rm -f -v $database | Out-Null }
    if ($networkCreated) { & docker network rm $network | Out-Null }
    if ($imageCreated) { & docker image rm $image | Out-Null }
    Pop-Location
}
