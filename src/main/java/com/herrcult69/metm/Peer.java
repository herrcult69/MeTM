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
        try (Socket s = socket;
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
