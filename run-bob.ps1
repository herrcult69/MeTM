param([int]$Port = 9000, [string]$ExpectedAlice = "192.168.0.123")
while ($true) {
    java -cp target\classes com.herrcult69.metm.Bob $Port $ExpectedAlice
    if ($LASTEXITCODE -eq 0) { Write-Host "Bob exited cleanly."; break }
    Write-Host "Bob's connection dropped (exit $LASTEXITCODE) - reconnecting in 2s..."
    Start-Sleep -Seconds 2
}
