import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
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

    private ReentrantLock[] fileWriteLocks;
    private static MessageDigest md;
    static {
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    //TODO constructor
}

//TODO fix access modifiers