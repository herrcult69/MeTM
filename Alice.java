import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class Alice {
    public static void main(String[] args) throws IOException {
        String bobHost = "localhost";
        int bobPort = 9000;

        try (Socket socket = new Socket(bobHost, bobPort);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String message = "Transfer 100 to Bob";
            System.out.println("[Alice] sending: " + message);
            out.println(message);
            System.out.println("[Alice] reply: " + in.readLine());
        }
    }
}
