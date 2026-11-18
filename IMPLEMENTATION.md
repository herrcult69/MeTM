# MeTM — Implementation Guide v2 (believable identity, naive IP check, seamless attack)

Supersedes the v1 lockstep design. Three things change:

1. **A self-declared identity handshake** (`HELLO <name>`), so the chat looks like
   a normal app with usernames — and so we can demonstrate exactly why trusting
   that label is worthless without cryptography.
2. **A naive IP-allowlist check** on top of that handshake — the "workaround" a
   slightly-more-careful first-year might bolt on — and a precise explanation of
   why it still doesn't save you.
3. **Auto-reconnect**, via a tiny wrapper script, not changed Java semantics —
   so the attack can go live mid-conversation without a visible crash, and so you
   don't need the strict "spoof before they connect" choreography anymore.

Package stays `com.herrcult69.metm`. Terminator stays `EOF END`.

| Who   | IP              | Role                                          |
|-------|-----------------|------------------------------------------------|
| Alice | 192.168.0.123   | client, always dials Bob's real IP             |
| Bob   | 192.168.0.152   | server, listens on 9000                        |
| Trudy | 192.168.0.24    | Kali VM, bridged via USB-Ethernet adapter       |

---

## 1. The handshake: `HELLO <name>`

The very first line either side sends, right after the socket opens, is:

```
HELLO Alice
```
or
```
HELLO Bob
```

Every line after that is ordinary chat text until `EOF END`. The receiving side
remembers that claimed name and labels every incoming line with it:

```
[Alice @ 192.168.0.123] << hey bob
```

**This is deliberately naive.** `HELLO <name>` is plain text with nothing behind
it — no password, no signature, no challenge-response. Whoever is holding the
other end of the TCP connection can send any name they like. That is the point:
it's the simplest possible stand-in for "identity" in a chat app built by two
first-years who haven't had the security course yet, and it sets up the exact
lesson the slides on authentication protocols (ap1.0–ap5.0) are about — a bare
claim of identity, with no proof, is not authentication.

## 2. The naive IP check — and why it still doesn't save you

On top of the claimed name, each side can also compare the *real* socket address
(`socket.getInetAddress()` — the actual IP the OS says this TCP connection comes
from, not anything self-reported) against an expected value. This is "the
workaround": the kind of fix a careful-but-still-naive developer adds after
reading one Stack Overflow answer about spoofing.

**On Bob's side (the server), this check is meaningful and will actually fire.**
Bob accepts whoever connects to him. Trudy's relay opens its *own* new TCP
connection to Bob — from her own real IP, 192.168.0.24 — so the moment she
relays Alice's `HELLO Alice`, Bob's check correctly detects: "this says Alice,
but it's really connecting from 192.168.0.24, not 192.168.0.123." Bob prints a
warning. This is **honest and correct** — a dumb relay through a second machine
is, in fact, detectable at the network layer, if anyone is looking for it.

**On Alice's side (the client), this check is structurally useless, and always
will be, for this kind of attack.** Alice calls `new Socket(host, port)` with
`host` = Bob's real IP. Java reports back exactly the address she asked to
connect to — that's what `socket.getInetAddress()` returns, regardless of what
actually happened at the Ethernet layer. ARP spoofing works by lying at layer 2
(which MAC address an IP belongs to); it is invisible at layer 3 by construction.
So Alice's IP check will say "matches!" even while every byte she sends is
physically arriving at Trudy's network card first. **There is no way to fix this
with an IP check on the client side** — this is exactly why the real fix (Step 3,
AES + HMAC + endpoint authentication) has to exist at all.

**So why does "hidden in plain sight" still hold, even though Bob's check
correctly fires?** Two reasons, both worth saying out loud in the defense:

- **Detection is not protection.** Bob's warning is one line printed once, at
  the moment the connection opens, in a terminal that is about to fill up with
  a normal-looking conversation. A first-year who doesn't know what the warning
  means — or who is mid-conversation and not reading his own console closely —
  scrolls right past it. This is the exact same failure mode as browser TLS
  certificate warnings: the check works, people click through anyway. "Warning
  fatigue" is a well-documented, real phenomenon, not a stretch for the demo.
