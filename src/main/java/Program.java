import java.nio.file.Files;
import java.nio.file.Path;

public class Program {

    public static Client client;

    public static void main (String[] args) {
        int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            throw new NumberFormatException("Not a valid port number");
        }

        if (args.length != 3 || !Files.exists(Path.of(args[1]))) {
            System.out.println("Error: arguments are port, torrent file, download directory");
            return;
        }

        client = new Client(port, args[1], args[2]);
        client.start();

        // Run client.stop() if SIGINT signal received
        Runtime.getRuntime().addShutdownHook(new Thread(client::stop));

        // Blocks main thread until signal above received, unsure if this is necessary
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
