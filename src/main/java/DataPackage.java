public class DataPackage {
    public Peer peer;
    public int piece;
    public int block;
    public byte[] data;

    public DataPackage(Peer peer, int piece, int block, byte[] data) {
        this.peer = peer;
        this.piece = piece;
        this.block = block;
        this.data = data;
    }
}
