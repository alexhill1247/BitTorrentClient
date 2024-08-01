import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class Torrent {
    public String name;
    public boolean isPrivate;
    public List<FileItem> Files = new ArrayList<>();
    private String fileDirectory;
    public String downloadDirectory;

    public List<Tracker> trackers = new ArrayList<>();
    public String comment;
    public String createdBy;
    public Date creationDate;
    public Charset encoding;

    public int blockSize;
    public int pieceSize;

    public byte[][] pieceHashes;


    public String getFileDirectory() {
        return (Files.size() > 1 ? name + File.separatorChar : "");
    }
}

//TODO fix access modifiers