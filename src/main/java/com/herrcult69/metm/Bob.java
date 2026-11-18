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
