package com.herrcult69.metm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Full-duplex line chat over one socket. No identity handshake, no IP check --
 * Alice always dials Bob's real IP and Bob accepts whoever connects. That bare
 * trust is exactly what the ARP-spoofing MITM attack exploits (see IMPLEMENTATION.md).
 * The attack itself runs transparently at the network layer (Ettercap/arpspoof +
 * iptables), so this stays a single unbroken TCP connection end to end -- there is
 * no in-path Java process to crash or reconnect to.
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
