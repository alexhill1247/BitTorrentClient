public class FileItem {
    public String path;
    public long size;
    public long offset;

    public FileItem(String path, long size) {
        this.path = path;
        this.size = size;
    }

    public FileItem(String path, long size, long offset) {
        this.path = path;
        this.size = size;
        this.offset = offset;
    }
}
