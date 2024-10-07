public class DataRequest {
    public Peer peer;
    public int piece;
    public int begin;
    public int length;
    public boolean isCancelled = false;

    public DataRequest(Peer peer, int piece, int begin, int length) {
        this.peer = peer;
        this.piece = piece;
        this.begin = begin;
        this.length = length;
    }
}