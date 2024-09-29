public class HandshakeResult {
    public boolean success;
    public byte[] hash;
    public String id;

    public HandshakeResult(boolean success, byte[] hash, String id) {
        this.success = success;
        this.hash = hash;
        this.id = id;
    }
}
