import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Torrent {
    public String name;
    public Boolean isPrivate;
    public List<FileItem> files;
    public String getFileDirectory() { return (files.size() > 1 ? name + File.separatorChar : ""); }
    public String downloadDirectory;

    public List<Tracker> trackers = new ArrayList<>();
    public String comment;
    public String createdBy;
    public ZonedDateTime creationDate;
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
    public Boolean[] isPieceVerified;
    public Boolean[][] isBlockAcquired;

    public String getVerifiedPiecesStr() {
        return Arrays.stream(isPieceVerified).map(b -> b ? "1" : "0")
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

    public byte[] infoHash;

    public String getUrlSafeInfoHash() {
        StringBuilder sb = new StringBuilder();
        Pattern pattern = Pattern.compile("[0-9a-zA-Z.\\-_~]");
        for (int i = 0; i < infoHash.length; i++) {
            char ch = (char) infoHash[i];
            if (pattern.matcher(String.valueOf(ch)).find()) {
                sb.append(ch);
            } else {
                sb.append("%");
                sb.append(String.format("%02x", infoHash[i]));
            }
        }
        return sb.toString();
    }

    private EventListeners.PeerListUpdatedListener peerListUpdatedListener;

    public void setPeerListUpdatedListener(EventListeners.PeerListUpdatedListener listener) {
        this.peerListUpdatedListener = listener;
    }

    private final Object[] fileWriteLocks;
    private static final MessageDigest sha1;
    static {
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public Torrent(String name, String downloadDirectory, List<FileItem> files, List<String> trackers,
                   int pieceSize, byte[] pieceHashes, int blockSize, Boolean isPrivate)
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
                tracker.setPeerListUpdatedListener(this::handlePeerListUpdated);
            }
        }

        this.pieceSize = pieceSize;
        this.blockSize = blockSize;
        this.isPrivate = isPrivate;

        int count = (int) Math.ceil((double) getTotalSize() / (double) pieceSize);

        this.pieceHashes = new byte[count][];
        isPieceVerified = new Boolean[count];
        isBlockAcquired = new Boolean[count][];

        for (int i = 0; i < getPieceCount(); i++) {
            isBlockAcquired[i] = new Boolean[getBlockCount(i)];
            Arrays.fill(isBlockAcquired[i], false);
        }

        if (pieceHashes == null) {
            // New torrent
            for (int i = 0; i < getPieceCount(); i++) {
                this.pieceHashes[i] = getHash(i);
            }
        } else {
            for (int i = 0; i < getPieceCount(); i++) {
                this.pieceHashes[i] = new byte[20];
                System.arraycopy(pieceHashes, i * 20, this.pieceHashes[i], 0, 20);
            }
        }

        Object info = torrentInfoToBEncodingObj(this);
        byte[] bytes;
        try {
            bytes = BEncoding.encode(info);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        infoHash = sha1.digest(bytes);

        for (int i = 0; i < getPieceCount(); i++) {
            verify(i);
        }
    }

    private void handlePeerListUpdated(Object sender, List<InetSocketAddress> endPoints) {
        if (peerListUpdatedListener != null) {
            peerListUpdatedListener.onPeerListUpdated(sender, endPoints);
        }
    }

    //------------------------------------------------------
    //                 READING / WRITING
    //------------------------------------------------------

    public byte[] read(long start, int length) {
        long end = start + length;
        byte[] buffer = new byte[length];

        for (int i = 0; i < files.size(); i++) {
            if ((start < files.get(i).offset && end < files.get(i).offset) ||
                (start > files.get(i).offset + files.get(i).size && end > files.get(i).offset + files.get(i).size))
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
            if ((start < files.get(i).offset && end < files.get(i).offset) ||
                (start > files.get(i).offset + files.get(i).size && end > files.get(i).offset + files.get(i).size))
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

    public byte[] readPiece(int piece) {
        return read((long) piece * pieceSize, getPieceSize(piece));
    }

    public byte[] readBlock(int piece, int offset, int length) {
        return read((long) piece * pieceSize + offset, length);
    }

    public void writeBlock(int piece, int block, byte[] bytes) {
        write((long) piece * pieceSize + (long) block * blockSize, bytes);
        isBlockAcquired[piece][block] = true;
        verify(piece);
    }

    //-----------------------------------------------------
    //                     VERIFYING
    //-----------------------------------------------------

    private EventListeners.PieceVerifiedListener pieceVerifiedListener;

    public void setPieceVerifiedListener(EventListeners.PieceVerifiedListener listener) {
        pieceVerifiedListener = listener;
    }

    public void verify(int piece) {
        byte[] hash = getHash(piece);

        boolean isVerified = (hash != null && Arrays.equals(hash, pieceHashes[piece]));

        if (isVerified) {
            isPieceVerified[piece] = true;

            Arrays.fill(isBlockAcquired[piece], true);

            if (pieceVerifiedListener != null) {
                pieceVerifiedListener.onPieceVerified(piece);
            }

            return;
        }

        isPieceVerified[piece] = false;

        if (Arrays.stream(isBlockAcquired[piece]).allMatch(x -> x != null && x)) {
            Arrays.fill(isBlockAcquired[piece], false);
        }
    }

    public byte[] getHash(int piece) {
        byte[] data = readPiece(piece);

        if (data == null) return null;
        return sha1.digest(data);
    }

    //----------------------------------------------------
    //             IMPORTING AND EXPORTING
    //----------------------------------------------------

    public static Torrent loadFromFile(String filePath, String downloadPath) {
        Object obj = BEncoding.decodeFile(filePath);
        Path path = Path.of(filePath);
        String name = path.getFileName().toString();
        int ext = name.lastIndexOf(".");
        String nameWithoutExt = name.substring(0, ext);

        return BEncodingObjToTorrent(obj, name, downloadPath);
    }

    public static void saveToFile(Torrent torrent) {
        Object obj = torrentToBEncodingObj(torrent);

        BEncoding.encodeFile(obj, torrent.name + ".torrent");
    }

    public static long zonedDateTimeToUnixTimestamp(ZonedDateTime time) {
        return time.toEpochSecond();
    }

    private static Object torrentToBEncodingObj(Torrent torrent) {
        HashMap<String, Object> dict = new HashMap<>();

        if (torrent.trackers.size() == 1) {
            dict.put("announce", torrent.trackers.get(0).address.getBytes(StandardCharsets.UTF_8));
        } else {
            dict.put("announce", torrent.trackers.stream().map(
                    x -> x.address.getBytes(StandardCharsets.UTF_8))
                    .toList());
        }
        dict.put("comment", torrent.comment.getBytes(StandardCharsets.UTF_8));
        dict.put("created by", torrent.createdBy.getBytes(StandardCharsets.UTF_8));
        dict.put("creation date", zonedDateTimeToUnixTimestamp(torrent.creationDate));
        dict.put("encoding", "UTF-8".getBytes(StandardCharsets.UTF_8));
        dict.put("info", torrentInfoToBEncodingObj(torrent));

        return dict;
    }

    private static Object torrentInfoToBEncodingObj(Torrent torrent) {
        HashMap<String, Object> dict = new HashMap<>();

        dict.put("piece length", (long) torrent.pieceSize);
        byte[] pieces = new byte[20 * torrent.getPieceCount()];
        for (int i = 0; i < torrent.getPieceCount(); i++) {
            System.arraycopy(torrent.pieceHashes[i], 0, pieces, i * 20, 20);
        }
        dict.put("pieces", pieces);

        if (torrent.isPrivate != null) {
            dict.put("private", torrent.isPrivate ? 1L : 0L);
        }

        if (torrent.files.size() == 1) {
            dict.put("name", torrent.files.get(0).path.getBytes(StandardCharsets.UTF_8));
            dict.put("length", torrent.files.get(0).size);
        } else {
            List<Object> files = new ArrayList<>();
            for (FileItem fileItem : torrent.files) {
                HashMap<String, Object> fileDict = new HashMap<>();
                // Store path as a list
                fileDict.put("path", Arrays.stream(fileItem.path
                        .split(Pattern.quote(String.valueOf(File.separatorChar))))
                        .map(x -> x.getBytes(StandardCharsets.UTF_8))
                        .toList()
                );
                fileDict.put("length", fileItem.size);
                files.add(fileDict);
            }

            dict.put("files", files);
            // Remove trailing file separator
            dict.put("name", torrent.getFileDirectory()
                    .substring(0, torrent.getFileDirectory().length() - 1)
                    .getBytes(StandardCharsets.UTF_8)
            );
        }

        return dict;
    }

    public static String decodeUTF8Str(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static ZonedDateTime unixTimestampToZonedDateTime(long unixTimestamp) {
        Instant instant = Instant.ofEpochSecond(unixTimestamp);
        return instant.atZone(ZoneId.systemDefault());
    }

    @SuppressWarnings("unchecked")
    public static Torrent BEncodingObjToTorrent(Object bencoding, String name, String downloadPath) {
        TreeMap<String, Object> obj = (TreeMap<String, Object>) bencoding;
        if (obj == null) throw new RuntimeException("Not a torrent file");

        List<String> trackers = new ArrayList<>();
        if (obj.containsKey("announce")) trackers.add(decodeUTF8Str((byte[]) obj.get("announce")));

        if (!obj.containsKey("info")) throw new RuntimeException("Missing torrent info");
        TreeMap<String, Object> info = (TreeMap<String, Object>) obj.get("info");
        if (info == null) throw new RuntimeException("Error with torrent info");

        List<FileItem> files = new ArrayList<>();
        if (info.containsKey("name") && info.containsKey("length")) {
            files.add(new FileItem(
                    new String((byte[]) info.get("name"), StandardCharsets.UTF_8),
                    (long) info.get("length")
            ));
        } else if (info.containsKey("files")) {
            long running = 0;

            for (Object item : (List<Object>) info.get("files")) {
                TreeMap<String, Object> dict = (TreeMap<String, Object>) item;

                if (dict == null || !dict.containsKey("path") || !dict.containsKey("length"))
                    throw new RuntimeException("Incorrect file specification");

                List<Object> pathList = (List<Object>) dict.get("path");

                String path = String.join(File.separator, pathList
                        .stream()
                        .map(x -> decodeUTF8Str((byte[]) x))
                        .collect(Collectors.joining())
                );

                long size = (long) dict.get("length");

                files.add(new FileItem(path, size, running));

                running += size;
            }
        } else {
            throw new RuntimeException("No files in torrent");
        }

        if (!info.containsKey("piece length")) throw new RuntimeException("Error with piece length");
        Long temp = (Long) info.get("piece length");
        int pieceSize = temp.intValue();

        if (!info.containsKey("pieces")) throw new RuntimeException("Error with pieces");
        byte[] pieceHashes = (byte[]) info.get("pieces");

        Boolean isPrivate = null;
        if (info.containsKey("private"))
            isPrivate = (long) info.get("private") == 1L;

        Torrent torrent = new Torrent(
                name,
                downloadPath,
                files,
                trackers,
                pieceSize,
                pieceHashes,
                16384,
                isPrivate
        );

        if (obj.containsKey("comment"))
            torrent.comment = decodeUTF8Str((byte[]) obj.get("comment"));

        if (obj.containsKey("created by"))
            torrent.createdBy = decodeUTF8Str((byte[]) obj.get("created by"));

        if (obj.containsKey("creation date"))
            torrent.creationDate = unixTimestampToZonedDateTime((long) obj.get("creation date"));

        if (obj.containsKey("encoding"))
            torrent.encoding = Charset.forName(decodeUTF8Str((byte[]) obj.get("encoding")));

        return torrent;
    }

    public void updateTrackers(Tracker.TrackerEvent ev, String id, int port) {
        for (Tracker tracker : trackers) {
            //System.out.println("updating tracker: " + tracker.address);
            tracker.update(this, ev, id, port);
        }
    }

    public void resetTrackersLastRequest() {
        for (Tracker tracker : trackers) {
            tracker.resetLastRequest();
        }
    }
}