package com.herrcult69.metm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;

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

    // Application-level heartbeat. An ARP-spoofed, already-established TCP connection
    // doesn't fail fast on its own - the OS will keep retrying for ~20-30+ seconds
    // before giving up, which is a dead gap in a live demo. Sending a throwaway line
    // every HEARTBEAT_INTERVAL_MS and expecting *something* (a heartbeat or a real
    // chat line) within HEARTBEAT_TIMEOUT_MS means a hijacked connection is detected
    // in ~2s instead of ~30s, because the heartbeats get blackholed right along with
    // everything else the instant the spoof takes effect.
    static final String HEARTBEAT = "\u0001HB"; // package-private: Trudy's relay needs to recognize and skip these
    private static final int HEARTBEAT_INTERVAL_MS = 700;
    private static final int HEARTBEAT_TIMEOUT_MS = 2000;

    private Peer() {
    }

    /** @param peerName the role this side is always talking to ("Bob" or "Alice") */
    static void chat(String tag, Socket socket, String peerName) throws IOException {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in))) {

            socket.setSoTimeout(HEARTBEAT_TIMEOUT_MS);

            String peerAddr = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
            String prompt = "To " + peerName + " (" + peerAddr + ") > ";
            Log.info(tag, "connected to " + peerName + " at " + peerAddr);

            Thread heartbeat = new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(HEARTBEAT_INTERVAL_MS);
                        out.println(HEARTBEAT);
                    }
                } catch (InterruptedException ignored) {
                    // JVM is exiting; nothing to clean up.
                }
            }, tag + "-heartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();

            Thread reader = new Thread(() -> {
                boolean clean = false;
                try {
                    String line;
                    while (true) {
                        try {
                            line = in.readLine();
                        } catch (SocketTimeoutException e) {
                            Log.error(tag, "no heartbeat from " + peerName
                                    + " in " + HEARTBEAT_TIMEOUT_MS + "ms - connection presumed dead");
                            break;
                        }
                        if (line == null) {
                            Log.error(tag, "connection dropped by peer");
                            break;
                        }
                        if (line.equals(HEARTBEAT)) {
                            continue;
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
