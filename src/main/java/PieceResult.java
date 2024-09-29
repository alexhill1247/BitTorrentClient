public class PieceResult {
    boolean success;
    int index;
    int begin;
    byte[] data;

    public PieceResult(boolean success, int index, int begin, byte[] data) {
        this.success = success;
        this.index = index;
        this.begin = begin;
        this.data = data;
    }
}
