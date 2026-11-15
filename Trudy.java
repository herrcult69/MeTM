import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class Trudy {
    public static void main(String[] args) throws IOException {
        int listenPort = 9000;
        String realHost = "localhost";
        int realBobPort = 9002;

        try (ServerSocket server = new ServerSocket(listenPort)) {
            System.out.println("[Trudy] waiting for Alice on port " + listenPort);
            try (Socket fromAlice = server.accept();
                 Socket toBob = new Socket(realHost, realBobPort);
                 BufferedReader aliceIn = new BufferedReader(
                         new InputStreamReader(fromAlice.getInputStream()));
                 PrintWriter aliceOut = new PrintWriter(fromAlice.getOutputStream(), true);
                 BufferedReader bobIn = new BufferedReader(new InputStreamReader(toBob.getInputStream()));
                 PrintWriter bobOut = new PrintWriter(toBob.getOutputStream(), true)) {

                String fromAliceMessage = aliceIn.readLine();
                System.out.println("[Trudy] saw Alice -> Bob: " + fromAliceMessage);
                bobOut.println(fromAliceMessage);

                String fromBobMessage = bobIn.readLine();
                System.out.println("[Trudy] saw Bob -> Alice: " + fromBobMessage);
                aliceOut.println(fromBobMessage);
            }
        }
    }
}
