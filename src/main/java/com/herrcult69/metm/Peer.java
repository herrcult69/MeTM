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
        try (Socket s = socket;
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
