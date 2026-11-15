package com.herrcult69.metm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.Socket;

public class Alice {
    private static final String TAG = "Alice";

    public static void main(String[] args) throws IOException {
        String host = "localhost";
        int port = 9000;            // Alice believes Bob is here

        BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in));
        int sent = 0;

        Log.info(TAG, "connecting to " + host + ":" + port + " (Trudy, posing as Bob) ...");
        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            Log.info(TAG, "CONNECTED to " + socket.getRemoteSocketAddress()
                    + " (local port " + socket.getLocalPort() + ")");
            Log.info(TAG, "type a message, or EOF END to quit (Ctrl+Z / Ctrl+D also quits)");
            while (true) {
                System.out.print("Alice> ");
                String message = keyboard.readLine();
                if (message == null) {                        // Ctrl+Z / Ctrl+D
                    Log.info(TAG, "input closed, ending session");
                    break;
                }
                message = message.trim();
                out.println(message);
                sent++;
                Log.info(TAG, "sent #" + sent + " (" + message.length() + " chars): " + message);

                if (message.equals("EOF END")) {              // no reply to END
                    Log.info(TAG, "EOF END sent, ending session");
                    break;
                }

                String reply = in.readLine();
                if (reply == null) {
                    Log.error(TAG, "connection closed by peer (no reply to #" + sent + ")");
                    break;
                }
                Log.info(TAG, "reply #" + sent + ": " + reply);
            }
        } catch (ConnectException e) {
            Log.error(TAG, "could not connect to " + host + ":" + port
                    + " - is Trudy running? (" + e.getMessage() + ")");
        } catch (IOException e) {
            Log.error(TAG, "I/O error: " + e);
        }
        Log.info(TAG, "bye - " + sent + " message(s) sent");
    }
}
