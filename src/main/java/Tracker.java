import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
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

        //System.out.println("updating...");

        lastPeerRequest = Instant.now();

        /*
        Get client external IP from web service.
        Necessary for when the tracker is on the same network as the client, otherwise
        tracker will add local IP to peer list.
         */
        URL checkIP;
        String externalIP;
        try {
            checkIP = new URL("http://checkip.amazonaws.com");
            BufferedReader in = new BufferedReader(new InputStreamReader(checkIP.openStream()));
            externalIP = in.readLine();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String format = "%s?info_hash=%s&peer_id=%s&port=%d&uploaded=%d&downloaded=%d&left=%d&event=%s&compact=1&ip=%s";
        String url = String.format(format,
                address,
                torrent.getUrlSafeInfoHash(),
                id,
                port,
                torrent.uploaded,
                torrent.downloaded(),
                torrent.remaining(),
                ev.name(),
                externalIP);

        //System.out.println("requesting: " + url);
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
        //System.out.println("received response to request");
        byte[] bytes;

        if (response.statusCode() != 200) {
            System.out.println("Error reaching tracker " + this + ": " + response.statusCode());
            return;
        }

        bytes = response.body();
        System.out.println("received bytes: " + bytes.length);
        List<Byte> byteList = new ArrayList<>();
        for (byte b : bytes) {
            byteList.add(b);
        }
        //System.out.println("converted to list");

        Map<String, Object> info = null;
        try {
            //noinspection unchecked
            info = (Map<String, Object>) BEncoding.decode(byteList);
            //System.out.println("decoded info: " + info);
        } catch (Exception e) {
            System.out.println("failed to decode byte list: " + e.getMessage());
        }

        if (info == null) {
            System.out.println("Unable to decode tracker response");
            return;
        }

        peerRequestInterval = Duration.ofSeconds((long) info.get("interval"));
        //System.out.println("interval: " + peerRequestInterval);
        byte[] peerInfo = (byte[]) info.get("peers");
        //System.out.println("peer info: " + new String(peerInfo, StandardCharsets.UTF_8));

        ArrayList<InetSocketAddress> peers = new ArrayList<>();
        for (int i = 0; i < peerInfo.length/6; i++) {
            int offset = i * 6;
            String address = (peerInfo[offset]   & 0xFF) + "."
                           + (peerInfo[offset+1] & 0xFF) + "."
                           + (peerInfo[offset+2] & 0xFF) + "."
                           + (peerInfo[offset+3] & 0xFF);
            ByteBuffer buffer = ByteBuffer.wrap(peerInfo);
            buffer.order(ByteOrder.BIG_ENDIAN);
            int port = buffer.getChar(offset+4);

            //System.out.println("peer: " + address + ":" + port);
            peers.add(new InetSocketAddress(address, port));
        }

        if (peerListUpdatedListener != null) {
            peerListUpdatedListener.onPeerListUpdated(this, peers);
        }
    }
}
