import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;

public class Tracker {

    //TODO needs some sort of event handler for when peers are updated

    public enum trackerEvent {
        started,
        paused,
        stopped
    }

    public String address;

    public Tracker(String address) {
        this.address = address;
    }

    public ZonedDateTime lastPeerRequest = ZonedDateTime.from(Instant.EPOCH);
    public Duration peerRequestInterval = Duration.ofMinutes(30);

    public void update(Torrent torrent, trackerEvent ev, String id, int port) {
        if (ev == trackerEvent.started && ZonedDateTime.now().isBefore(lastPeerRequest.plus(peerRequestInterval))) {
            return;
        }

        lastPeerRequest = ZonedDateTime.now();

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
        lastPeerRequest = ZonedDateTime.from(Instant.EPOCH);
    }

    public void request(String url) {
        //TODO
    }
}
