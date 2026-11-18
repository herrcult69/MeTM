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
