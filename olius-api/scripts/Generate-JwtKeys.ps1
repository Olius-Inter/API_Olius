param([string]$OutputDirectory = (Join-Path $PSScriptRoot '..\secrets'))
$ErrorActionPreference = 'Stop'
$destination = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$privatePath = Join-Path $destination 'jwt-private.pem'
$publicPath = Join-Path $destination 'jwt-public.pem'
if ((Test-Path -LiteralPath $privatePath) -or (Test-Path -LiteralPath $publicPath)) {
    throw 'Arquivos já existem. Rotação de chaves exige decisão explícita; nada foi sobrescrito.'
}
$rsa = [Security.Cryptography.RSA]::Create(2048)
try {
    $privateBytes = $rsa.ExportPkcs8PrivateKey()
    $publicBytes = $rsa.ExportSubjectPublicKeyInfo()
    $privatePem = "-----BEGIN PRIVATE KEY-----`n" + [Convert]::ToBase64String($privateBytes, 'InsertLineBreaks') + "`n-----END PRIVATE KEY-----`n"
    $publicPem = "-----BEGIN PUBLIC KEY-----`n" + [Convert]::ToBase64String($publicBytes, 'InsertLineBreaks') + "`n-----END PUBLIC KEY-----`n"
    [IO.File]::WriteAllText($privatePath, $privatePem, [Text.Encoding]::ASCII)
    [IO.File]::WriteAllText($publicPath, $publicPem, [Text.Encoding]::ASCII)
    Write-Output "Chaves criadas. Configure OLIUS_JWT_PRIVATE_KEY_PATH e OLIUS_JWT_PUBLIC_KEY_PATH com os caminhos em: $destination"
} finally {
    $rsa.Dispose()
}
