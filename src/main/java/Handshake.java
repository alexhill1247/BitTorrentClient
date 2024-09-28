public class Handshake {
    public boolean success;
    public byte[] hash;
    public String id;

    public Handshake(boolean success, byte[] hash, String id) {
        this.success = success;
        this.hash = hash;
        this.id = id;
    }
}