- **Passing the check (or failing loudly) doesn't protect anything that comes
  after it.** The identity check runs once, at connect time. Every message after
  that is still bare, unauthenticated text. Even if Bob's check were somehow
  satisfied — a more careful attacker doing real IP spoofing, or simply a user
  who dismisses the warning — nothing stops tampering or forgery of any message
  from that point on. The check only ever answers "who connected," never "is
  this specific message really from them."

---

## 3. `Log.java` — unchanged.

## 4. `Peer.java` — handshake + exit-code convention

The reader thread exits the JVM directly on disconnect (same pattern as before —
it's the simplest reliable way to unblock a thread stuck on blocking keyboard
input). What's new: the exit **code** now means something, so a wrapper script
can tell a clean quit from a dropped connection:

- exit **0** — `EOF END` sent or received: a clean close. Don't reconnect.
- exit **2** — I/O error / connection dropped unexpectedly. The wrapper SHOULD
  reconnect.

```java
package com.herrcult69.metm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Full-duplex line chat with a self-declared (NOT verified) identity handshake.
 * See IMPLEMENTATION.md, "The handshake" and "The naive IP check", for why.
 *
 * Exit codes (read by run-alice.ps1 / run-bob.ps1):
 *   0 = clean close (EOF END) - do not reconnect
 *   2 = dropped connection / I/O error - the wrapper SHOULD reconnect
 */
final class Peer {
    static final String END = "EOF END";
    private static final String HELLO_PREFIX = "HELLO ";

    private Peer() {
    }

    /**
     * @param myName         the name THIS program announces itself as
     * @param expectedPeerIp the IP we expect the peer to really be at, or null
     *                       to skip the check
     */
    static void chat(String tag, Socket socket, String myName, String expectedPeerIp) throws IOException {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in))) {

            out.println(HELLO_PREFIX + myName);

            String realPeerIp = socket.getInetAddress().getHostAddress();
            if (expectedPeerIp == null) {
                Log.info(tag, "connected to " + realPeerIp + " (no IP check configured for this role)");
            } else if (realPeerIp.equals(expectedPeerIp)) {
                Log.info(tag, "connected to " + realPeerIp + " (matches the expected address)");
            } else {
                Log.error(tag, "*** WARNING *** connection is really from " + realPeerIp
                        + ", expected " + expectedPeerIp + " - this may not be who it claims to be");
            }

            final String[] claimedName = { "???" };
            Thread reader = new Thread(() -> {
                boolean clean = false;
                try {
                    String line;
                    boolean first = true;
                    while ((line = in.readLine()) != null) {
                        if (first && line.startsWith(HELLO_PREFIX)) {
                            claimedName[0] = line.substring(HELLO_PREFIX.length()).trim();
                            Log.info(tag, "peer announced itself as \"" + claimedName[0] + "\"");
                            first = false;
                            continue;
                        }
                        first = false;
                        if (line.equals(END)) {
                            Log.info(tag, "peer ended the session (EOF END)");
                            clean = true;
                            break;
                        }
                        Log.info(tag, "[" + claimedName[0] + " @ " + realPeerIp + "] << " + line);
                    }
                    if (line == null) {
                        Log.error(tag, "connection dropped by peer");
                    }
                } catch (IOException e) {
                    Log.error(tag, "read error: " + e);
                }
                System.exit(clean ? 0 : 2);
            }, tag + "-reader");
            reader.setDaemon(true);
            reader.start();

            Log.info(tag, "type messages, or EOF END to quit");
            while (true) {
                String msg = keyboard.readLine();
                if (msg == null) {
                    out.println(END);
                    System.exit(0);
                }
                msg = msg.trim();
                out.println(msg);
                if (msg.equals(END)) {
                    System.exit(0);
                }
            }
        }
    }
}
```

