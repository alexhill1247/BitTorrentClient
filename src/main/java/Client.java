import java.util.Random;

public class Client {
    public int port;
    public Torrent torrent;
    public String id;

    private Random random = new Random();

    public Client(int port, String torrentPath, String downloadPath) {
        id = "";
        for (int i = 0; i < 20; i++) {
            id += random.nextInt(10);
        }

        this.port = port;

        torrent = Torrent.loadFromFile(torrentPath, downloadPath);
        //TODO register Torrent.PieceVerified event to handlePieceVerified
        //TODO ''               PeerListUpdated ''

        System.out.println(torrent);
    }

    //----------------------------------
    //             Threads
    //----------------------------------

    private boolean isStopping;
    //TODO dont like these names
    private int isProcessPeers = 0;
    private int isProcessUploads = 0;
    private int isProcessDownloads = 0;

    public void start() {
        System.out.println("Starting client");

        isStopping = false;

        //TODO Torrent.ResetTrackersLastRequest();

        enablePeerConnections();

        // tracker
        new Thread(() -> {
            while (!isStopping) {
                torrent.updateTrackers(Tracker.TrackerEvent.started, id, port);
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();

        // peer
        new Thread(() -> {
            while (!isStopping) {
                processPeers();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();

        // upload
        new Thread(() -> {
            while (!isStopping) {
                processUploads();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();

        // download
        new Thread(() -> {
            while (!isStopping) {
                processDownloads();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    public void stop() {
        System.out.println("Stopping client");

        isStopping = true;
        disablePeerConnections();
        torrent.updateTrackers(Tracker.TrackerEvent.stopped, id, port);
    }

}
