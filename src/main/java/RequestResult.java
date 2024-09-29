public class RequestResult {
    boolean success;
    int index;
    int begin;
    int length;

    public RequestResult(boolean success, int index, int begin, int length) {
        this.success = success;
        this.index = index;
        this.begin = begin;
        this.length = length;
    }
}
