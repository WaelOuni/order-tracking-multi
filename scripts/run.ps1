$envFile = Join-Path $PSScriptRoot "..\\.env"

if (Test-Path $envFile) {
    Get-Content $envFile |
        Where-Object { $_ -and -not $_.StartsWith("#") } |
        ForEach-Object {
            $pair = $_ -split "=", 2
            if ($pair.Length -eq 2) {
                $name = $pair[0].Trim()
                $value = $pair[1].Trim()
                if ($name) {
                    Set-Item -Path "Env:$name" -Value $value
                }
            }
        }
}

mvn spring-boot:run
