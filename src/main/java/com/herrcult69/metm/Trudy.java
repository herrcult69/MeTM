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

    public static void main(String[] args) throws IOException {
        int listenPort = 9000;      // where Alice thinks Bob is
        String bobHost = "localhost";
        int bobPort = 9002;         // where Bob really is

        try (ServerSocket server = new ServerSocket(listenPort)) {
            Log.info(TAG, "listening on port " + listenPort + " for Alice; will relay to Bob at "
                    + bobHost + ":" + bobPort);
            int session = 0;
            while (true) {
                try (Socket fromAlice = server.accept()) {
                    session++;
                    Log.info(TAG, "session #" + session + ": Alice CONNECTED from "
                            + fromAlice.getRemoteSocketAddress() + " (local port " + fromAlice.getLocalPort() + ")");
                    relay(session, fromAlice, bobHost, bobPort);
                } catch (ConnectException e) {
                    Log.error(TAG, "could not reach Bob at " + bobHost + ":" + bobPort
                            + " - is Bob running? (" + e.getMessage() + ")");
                } catch (IOException e) {
                    Log.error(TAG, "session I/O error: " + e);
                }
            }
        }
    }

    private static void relay(int session, Socket fromAlice, String bobHost, int bobPort) throws IOException {
        Log.info(TAG, "session #" + session + ": connecting to Bob at " + bobHost + ":" + bobPort + " ...");
        try (Socket toBob = new Socket(bobHost, bobPort);
             BufferedReader aliceIn = new BufferedReader(new InputStreamReader(fromAlice.getInputStream()));
             PrintWriter aliceOut = new PrintWriter(fromAlice.getOutputStream(), true);
             BufferedReader bobIn = new BufferedReader(new InputStreamReader(toBob.getInputStream()));
             PrintWriter bobOut = new PrintWriter(toBob.getOutputStream(), true)) {

            Log.info(TAG, "session #" + session + ": CONNECTED to Bob at " + toBob.getRemoteSocketAddress()
                    + " (local port " + toBob.getLocalPort() + ")");

            int relayed = 0;
            String message;
            while ((message = aliceIn.readLine()) != null) {
                relayed++;
                Log.info(TAG, "Alice -> Bob #" + relayed + " (" + message.length() + " chars): " + message);
                bobOut.println(message);              // forward, END included

                if (message.equals("EOF END")) {
                    Log.info(TAG, "EOF END relayed - ending session #" + session);
                    break;
                }

                String reply = bobIn.readLine();
                if (reply == null) {                  // Bob went away
                    Log.error(TAG, "Bob closed the connection (no reply to #" + relayed + ")");
                    break;
                }
                Log.info(TAG, "Bob -> Alice #" + relayed + ": " + reply);
                aliceOut.println(reply);
            }
            if (message == null) {
                Log.info(TAG, "Alice closed the connection without EOF END");
            }
            Log.info(TAG, "session #" + session + " CLOSED after " + relayed + " message(s)");
        }
    }
}
