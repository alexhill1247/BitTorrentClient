import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Peer {
    //TODO event handlers

    public String localID;
    public String id;

    public Torrent torrent;

    public InetSocketAddress inetSocketAddress;
    public String key;
    public String getKey() {
        return inetSocketAddress.toString();
    }

    private AsynchronousSocketChannel client;
    private final int bufferSize = 256;
    private ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
    private ArrayList<Byte> data = new ArrayList<>();

    public Boolean[] isPieceDownloaded = new Boolean[0];
    public String getPiecesDownloaded() {
        return Arrays.stream(isPieceDownloaded)
                .map(x -> Integer.toString(x ? 1 : 0))
                .collect(Collectors.joining(""));
    }
    public int getPiecesRequiredAvailable() {
        return (int) IntStream.range(0, isPieceDownloaded.length)
                .filter(i -> isPieceDownloaded[i] && !torrent.isPieceVerified[i])
                .count();
    }
    public int getPiecesDownloadedCount() {
        return (int) Arrays.stream(isPieceDownloaded)
                .filter(x -> x)
                .count();
    }
    public boolean isCompleted() {
        return getPiecesDownloadedCount() == torrent.getPieceCount();
    }

    public boolean isDisconnected;

    public boolean isHandshakeSent;
    public boolean isPositionSent;
    public boolean isChokeSent = true;
    public boolean isInterestedSent = false;

    public boolean isHandshakeReceived;
    public boolean isChokeReceived = true;
    public boolean isInterestedReceived = false;

    public Boolean[][] isBlockRequested = new Boolean[0][];
    public int getBlocksRequested() {
        return Arrays.stream(isBlockRequested)
                .mapToInt(x -> (int) Arrays.stream(x)
                        .filter(y -> y)
                        .count())
                .sum();
    }

    public ZonedDateTime lastActive;
    public ZonedDateTime lastKeepAlive = ZonedDateTime.from(Instant.EPOCH);

    public long uploaded;
    public long downloaded;

    public Peer(Torrent torrent, String localID, AsynchronousSocketChannel client) {
        this.torrent = torrent;
        this.localID = localID;
        this.client = client;
        try {
            inetSocketAddress = (InetSocketAddress) client.getRemoteAddress();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Peer(Torrent torrent, String localID, InetSocketAddress endPoint) {
        this.torrent = torrent;
        this.localID = localID;
        inetSocketAddress = endPoint;
    }

    private Peer(Torrent torrent, String localID) {
        this.torrent = torrent;
        this.localID = localID;

        lastActive = ZonedDateTime.now();
        int pieceCount = torrent.getPieceCount();
        isPieceDownloaded = new Boolean[pieceCount];
        isBlockRequested = new Boolean[pieceCount][];
        for (int i = 0; i < pieceCount; i++) {
            isBlockRequested[i] = new Boolean[torrent.getBlockCount(i)];
        }
    }

    public void connect() {
        try {
            client = AsynchronousSocketChannel.open();
            client.connect(inetSocketAddress, null, new CompletionHandler<Void, Void>() {
                @Override
                public void completed(Void result, Void attachment) {
                    read();
                    sendHandshake();
                    if (isHandshakeReceived) sendBitfield();
                }
                @Override
                public void failed(Throwable exc, Void attachment) {
                    disconnect();
                }
            });
        } catch (Exception e) {
            disconnect();
        }
    }

    public void disconnect() {
        if (!isDisconnected) {
            isDisconnected = true;
            System.out.println(this + " disconnected, down " + downloaded + ", up " + uploaded);
        }

        try {
            if (client != null && client.isOpen()) {
                client.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        //TODO disconnected event
    }

    private void sendBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        try {
            client.write(buffer);
        } catch (Exception e) {
            disconnect();
        }
    }

    private void read() {
        client.read(buffer, buffer, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer result, ByteBuffer attachment) {
                // Set buffer to read
                attachment.flip();
                // Move bytes from buffer into array
                byte[] bytes = new byte[attachment.limit()];
                attachment.get(bytes);
                // Put into data list
                for (byte b : bytes) {
                    data.add(b);
                }
                attachment.clear();

                int messageLength = getMessageLength(data);
                while (data.size() >= messageLength) {
                    handleMessage(data.subList(0, messageLength).toArray(new Byte[0]));
                    data = (ArrayList<Byte>) data.subList(messageLength, data.size());
                    messageLength = getMessageLength(data);
                }
                read();
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                disconnect();
            }
        });
    }

    private int getMessageLength(List<Byte> data) {
        if (!isHandshakeReceived) return 68;
        if (data.size() < 4) return Integer.MAX_VALUE;

        return ByteBuffer.wrap(new byte[] {
                data.get(0),
                data.get(1),
                data.get(2),
                data.get(3),
        }).getInt() + 4;
    }

    public enum MessageType {
        unknown(-3),
        handshake(-2),
        keepAlive(-1),
        choke(0),
        unchoke(1),
        interested(2),
        notInterested(3),
        have(4),
        bitfield(5),
        request(6),
        piece(7),
        cancel(8),
        port(9);

        private final int value;

        MessageType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    //TODO potential issue with indices
    public static Handshake decodeHandshake(byte[] bytes) {
        byte[] hash = new byte[20];
        String id = "";

        if (bytes.length != 68 || bytes[0] != 19) {
            System.out.println("Invalid handshake 1");
            return new Handshake(false, null, null);
        }

        byte[] temp = Arrays.copyOfRange(bytes, 1, 20);
        if (!new String(temp, StandardCharsets.UTF_8).equals("BitTorrent protocol")) {
            System.out.println("Invalid handshake 2");
            return new Handshake(false, null, null);
        }

        hash = Arrays.copyOfRange(bytes, 28, 48);

        temp = Arrays.copyOfRange(bytes, 48, 68);
        id = new String(temp, StandardCharsets.UTF_8);

        return new Handshake(true, hash, id);
    }

    public static byte[] encodeHandshake(byte[] hash, String id) {
        byte[] message = new byte[68];
        message[0] = 19;
        System.arraycopy("BitTorrent protocol".getBytes(StandardCharsets.UTF_8),
                0, message,
                1, 19);
        System.arraycopy(hash,
                0, message,
                28, 20);
        System.arraycopy(id.getBytes(StandardCharsets.UTF_8),
                0, message,
                48, 20);

        return message;
    }

    public static boolean decodeKeepAlive(byte[] bytes) {
        // Ensure byte order is big endian
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (bytes.length != 4 || buffer.getInt(0) != 0) {
            System.out.println("Invalid keep-alive");
            return false;
        }
        return true;
    }

    public static byte[] encodeKeepAlive() {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        return buffer.putInt(0).array();
    }

    public static boolean decodeChoke(byte[] bytes) {
        return decodeState(bytes, MessageType.choke);
    }

    public static boolean decodeUnchoke(byte[] bytes) {
        return decodeState(bytes, MessageType.unchoke);
    }

    public static boolean decodeInterested(byte[] bytes) {
        return decodeState(bytes, MessageType.interested);
    }

    public static boolean decodeNotInterested(byte[] bytes) {
        return decodeState(bytes, MessageType.notInterested);
    }

    public static boolean decodeState(byte[] bytes, MessageType type) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (bytes.length != 5 || buffer.getInt(0) != 1 || bytes[4] != (byte) type.getValue()) {
            System.out.println("Invalid" + type.toString());
            return false;
        }
        return true;
    }

    public static byte[] encodeChoke() {
        return encodeState(MessageType.choke);
    }

    public static byte[] encodeUnchoke() {
        return encodeState(MessageType.unchoke);
    }

    public static byte[] encodeInterested() {
        return encodeState(MessageType.interested);
    }

    public static byte[] encodeNotInterested() {
        return encodeState(MessageType.notInterested);
    }

    public static byte[] encodeState(MessageType type) {
        byte[] message = new byte[5];
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1);
        System.arraycopy(buffer.array(), 0, message, 0, 4);
        message[4] = (byte) type.getValue();
        return message;
    }

    public static int decodeHave(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (bytes.length != 9 || buffer.getInt(0) != 5) {
            System.out.println("Invalid have");
            return -1;
        }
        return buffer.getInt(5);
    }

    public static boolean decodeBitfield(byte[] bytes, int pieces, boolean[] isPieceDownloaded) {

        int expectedLength = (int) Math.ceil(pieces / 8.0) + 1;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (bytes.length != expectedLength + 4 || buffer.getInt(0) != expectedLength) {
            System.out.println("Invalid bitfield, first byte not " + expectedLength);
            return false;
        }

        BitSet bitfield = BitSet.valueOf((Arrays.copyOfRange(bytes, 5, bytes.length)));
        for (int i = 0; i < pieces; i++) {
            isPieceDownloaded[i] = bitfield.get(bitfield.length() - 1 - i);
        }

        return true;
    }

    public static byte[] encodeHave(int index) {
        byte[] message = new byte[9];

        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(5);
        System.arraycopy(buffer.array(), 0, message, 0, 4);
        message[4] = (byte) MessageType.have.getValue();
        buffer.clear().putInt(index);
        System.arraycopy(buffer.array(), 0, message, 5, 4);

        return message;
    }

    public static byte[] encodeBitfield(boolean[] isPieceDownloaded) {
        int numPieces = isPieceDownloaded.length;
        int numBytes = (int) Math.ceil(numPieces / 8.0);
        int numBits = numBytes * 8;

        int length = numBytes + 1;

        byte[] message = new byte[length + 4];

        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(length);
        System.arraycopy(buffer.array(), 0, message, 0, 4);
        message[4] = (byte) MessageType.bitfield.getValue();

        BitSet bitfield = new BitSet(numBits);
        for (int i = 0; i < numPieces; i++) {
            bitfield.set(i, isPieceDownloaded[i]);
        }

        BitSet reversed = new BitSet(numBits);
        for (int i = 0; i < numBits; i++) {
            reversed.set(i, bitfield.get(numBits - i - 1));
        }

        System.arraycopy(reversed.toByteArray(), 0, message, 5, reversed.length());

        return message;
    }

}