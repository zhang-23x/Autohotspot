$ErrorActionPreference = 'Continue'
$adb = "D:\AndroidDev\sdk\platform-tools\adb.exe"
$apk = "D:\deepseek\EDA\autohotspot\autohotspot.apk"
$tmp = "D:\deepseek\EDA\u_loop.xml"

Write-Output "=== push ==="
& $adb push $apk /data/local/tmp/autohotspot.apk 2>&1 | Out-Null

Write-Output "=== 后台启动 pm install ==="
$j = Start-Job -ScriptBlock {
    param($a)
    & $a shell "pm install -r -t /data/local/tmp/autohotspot.apk" 2>&1
} -ArgumentList $adb

function DumpUi {
    & $adb shell "uiautomator dump /sdcard/ul.xml" 2>&1 | Out-Null
    if (Test-Path $tmp) { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
    & $adb pull /sdcard/ul.xml $tmp 2>&1 | Out-Null
    & $adb shell rm /sdcard/ul.xml 2>&1 | Out-Null
    return (Test-Path $tmp)
}

function TapId($file, $id) {
    try {
        $x = [xml](Get-Content $file -Encoding utf8)
        $n = $x.SelectNodes("//node") | Where-Object { $_.'resource-id' -eq $id } | Select-Object -First 1
        if (-not $n) { return $false }
        $b = ($n.bounds -replace '[\[\]]', ' ') -split '\s+' | Where-Object { $_ -ne '' }
        $cx = [int](([int]$b[0] + [int]$b[2]) / 2)
        $cy = [int](([int]$b[1] + [int]$b[3]) / 2)
        Write-Output ("    tap $id @ ($cx,$cy)")
        & $adb shell "input tap $cx $cy" 2>&1 | Out-Null
        return $true
    } catch { return $false }
}

$done = $false
for ($i = 0; $i -lt 45; $i++) {
    Start-Sleep -Milliseconds 900
    if (-not (DumpUi)) { continue }
    $txt = Get-Content $tmp -Raw -Encoding utf8
    if ($txt -match 'install_unknow_source_confirm') {
        if (TapId $tmp 'android:id/button1') { Start-Sleep -Seconds 2 }
        continue
    }
    if ($txt -match 'hidden_card_install_completed_button_completed') {
        TapId $tmp 'com.huawei.appmarket:id/hidden_card_install_completed_button_completed' | Out-Null
        $done = $true
        break
    }
    if ($txt -match '安装成功|安装完成') {
        Write-Output "    检测到安装完成文案"
        $done = $true
    }
}

Write-Output "=== 等待安装进程 ==="
$r = $null
$null = Wait-Job $j -Timeout 60
$r = Receive-Job $j 2>&1
$r | ForEach-Object { Write-Output ("    " + $_) }
Remove-Job $j -Force -ErrorAction SilentlyContinue

& $adb shell input keyevent KEYCODE_BACK 2>&1 | Out-Null
Start-Sleep -Milliseconds 500
& $adb shell input keyevent KEYCODE_HOME 2>&1 | Out-Null

Write-Output "`n=== 核对版本 ==="
& $adb shell "dumpsys package com.yg.autohotspot | grep -E 'versionCode|lastUpdateTime' | head -3" 2>&1
Write-Output ("loopDone = " + $done)