## 5. `Alice.java` — same shape, now carries the handshake

```java
package com.herrcult69.metm;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;

public class Alice {
    private static final String TAG = "Alice";

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "192.168.0.152";  // Bob's REAL ip - never Trudy's
        int port    = args.length > 1 ? Integer.parseInt(args[1]) : 9000;

        Log.info(TAG, "dialing Bob at " + host + ":" + port + " ...");
        try (Socket socket = new Socket(host, port)) {
            // expectedPeerIp = host, the address we just dialed. Under ARP spoofing
            // this check is structurally blind: Java reports the address we asked to
            // connect to, not the real L2 path the frames took. It will say "matches"
            // even while Trudy is in the middle. See IMPLEMENTATION.md section 2.
            Peer.chat(TAG, socket, "Alice", host);
        } catch (ConnectException e) {
            Log.error(TAG, "cannot reach " + host + ":" + port + " (" + e.getMessage() + ")");
            System.exit(2);
        }
    }
}
```

## 6. `Bob.java` — one connection per process; the wrapper script relaunches it

No more loop around `accept()`. Each process handles exactly one connection,
then exits. The wrapper script (`run-bob.ps1`, below) is what makes a fresh
process come back and accept the next connection — that's the "reconnect," and
it lives entirely outside the Java code.

```java
package com.herrcult69.metm;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Bob {
    private static final String TAG = "Bob";

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9000;
        String expectedAliceIp = args.length > 1 ? args[1] : "192.168.0.123";

        try (ServerSocket server = new ServerSocket(port)) {
            Log.info(TAG, "listening on " + port + " (expecting Alice at " + expectedAliceIp + ") ...");
            Socket socket = server.accept();
            Log.info(TAG, "connection from " + socket.getRemoteSocketAddress());
            Peer.chat(TAG, socket, "Bob", expectedAliceIp);
        }
    }
}
```

## 7. `Trudy.java` — unchanged, and that's the point

Trudy's relay is still the dumb two-thread pump from v1 (keep your existing
`Trudy.java` as-is). It doesn't need to know about `HELLO` at all: it forwards
every line verbatim, so Alice's real `HELLO Alice` reaches Bob byte-for-byte
unchanged, and Bob's real `HELLO Bob` reaches Alice byte-for-byte unchanged. A
completely dumb relay automatically forwards whatever identity claim flows
through it — zero extra effort from the attacker. That's worth saying explicitly
in the defense: the relay didn't need a single line of code changed to defeat
the new handshake.

The existing operator commands keep working exactly as before:
- `/tobob <text>` — Bob sees it labeled with whatever name Alice's connection
  announced (normally "Alice"), from Trudy's real IP. That's forgery-as-Alice.
- `/toalice <text>` — same, labeled as "Bob."
- `/replace a=b`, `/drop x` — unchanged.

## 8. Auto-reconnect: `run-bob.ps1` / `run-alice.ps1`

Why a wrapper script and not an in-process retry loop: the main thread is
blocked on a keyboard read (`BufferedReader.readLine()` on `System.in`), which
Java cannot cleanly interrupt mid-block. Rather than fight that with complex
non-blocking I/O for a one-off student demo, we do what plenty of real
long-running services do (`systemd Restart=always`, Docker restart policies):
let the whole process die and have something outside it bring it back. Simple,
explainable, robust under demo pressure.

