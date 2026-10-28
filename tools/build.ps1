$ErrorActionPreference = 'Continue'
$SRC   = $PSScriptRoot
$BUILD = Join-Path $SRC 'build'
$JDK   = 'D:\Program Files\Java\jdk-17\bin'
$BT    = 'D:\AndroidDev\sdk\build-tools\34.0.0'
$AJAR  = 'D:\AndroidDev\sdk\platforms\android-34\android.jar'

function Step($name) { Write-Host "`n>>> $name" -ForegroundColor Cyan }

if (Test-Path $BUILD) { Remove-Item $BUILD -Recurse -Force }
New-Item -ItemType Directory -Path $BUILD | Out-Null

Step '1) aapt2 compile 资源'
& "$BT\aapt2.exe" compile --dir "$SRC\res" -o "$BUILD\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Step '2) aapt2 link 生成基础 APK + R.java'
& "$BT\aapt2.exe" link -o "$BUILD\base.apk" -I $AJAR `
    --manifest "$SRC\AndroidManifest.xml" `
    -R "$BUILD\res.zip" `
    --java "$BUILD\gen" `
    --min-sdk-version 26 --target-sdk-version 29 `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Step '3) javac 编译 Java 源码'
$srcs = @()
$srcs += Get-ChildItem "$SRC\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcs += Get-ChildItem "$BUILD\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host ("    java 文件: " + $srcs.Count)
New-Item -ItemType Directory -Path "$BUILD\classes" -Force | Out-Null
# javac 的 "注: 某些输入文件使用已过时的 API" 会走 stderr，
# 直接管道会让 PowerShell 把它当 NativeCommandError 并污染 $LASTEXITCODE，
# javac 的 "注: 某些输入文件使用已过时的 API" 走 stderr。
# PowerShell 的 & 调用会把它当 NativeCommandError 并污染 $LASTEXITCODE；
# Start-Process 在本机又会撞 NO_PROXY/no_proxy 环境字典冲突。
# 因此改用 cmd /c：退出码可靠，stdout/stderr 各自落盘，只在失败时回显。
$jargs = @('-encoding','UTF-8','-source','8','-target','8','-nowarn','-classpath', "`"$AJAR`"", '-d', "`"$BUILD\classes`"") + $srcs
$jline = '"' + "$JDK\javac.exe" + '" ' + ($jargs -join ' ') + ' > "' + "$BUILD\javac.out" + '" 2> "' + "$BUILD\javac.err" + '"'
$null = cmd /c $jline
$jcode = $LASTEXITCODE
if ($jcode -ne 0) {
    if (Test-Path "$BUILD\javac.err") { Get-Content "$BUILD\javac.err" | ForEach-Object { Write-Host "    $_" } }
    throw 'javac failed'
}

Step '4) 打包 classes.jar'
Push-Location "$BUILD\classes"
& "$JDK\jar.exe" cf "$BUILD\classes.jar" .
Pop-Location
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

Step '5) d8 生成 classes.dex'
New-Item -ItemType Directory -Path "$BUILD\dex" -Force | Out-Null
& "$BT\d8.bat" --lib $AJAR --min-api 26 --output "$BUILD\dex" "$BUILD\classes.jar"
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Step '6) 把 classes.dex 塞进 APK'
Copy-Item "$BUILD\base.apk" "$BUILD\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$BUILD\unsigned.apk", 'Update')
$existing = $zip.GetEntry('classes.dex')
if ($existing) { $existing.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$BUILD\dex\classes.dex", 'classes.dex', 'Optimal') | Out-Null
$zip.Dispose()

Step '7) zipalign'
& "$BT\zipalign.exe" -f -p 4 "$BUILD\unsigned.apk" "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Step '8) 生成签名密钥'
$KS = "$SRC\debug.keystore"
if (-not (Test-Path $KS)) {
    & "$JDK\keytool.exe" -genkeypair -v -keystore $KS -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

Step '9) apksigner 签名'
$APK = "$SRC\autohotspot.apk"
if (Test-Path $APK) { Remove-Item $APK -Force }
& "$BT\apksigner.bat" sign --ks $KS --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $APK "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Step '10) 校验'
& "$BT\apksigner.bat" verify --print-certs $APK
Write-Host "`n构建完成: $APK" -ForegroundColor Green
Get-Item $APK | Select-Object FullName, Length | Format-List
