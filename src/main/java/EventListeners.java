import java.net.InetSocketAddress;
import java.util.List;

public class EventListeners {
    public interface DisconnectedListener {
        void onDisconnected(Peer peer);
    }

    public interface StateChangedListener {
        void onStateChanged();
    }

    public interface BlockRequestedListener {
        void onBlockRequested(DataRequest request);
    }

    public interface BlockCancelledListener {
        void onBlockCancelled(DataRequest request);
    }

    public interface BlockReceivedListener {
        void onBlockReceived(DataPackage data);
    }

    public interface PeerListUpdatedListener {
        void onPeerListUpdated(Object sender, List<InetSocketAddress> peerList);
    }

    public interface PieceVerifiedListener {
        void onPieceVerified(int pieceVerified);
    }
}
