param([string]$BobIp = "192.168.0.152", [int]$Port = 9000)
while ($true) {
    java -cp target\classes com.herrcult69.metm.Alice $BobIp $Port
    if ($LASTEXITCODE -eq 0) { Write-Host "Alice exited cleanly."; break }
    Write-Host "Alice's connection dropped (exit $LASTEXITCODE) - reconnecting in 2s..."
    Start-Sleep -Seconds 2
}
