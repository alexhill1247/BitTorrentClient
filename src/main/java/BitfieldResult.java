public class BitfieldResult {
    boolean success;
    public boolean[] isPieceDownloaded;

    public BitfieldResult(boolean success, boolean[] isPieceDownloaded) {
        this.success = success;
        this.isPieceDownloaded = isPieceDownloaded;
    }
}
