$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$temporary = Join-Path ([System.IO.Path]::GetTempPath()) ("wacr-privacy-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    $init = Join-Path $temporary 'privacy-test.init.gradle'
    @'
gradle.projectsEvaluated {
    def project = gradle.rootProject.project(':app')
    def task = project.tasks.getByName('verifyDebugNoInternet')
    task.mergedManifest.set(project.file(project.property('privacyGuardFixture')))
    task.verifiedManifest.set(project.layout.buildDirectory.file('privacy-guard-test/AndroidManifest.xml'))
}
'@ | Set-Content -LiteralPath $init -Encoding utf8

    foreach ($case in @(
        @{ Name = 'clean'; Body = '<!-- android.permission.INTERNET is forbidden -->'; Allowed = $true },
        @{ Name = 'permission'; Body = '<uses-permission android:name="android.permission.INTERNET" />'; Allowed = $false },
        @{ Name = 'sdk23'; Body = '<uses-permission-sdk-23 android:name="android.permission.INTERNET" />'; Allowed = $false },
        @{ Name = 'namespace_alias'; Body = '<uses-permission a:name="android.permission.INTERNET" />'; Allowed = $false }
    )) {
        $fixture = Join-Path $temporary ($case.Name + '.xml')
        $xml = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:a="http://schemas.android.com/apk/res/android">' +
            $case.Body + '<application android:allowBackup="false" /></manifest>'
        Set-Content -LiteralPath $fixture -Value $xml -Encoding utf8
        $output = & (Join-Path $root 'gradlew.bat') --console=plain --quiet --init-script $init "-PprivacyGuardFixture=$fixture" :app:verifyDebugNoInternet 2>&1
        $code = $LASTEXITCODE
        if ($case.Allowed -and $code -ne 0) { throw "Clean manifest rejected: $($output -join "`n")" }
        if (-not $case.Allowed -and ($code -eq 0 -or ($output -join "`n") -notmatch 'INTERNET permission forbidden')) {
            throw "Guard failed to reject $($case.Name): $($output -join "`n")"
        }
        Write-Output "PASS privacy guard: $($case.Name)"
    }
} finally {
    Remove-Item -LiteralPath $temporary -Recurse
}
$global:LASTEXITCODE = 0
