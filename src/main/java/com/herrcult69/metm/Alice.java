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
