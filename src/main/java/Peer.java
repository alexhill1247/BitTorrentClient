import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Peer {
    //TODO event handlers

    public String localID;
    public String id;

    public Torrent torrent;

    public InetSocketAddress inetSocketAddress;
    public String key;
    public String getKey() {
        return inetSocketAddress.toString();
    }

    private Socket socket;
    private InputStream inputStream; // connect this to socket
    private OutputStream outputStream; // ^^
    private final int bufferSize = 256;
    private byte[] streamBuffer = new byte[bufferSize];
    private ArrayList<Byte> data = new ArrayList<>();

    public Boolean[] isPieceDownloaded = new Boolean[0];
    public String getPiecesDownloaded() {
        return Arrays.stream(isPieceDownloaded)
                .map(x -> Integer.toString(x ? 1 : 0))
                .collect(Collectors.joining(""));
    }
    public int getPiecesRequiredAvailable() {
        return (int) IntStream.range(0, isPieceDownloaded.length)
                .filter(i -> isPieceDownloaded[i] && !torrent.isPieceVerified[i])
                .count();
    }
    public int getPiecesDownloadedCount() {
        return (int) Arrays.stream(isPieceDownloaded)
                .filter(x -> x)
                .count();
    }
    public boolean isCompleted() {
        return getPiecesDownloadedCount() == torrent.getPieceCount();
    }

    public boolean isDisconnected;

    public boolean isHandshakeSent;
    public boolean isPositionSent;
    public boolean isChokeSent = true;
    public boolean isInterestedSent = false;

    public boolean isHandshakeReceived;
    public boolean isChokeReceived = true;
    public boolean isInterestedReceived = false;

    public Boolean[][] isBlockRequested = new Boolean[0][];
    public int getBlocksRequested() {
        return Arrays.stream(isBlockRequested)
                .mapToInt(x -> (int) Arrays.stream(x)
                        .filter(y -> y)
                        .count())
                .sum();
    }

    public ZonedDateTime lastActive;
    public ZonedDateTime lastKeepAlive = ZonedDateTime.from(Instant.EPOCH);

    public long uploaded;
    public long downloaded;

    public Peer(Torrent torrent, String localID, Socket client) {
        this.torrent = torrent;
        this.localID = localID;
        socket = client;
        inetSocketAddress = (InetSocketAddress) client.getRemoteSocketAddress();
    }

    public Peer(Torrent torrent, String localID, InetSocketAddress endPoint) {
        this.torrent = torrent;
        this.localID = localID;
        inetSocketAddress = endPoint;
    }

    private Peer(Torrent torrent, String localID) {
        this.torrent = torrent;
        this.localID = localID;

        lastActive = ZonedDateTime.now();
        int pieceCount = torrent.getPieceCount();
        isPieceDownloaded = new Boolean[pieceCount];
        isBlockRequested = new Boolean[pieceCount][];
        for (int i = 0; i < pieceCount; i++) {
            isBlockRequested[i] = new Boolean[torrent.getBlockCount(i)];
        }
    }
}