import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Torrent {
    public String name;
    public boolean isPrivate;
    public List<FileItem> files = new ArrayList<>();
    public String getFileDirectory() { return (files.size() > 1 ? name + File.separatorChar : ""); }
    public String downloadDirectory;

    public List<Tracker> trackers = new ArrayList<>();
    public String comment;
    public String createdBy;
    public Date creationDate;
    public Charset encoding;

    public int blockSize;
    public int pieceSize;
    public long getTotalSize() {
        long totalSize = 0;
        for (FileItem file : files) {
            totalSize += file.size;
        }
        return totalSize;
    }
    public String getPieceSizeStr() { return String.valueOf(pieceSize); }
    public String getTotalSizeStr() { return String.valueOf(getTotalSize()); }

    public byte[][] pieceHashes;
    public int getPieceCount() { return pieceHashes.length; }
    public boolean[] isPieceVerified;
    public boolean[][] isBlockAcquired;

    public String getVerifiedPiecesStr() {
        return IntStream.range(0, isPieceVerified.length)
                .mapToObj(i -> isPieceVerified[i] ? "1" : "0")
                .collect(Collectors.joining());
    }
    public int getVerifiedPiecesCount() {
        return (int) IntStream.range(0, isPieceVerified.length)
                .filter(i -> isPieceVerified[i])
                .count();
    }
    public double getVerifiedRatio() { return (double) getVerifiedPiecesCount() / getPieceCount(); }
    public boolean isCompleted() { return getVerifiedPiecesCount() == getPieceCount(); }
    public boolean hasStarted() { return getVerifiedPiecesCount() > 0; }

    public long uploaded = 0;
    //TODO apparently this is wrong
    public long downloaded() { return (long) pieceSize * (long) getVerifiedPiecesCount(); }
    public long remaining() { return getTotalSize() - downloaded(); }

    // The last piece may not be the full size
    public int getPieceSize(int piece) {
        if (piece == getPieceCount() - 1) {
            int remainder = (int) (getTotalSize() % pieceSize);
            if (remainder != 0 ) return remainder;
        }
        return pieceSize;
    }

    public int getBlockCount(int piece) {
        return (int) Math.ceil((double) getPieceSize(piece) / (double) blockSize);
    }

    // The last block may not be the full size
    public int getBlockSize(int piece, int block) {
        if (block == getBlockCount(piece) - 1) {
            int remainder = getPieceSize(piece) % blockSize;
            if (remainder != 0) return remainder;
        }
        return blockSize;
    }

    public byte[] infoHash = new byte[20];
    // Maps each value in infoHash into its hexadecimal representation and joins it as a string
    public String getHexStringInfoHash() {
        return IntStream.range(0, infoHash.length)
                .mapToObj(i -> String.format("%02x", infoHash[i]))
                .collect(Collectors.joining());
    }
    public String getUrlSafeInfoHash() {
        String infoHashStr = new String(infoHash, StandardCharsets.UTF_8);
        return URLEncoder.encode(infoHashStr, StandardCharsets.UTF_8);
    }

    //TODO peerlist event handler

    private Object[] fileWriteLocks;
    private static MessageDigest sha1;
    static {
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public Torrent(String name, String downloadDirectory, List<FileItem> files, List<String> trackers,
                   int pieceSize, byte[] pieceHashes, int blockSize, boolean isPrivate)
    {
        this.name = name;
        this.downloadDirectory = downloadDirectory;
        this.files = files;
        fileWriteLocks = new Object[this.files.size()];
        for (int i = 0; i < this.files.size(); i++) {
            fileWriteLocks[i] = new Object();
        }

        if (trackers != null) {
            for (String url : trackers) {
                Tracker tracker = new Tracker(url);
                this.trackers.add(tracker);
                //TODO trigger peer list event
            }
        }

        this.pieceSize = pieceSize;
        this.blockSize = blockSize;
        this.isPrivate = isPrivate;

        int count = (int) Math.ceil((double) getTotalSize() / (double) pieceSize);

        this.pieceHashes = new byte[count][];
        isPieceVerified = new boolean[count];
        isBlockAcquired = new boolean[count][];

        for (int i = 0; i < getPieceCount(); i++) {
            isBlockAcquired[i] = new boolean[getBlockCount(i)];
        }

        if (pieceHashes == null) {
            // New torrent
            for (int i = 0; i < getPieceCount(); i++) {
                this.pieceHashes[i] = GetHash(i);
            }
        } else {
            for (int i = 0; i < getPieceCount(); i++) {
                this.pieceHashes[i] = new byte[20];
                System.arraycopy(pieceHashes, i * 20, this.pieceHashes[i], 0, 20);
            }
        }

        Object info = TorrentInfoToBEncodingObj(this);
        byte[] bytes;
        try {
            bytes = BEncoding.encode(info);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        infoHash = sha1.digest(bytes);

        for (int i = 0; i < getPieceCount(); i++) {
            Verify(i);
        }
    }

    public byte[] read(long start, int length) {
        long end = start + length;
        byte[] buffer = new byte[length];

        for (int i = 0; i < files.size(); i++) {
            if ((start < files.get(i).offset &&
                   end < files.get(i).offset) ||
                (start > files.get(i).offset + files.get(i).size &&
                   end > files.get(i).offset + files.get(i).size))
                continue;

            String filePath = downloadDirectory + File.separatorChar + getFileDirectory() + files.get(i).path;

            if (!Files.exists(Path.of(filePath))) return null;

            long fileStart = Math.max(0, start - files.get(i).offset);
            long fileEnd = Math.min(end - files.get(i).offset, files.get(i).size);
            int fileLength = (int) (fileEnd - fileStart);
            int blockStart = Math.max(0, (int) (files.get(i).offset - start));

            try (RandomAccessFile file = new RandomAccessFile(filePath, "r")) {
                file.seek(fileStart);
                file.read(buffer, blockStart, fileLength);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return buffer;
    }

    public void write(long start, byte[] bytes) {
        long end = start + bytes.length;

        for (int i = 0; i < files.size(); i++) {
            if ((start < files.get(i).offset &&
                   end < files.get(i).offset) ||
                (start > files.get(i).offset + files.get(i).size &&
                   end > files.get(i).offset + files.get(i).size))
                continue;

            String filePath = downloadDirectory + File.separatorChar + getFileDirectory() + files.get(i).path;

            Path dir = Path.of(filePath).getParent();
            if (!Files.exists(dir)) {
                try {
                    Files.createDirectories(dir);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            synchronized (fileWriteLocks[i]) {
                try (RandomAccessFile file = new RandomAccessFile(filePath, "rw")) {
                    long fileStart = Math.max(0, start - files.get(i).offset);
                    long fileEnd = Math.min(end - files.get(i).offset, files.get(i).size);
                    int fileLength = (int) (fileEnd - fileStart);
                    int blockStart = Math.max(0, (int) (files.get(i).offset - start));

                    file.seek(fileStart);
                    file.write(bytes, blockStart, fileLength);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}

//TODO fix access modifiers, getters, setters
//TODO add torrent constructor overloading to support default
// values