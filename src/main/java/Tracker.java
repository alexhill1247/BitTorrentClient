import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class Tracker {

    private EventListeners.PeerListUpdatedListener peerListUpdatedListener;

    public void setPeerListUpdatedListener(EventListeners.PeerListUpdatedListener listener) {
        this.peerListUpdatedListener = listener;
    }

    public enum TrackerEvent {
        started,
        paused,
        stopped
    }

    public String address;

    public Tracker(String address) {
        this.address = address;
    }

    public Instant lastPeerRequest = Instant.EPOCH;
    public Duration peerRequestInterval = Duration.ofMinutes(30);

    public void update(Torrent torrent, TrackerEvent ev, String id, int port) {
        if (ev == TrackerEvent.started && Instant.now().isBefore(lastPeerRequest.plus(peerRequestInterval))) {
            return;
        }

        lastPeerRequest = Instant.now();

        String format = "%s?info_hash=%s&peer_id=%s&port=%d&uploaded=%d&downloaded=%d&left=%d&event=%s&compact=1";
        String url = String.format(format,
                address,
                torrent.getUrlSafeInfoHash(),
                id,
                port,
                torrent.uploaded,
                torrent.downloaded(),
                torrent.remaining(),
                ev.name());

        request(url);
    }

    public void resetLastRequest() {
        lastPeerRequest = Instant.EPOCH;
    }

    public void request(String url) {
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .build();

        CompletableFuture<HttpResponse<byte[]>> responseFuture = httpClient.sendAsync(
                httpRequest, HttpResponse.BodyHandlers.ofByteArray()
        );

        responseFuture.thenAccept(this::handleResponse);
    }

    public void handleResponse(HttpResponse<byte[]> response) {
        byte[] bytes;

        if (response.statusCode() != 200) {
            System.out.println("Error reaching tracker " + this + ": " + response.statusCode());
            return;
        }

        bytes = response.body();
        List<Byte> byteList = new ArrayList<>();
        for (byte b : bytes) {
            byteList.add(b);
        }

        HashMap<String, Object> info = (HashMap<String, Object>) BEncoding.decode(byteList);

        if (info == null) {
            System.out.println("Unable to decode tracker response");
            return;
        }

        peerRequestInterval = Duration.ofSeconds((long) info.get("interval"));
        byte[] peerInfo = (byte[]) info.get("peers");

        ArrayList<InetSocketAddress> peers = new ArrayList<>();
        for (int i = 0; i < peerInfo.length/6; i++) {
            int offset = i * 6;
            String address = peerInfo[offset] + "."
                    + peerInfo[offset+1] + "."
                    + peerInfo[offset+2] + "."
                    + peerInfo[offset+3];
            ByteBuffer buffer = ByteBuffer.wrap(peerInfo);
            buffer.order(ByteOrder.BIG_ENDIAN);
            int port = buffer.getChar(offset+4);

            peers.add(new InetSocketAddress(address, port));
        }

        if (peerListUpdatedListener != null) {
            peerListUpdatedListener.onPeerListUpdated(this, peers);
        }
    }
}