**`run-bob.ps1`** (Bob's laptop):
```powershell
param([int]$Port = 9000, [string]$ExpectedAlice = "192.168.0.123")
while ($true) {
    java -cp target\classes com.herrcult69.metm.Bob $Port $ExpectedAlice
    if ($LASTEXITCODE -eq 0) { Write-Host "Bob exited cleanly."; break }
    Write-Host "Bob's connection dropped (exit $LASTEXITCODE) - reconnecting in 2s..."
    Start-Sleep -Seconds 2
}
```

**`run-alice.ps1`** (Alice's machine):
```powershell
param([string]$BobIp = "192.168.0.152", [int]$Port = 9000)
while ($true) {
    java -cp target\classes com.herrcult69.metm.Alice $BobIp $Port
    if ($LASTEXITCODE -eq 0) { Write-Host "Alice exited cleanly."; break }
    Write-Host "Alice's connection dropped (exit $LASTEXITCODE) - reconnecting in 2s..."
    Start-Sleep -Seconds 2
}
```

Run with: `.\run-bob.ps1` / `.\run-alice.ps1` instead of calling `java` directly.
To actually quit for good, type `EOF END` (exit code 0 breaks the loop) rather
than closing the window.

**What this buys the attack:** you no longer need to get Trudy's ARP spoof and
`iptables` redirect running *before* Alice and Bob start chatting. Bring the
attack infrastructure up whenever you want, mid-conversation included — the
existing connection will drop exactly as before (ARP poisoning hijacks its
packets with nowhere valid to land, same as v1), but now each side's wrapper
script notices the non-zero exit and relaunches within ~2 seconds. The new
connection attempt is a fresh TCP SYN, which the already-live redirect rule
catches automatically. From the keyboard, it looks like: a short gap, a
"reconnecting..." line easy to miss if you're mid-typing, and then the
conversation just... continues. That's the "hidden in plain sight" you asked
for — not literally invisible (there is a real, visible log line), but exactly
the kind of blip a non-technical user dismisses without a second thought.

---

## 9. Build

Unchanged — `mvn -q compile` (or `./mvnw`) from `MeTM/`, or `javac -d target/classes
src/main/java/com/herrcult69/metm/*.java`. `pom.xml` already targets Java 11
source/target, which this code doesn't exceed.

## 10. Run — scenario A: baseline

- Bob: `.\run-bob.ps1`
- Alice: `.\run-alice.ps1`

Expect Bob's console: `connected to 192.168.0.123 (matches the expected
address)`. Expect Alice's console: `connected to 192.168.0.152 (matches the
expected address)`. Chat both ways; each line prefixed `[<name> @ <ip>] <<` on
arrival.

## 11. Run — scenario B: active MITM, mid-conversation this time

1. Start Bob and Alice as in scenario A. Let them chat for a bit — prove it's a
   real, working, unattacked conversation first.
2. *Now*, on Trudy's Kali VM, bring the attack up (same as v1):
   ```
   sudo sysctl -w net.ipv4.ip_forward=1
   sudo sysctl -w net.ipv4.conf.all.rp_filter=0
   sudo arpspoof -i eth0 -t 192.168.0.123 192.168.0.152
   ```
   in another terminal:
   ```
   sudo iptables -t nat -A PREROUTING -p tcp -d 192.168.0.152 --dport 9000 -j REDIRECT --to-port 6000
   ```
   in a third:
   ```
   java -cp target/classes com.herrcult69.metm.Trudy 6000 192.168.0.152 9000
   ```
3. Alice and Bob's existing connection dies the moment the ARP spoof takes
   effect (expected — see v1 notes on why). Watch their consoles print the
   dropped-connection message and "reconnecting in 2s..." — then reconnect on
   their own, no manual restart needed.
4. Bob's console now prints the warning: connection is really from
   `192.168.0.24`, expected `192.168.0.123`. That's the naive check correctly
   firing — and then the conversation continues right underneath it, looking
   completely normal.
5. From Trudy's keyboard: `/replace`, `/drop`, `/toalice`, `/tobob` as in v1.

## 12. Teardown

Same as v1 — flush the `iptables` NAT table, `Ctrl+C` arpspoof, forwarding off,
`arp -d *` on Alice and Bob.

---

## 13. What goes in README.md instead of here

This file is the *design rationale* — why each piece exists and what it proves.
`README.md` is the *practical runbook* for someone who just wants to run the
thing: environment setup (hardware, TotoLink settings, VMware bridging) and the
attack walkthrough (exact commands, in order, no explanation). Keep them
separate so a teammate who missed the design discussion can still run the demo
from the README alone.
