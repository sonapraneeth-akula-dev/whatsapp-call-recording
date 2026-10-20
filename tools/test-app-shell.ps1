param(
    [Parameter(Mandatory = $true)]
    [string]$SdkPath
)

$ErrorActionPreference = 'Stop'
$analyzer = Join-Path $SdkPath 'cmdline-tools\latest\bin\apkanalyzer.bat'
if (-not (Test-Path $analyzer)) { throw "APK analyzer not found: $analyzer" }
$aapt = Join-Path $SdkPath 'build-tools\36.0.0\aapt2.exe'
if (-not (Test-Path $aapt)) { throw "Android build-tools 36.0.0 not found: $aapt" }
$root = Split-Path $PSScriptRoot -Parent
$android = 'http://schemas.android.com/apk/res/android'

foreach ($case in @(
    @{ Apk = 'app\build\outputs\apk\debug\app-debug.apk'; Id = 'com.soakula.wacr.debug'; Label = 'WACR Debug' },
    @{ Apk = 'app\build\outputs\apk\release\app-release-unsigned.apk'; Id = 'com.soakula.wacr'; Label = 'WACR' },
    @{ Apk = 'spike\build\outputs\apk\debug\spike-debug.apk'; Id = 'com.soakula.wacr.spike'; Label = 'WACR Spike' }
)) {
    $apk = Join-Path $root $case.Apk
    if (-not (Test-Path $apk)) { throw "Build the APK first: $apk" }
    $output = & $analyzer manifest print $apk
    if ($LASTEXITCODE -ne 0) { throw "Cannot read manifest: $apk" }
    [xml]$manifest = $output -join "`n"
    if ($manifest.manifest.package -ne $case.Id) { throw "Unexpected application ID in $apk" }
    $badging = & $aapt dump badging $apk
    if ($LASTEXITCODE -ne 0) { throw "Cannot read APK badging: $apk" }
    if ($badging -notcontains "application-label:'$($case.Label)'") {
        throw "Unexpected launcher label in $apk"
    }
    if ($manifest.manifest.application.GetAttribute('allowBackup', $android) -ne 'false') {
        throw "Backup enabled in $apk"
    }
    foreach ($attribute in @('minSdkVersion', 'targetSdkVersion')) {
        $expected = if ($attribute -eq 'minSdkVersion') { '33' } else { '36' }
        if ($manifest.manifest.'uses-sdk'.GetAttribute($attribute, $android) -ne $expected) {
            throw "Unexpected $attribute in $apk"
        }
    }
    foreach ($permission in $manifest.manifest.SelectNodes('uses-permission | uses-permission-sdk-23')) {
        if ($permission.GetAttribute('name', $android) -eq 'android.permission.INTERNET') {
            throw "INTERNET permission present in $apk"
        }
    }
    if ($case.Id -ne 'com.soakula.wacr.spike') {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($apk)
        try {
            $nativeLibraries = @($archive.Entries | Where-Object { $_.FullName -match '^lib/.+\.so$' })
            if ('lib/arm64-v8a/libsqlcipher.so' -notin $nativeLibraries.FullName) {
                throw "SQLCipher native library missing in $apk"
            }
            if ($nativeLibraries | Where-Object { $_.FullName -notmatch '^lib/arm64-v8a/' }) {
                throw "Unexpected native ABI in $apk"
            }
        } finally {
            $archive.Dispose()
        }
        if (-not $manifest.manifest.application.GetAttribute('dataExtractionRules', $android)) {
            throw "Backup/transfer exclusions missing in $apk"
        }
        $resource = & $analyzer resources value --type xml --name data_extraction_rules --config default $apk
        if ($LASTEXITCODE -ne 0) { throw "Cannot resolve extraction rules: $apk" }
        $output = & $analyzer resources xml --file $resource $apk
        if ($LASTEXITCODE -ne 0) { throw "Cannot read extraction rules: $apk" }
        [xml]$rules = $output -join "`n"
        foreach ($transport in @('cloud-backup', 'device-transfer')) {
            foreach ($domain in @('root', 'file', 'database', 'sharedpref', 'device_root', 'device_file', 'device_database', 'device_sharedpref', 'external')) {
                if (-not $rules.SelectSingleNode("/data-extraction-rules/$transport/exclude[@domain='$domain' and @path='.']")) {
                    throw "$transport does not exclude $domain in $apk"
                }
            }
        }
    }
    $launcher = $manifest.manifest.application.activity | Where-Object {
        $_.'intent-filter'.action.GetAttribute('name', $android) -eq 'android.intent.action.MAIN' -and
        $_.'intent-filter'.category.GetAttribute('name', $android) -eq 'android.intent.category.LAUNCHER'
    }
    if (@($launcher).Count -ne 1 -or $launcher.GetAttribute('exported', $android) -ne 'true') {
        throw "Expected one exported launcher in $apk"
    }
    Write-Output "PASS $($case.Id): identity, label, SDK levels, launcher, no backup or INTERNET"
}
