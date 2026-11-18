param([int]$Port = 9000)
while ($true) {
    java -cp target\classes com.herrcult69.metm.Bob $Port
    if ($LASTEXITCODE -eq 0) { Write-Host "Bob exited cleanly."; break }
    Write-Host "Bob's connection dropped (exit $LASTEXITCODE) - reconnecting in 2s..."
    Start-Sleep -Seconds 2
}
