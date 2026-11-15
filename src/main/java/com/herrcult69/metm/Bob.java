package com.herrcult69.metm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class Bob {
    private static final String TAG = "Bob";

    public static void main(String[] args) throws IOException {
        int port = 9002;
        try (ServerSocket server = new ServerSocket(port)) {
            Log.info(TAG, "listening on port " + port + " (waiting for Trudy) ...");
            int connection = 0;
            while (true) {
                try (Socket socket = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                     PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                    connection++;
                    Log.info(TAG, "connection #" + connection + " ACCEPTED from "
                            + socket.getRemoteSocketAddress() + " (local port " + socket.getLocalPort() + ")");

                    int handled = 0;
                    String message;
                    while ((message = in.readLine()) != null) {
                        if (message.equals("EOF END")) {
                            Log.info(TAG, "EOF END received - session ended by peer");
                            break;
                        }
                        handled++;
                        Log.info(TAG, "received #" + handled + " (" + message.length() + " chars): " + message);
                        String reply = "OK, received: " + message;
                        out.println(reply);
                        Log.info(TAG, "replied #" + handled + ": " + reply);
                    }
                    if (message == null) {
                        Log.info(TAG, "peer closed the connection without EOF END");
                    }
                    Log.info(TAG, "connection #" + connection + " CLOSED after " + handled + " message(s)");
                } catch (IOException e) {
                    Log.error(TAG, "I/O error: " + e);
                }
            }
        }
    }
}
