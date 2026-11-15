import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class Bob {
    public static void main(String[] args) throws IOException {
        int port = 9002;
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[Bob] listening on port " + port);
            while (true) {
                try (Socket socket = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                     PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                    String message = in.readLine();
                    System.out.println("[Bob] received: " + message);
                    out.println("OK, received: " + message);
                }
            }
        }
    }
}
