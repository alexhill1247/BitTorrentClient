import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
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
    private EventListeners.DisconnectedListener disconnectedListener;
    private EventListeners.StateChangedListener stateChangedListener;
    private EventListeners.BlockRequestedListener blockRequestedListener;
    private EventListeners.BlockCancelledListener blockCancelledListener;
    private EventListeners.BlockReceivedListener blockReceivedListener;

    public void setDisconnectedListener(EventListeners.DisconnectedListener listener) {
        this.disconnectedListener = listener;
    }

    public void setStateChangedListener(EventListeners.StateChangedListener listener) {
        this.stateChangedListener = listener;
    }

    public void setBlockRequestedListener(EventListeners.BlockRequestedListener listener) {
        this.blockRequestedListener = listener;
    }

    public void setBlockCancelledListener(EventListeners.BlockCancelledListener listener) {
        this.blockCancelledListener = listener;
    }

    public void setBlockReceivedListener(EventListeners.BlockReceivedListener listener) {
        this.blockReceivedListener = listener;
    }

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
                    if (isHandshakeReceived) sendBitfield(torrent.isPieceVerified);
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

        if (disconnectedListener != null) {
            disconnectedListener.onDisconnected();
        }
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

                    List<Byte> subList = data.subList(0, messageLength);
                    byte[] subBytes = new byte[subList.size()];
                    int i = 0;
                    for (Byte b : subList) {
                        subBytes[i++] = b;
                    }
                    handleMessage(subBytes);

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

        public static MessageType fromValue(int value) {
            for (MessageType type : MessageType.values()) {
                if (type.getValue() == value) return type;
            }
            return unknown;
        }
    }

    //--------------------------------------------------------
    //                 ENCODING & DECODING
    //--------------------------------------------------------

    public static HandshakeResult decodeHandshake(byte[] bytes) {
        byte[] hash = new byte[20];
        String id = "";

        if (bytes.length != 68 || bytes[0] != 19) {
            System.out.println("Invalid handshake 1");
            return new HandshakeResult(false, null, null);
        }

        byte[] temp = Arrays.copyOfRange(bytes, 1, 20);
        if (!new String(temp, StandardCharsets.UTF_8).equals("BitTorrent protocol")) {
            System.out.println("Invalid handshake 2");
            return new HandshakeResult(false, null, null);
        }

        hash = Arrays.copyOfRange(bytes, 28, 48);

        temp = Arrays.copyOfRange(bytes, 48, 68);
        id = new String(temp, StandardCharsets.UTF_8);

        return new HandshakeResult(true, hash, id);
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
        // Wrap bytes to ensure order is big-endian
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (bytes.length != 4 || buffer.getInt(0) != 0) {
            System.out.println("Invalid keep-alive");
            return false;
        }
        return true;
    }

    public static byte[] encodeKeepAlive() {
        ByteBuffer buffer = ByteBuffer.allocate(4);
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
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
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
        ByteBuffer buffer = ByteBuffer.allocate(4).putInt(1);
        System.arraycopy(buffer.array(), 0, message, 0, 4);
        message[4] = (byte) type.getValue();
        return message;
    }

    public static int decodeHave(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (bytes.length != 9 || buffer.getInt(0) != 5) {
            System.out.println("Invalid have");
            return -1;
        }
        return buffer.getInt(5);
    }

    public static boolean decodeBitfield(byte[] bytes, int pieces, boolean[] isPieceDownloaded) {

        isPieceDownloaded = new boolean[pieces];

        int expectedLength = (int) Math.ceil(pieces / 8.0) + 1;
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
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

        ByteBuffer buffer = ByteBuffer.allocate(4).putInt(5);
        System.arraycopy(buffer.array(), 0, message, 0, 4);
        message[4] = (byte) MessageType.have.getValue();
        buffer.clear().putInt(index);
        System.arraycopy(buffer.array(), 0, message, 5, 4);

        return message;
    }

    public static byte[] encodeBitfield(Boolean[] isPieceDownloaded) {
        int numPieces = isPieceDownloaded.length;
        int numBytes = (int) Math.ceil(numPieces / 8.0);
        int numBits = numBytes * 8;

        int length = numBytes + 1;

        byte[] message = new byte[length + 4];

        ByteBuffer buffer = ByteBuffer.allocate(4);
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

    // Used for request and cancel message
    public static RequestResult decodeRequest(byte[] bytes) {
        int index = -1;
        int begin = -1;
        int length = -1;

        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        if (bytes.length != 17 || buffer.getInt(0) != 13) {
            System.out.println("Invalid request/cancel");
            return new RequestResult(false, index, begin, length);
        }

        index = buffer.getInt(5);
        begin = buffer.getInt(9);
        length = buffer.getInt(13);

        return new RequestResult(true, index, begin, length);
    }

    public static PieceResult decodePiece(byte[] bytes) {
        int index = -1;
        int begin = -1;
        byte[] data = new byte[0];

        if (bytes.length < 13) {
            System.out.println("Invalid piece");
            return new PieceResult(false, index, begin, data);
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        index = buffer.getInt(5);
        begin = buffer.getInt(9);
        int length = buffer.getInt(0) - 9;
        data = new byte[length];
        System.arraycopy(bytes, 13, data, 0, length);

        return new PieceResult(true, index, begin, data);
    }

    public static byte[] encodeRequest(MessageType type, int index, int begin, int length) {
        byte[] message = new byte[17];

        ByteBuffer buffer = ByteBuffer.allocate(4);

        buffer.putInt(13);
        System.arraycopy(buffer.array(), 0, message, 0, 4);
        message[4] = (byte) type.getValue();
        buffer.clear().putInt(index);
        System.arraycopy(buffer.array(), 0, message, 5, 4);
        buffer.clear().putInt(begin);
        System.arraycopy(buffer.array(), 0, message, 9, 4);
        buffer.clear().putInt(length);
        System.arraycopy(buffer.array(), 0, message, 13, 4);

        return message;
    }

    public static byte[] encodePiece(int index, int begin, byte[] data) {
        int length = data.length + 9;

        byte[] message = new byte[length + 4];
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(length);
        System.arraycopy(buffer.array(), 0, message, 0, 4);
        message[4] = (byte) MessageType.piece.getValue();
        buffer.clear().putInt(index);
        System.arraycopy(buffer.array(), 0, message, 5, 4);
        buffer.clear().putInt(begin);
        System.arraycopy(buffer.array(), 0, message, 9, 4);
        System.arraycopy(data, 0, message, 13, data.length);

        return message;
    }

    //-----------------------------------------------------
    //                 SENDING MESSAGES
    //-----------------------------------------------------

    private void sendHandshake() {
        if (isHandshakeSent) return;

        System.out.println(this + "-> handshake");
        sendBytes(encodeHandshake(torrent.infoHash, localID));
        isHandshakeSent = true;
    }

    public void sendKeepAlive() {
        if (lastKeepAlive.isAfter(ZonedDateTime.now().minusSeconds(30))) return;

        System.out.println(this + "-> keep alive");
        sendBytes(encodeKeepAlive());
        lastKeepAlive = ZonedDateTime.now();
    }

    public void sendChoke() {
        if (isChokeSent) return;

        System.out.println(this + "-> choke");
        sendBytes(encodeChoke());
        isChokeSent = true;
    }

    public void sendUnchoke() {
        if (!isChokeSent) return;

        System.out.println(this + "-> unchoke");
        sendBytes(encodeUnchoke());
        isChokeSent = false;
    }

    public void sendInterested() {
        if (isInterestedSent) return;

        System.out.println(this + "-> interested");
        sendBytes(encodeInterested());
        isInterestedSent = true;
    }

    public void sendNotInterested() {
        if (!isInterestedSent) return;

        System.out.println(this + "-> not interested");
        sendBytes(encodeNotInterested());
        isInterestedSent = false;
    }

    public void sendHave(int index) {
        System.out.println(this + "-> have " + index);
        sendBytes(encodeHave(index));
    }

    public void sendBitfield(Boolean[] isPieceDownloaded) {
        System.out.println(this + "-> bitfield" +
                Arrays.stream(isPieceDownloaded)
                        .map(b -> b ? "1" : "0")
                        .collect(Collectors.joining())
        );
        sendBytes(encodeBitfield(isPieceDownloaded));
    }

    public void sendRequest(MessageType type, int index, int begin, int length) {
        System.out.println(this + "-> " + type.toString() + " " + index + ", " + begin + ", " + length);
        sendBytes(encodeRequest(type, index, begin, length));
    }

    public void sendPiece(int index, int begin, byte[] data) {
        System.out.println(this + "-> piece " + index + ", " + begin + ", " + data.length);
        sendBytes(encodePiece(index, begin, data));
        uploaded += data.length;
    }

    //--------------------------------------------------
    //               RECEIVING MESSAGES
    //--------------------------------------------------

    private MessageType getMessageType(byte[] bytes) {
        if (isHandshakeReceived) return MessageType.handshake;

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (bytes.length == 4 && buffer.getInt(0) == 0) {
            return MessageType.keepAlive;
        }

        if (bytes.length > 4) return MessageType.fromValue(bytes[4]);

        return MessageType.unknown;
    }

    private void handleMessage(byte[] bytes) {
        lastActive = ZonedDateTime.now();

        MessageType type = getMessageType(bytes);

        if (type == MessageType.unknown) return;

        else if (type == MessageType.handshake) {
            HandshakeResult result = decodeHandshake(bytes);
            if (result.success) {
                handleHandshake(result.hash, result.id);
                return;
            }
        }
        else if (type == MessageType.keepAlive && decodeKeepAlive(bytes)) {
            handleKeepAlive();
            return;
        }
        else if (type == MessageType.choke && decodeChoke(bytes)) {
            handleChoke();
            return;
        }
        else if (type == MessageType.unchoke && decodeUnchoke(bytes)) {
            handleUnchoke();
            return;
        }
        else if (type == MessageType.interested && decodeInterested(bytes)) {
            handleInterested();
            return;
        }
        else if (type == MessageType.notInterested && decodeNotInterested(bytes)) {
            handleNotInterested();
            return;
        }
        else if (type == MessageType.have) {
            int index = decodeHave(bytes);
            if (index != -1) {
                handleHave(index);
                return;
            }
        }
        else if (type == MessageType.bitfield) {
            boolean[] isPieceDownloaded = new boolean[0];
            if (decodeBitfield(bytes, this.isPieceDownloaded.length, isPieceDownloaded)) {
                handleBitfield(isPieceDownloaded);
                return;
            }
        }
        else if (type == MessageType.request || type == MessageType.cancel) {
            RequestResult result = decodeRequest(bytes);
            if (result.success) {
                if (type == MessageType.request) handleRequest(result.index, result.begin, result.length);
                if (type == MessageType.cancel)  handleCancel (result.index, result.begin, result.length);
                return;
            }
        }
        else if (type == MessageType.piece) {
            PieceResult result = decodePiece(bytes);
            if (result.success) {
                handlePiece(result.index, result.begin, result.data);
                return;
            }
        }
        else if (type == MessageType.port) {
            System.out.println(this + " <- port: " + bytesToHexString(bytes));
            return;
        }

        System.out.println(this + " unhandled incoming message " + bytesToHexString(bytes));
        disconnect();
    }

    private String bytesToHexString(byte[] bytes) {
        return IntStream.range(0, bytes.length)
                .mapToObj(i -> String.format("%02x", bytes[i]))
                .collect(Collectors.joining());
    }

    private void handleHandshake(byte[] hash, String id) {
        System.out.println(this + "<- handshake");

        if (!Arrays.equals(torrent.infoHash, hash)) {
            System.out.println("Invalid handshake, expected hash = " + bytesToHexString(torrent.infoHash) +
                    ", received = " + bytesToHexString(hash));
            disconnect();
            return;
        }

        this.id = id;
        isHandshakeReceived = true;
        sendBitfield(torrent.isPieceVerified);
    }

    //TODO just put this above?
    private void handleKeepAlive() {
        System.out.println(this + "<- keep alive");
    }

    //TODO why does this exist?
    private void handlePort(int port) {
        System.out.println(this + "<- port " + port);
    }

    private void handleChoke() {
        System.out.println(this + "<- choke");
        isChokeReceived = true;

        if (stateChangedListener != null) {
            stateChangedListener.onStateChanged();
        }
    }

    private void handleUnchoke() {
        System.out.println(this + "<- unchoke");
        isChokeReceived = false;

        if (stateChangedListener != null) {
            stateChangedListener.onStateChanged();
        }
    }

    private void handleInterested() {
        System.out.println(this + "<- interested");
        isInterestedReceived = true;

        if (stateChangedListener != null) {
            stateChangedListener.onStateChanged();
        }
    }

    private void handleNotInterested() {
        System.out.println(this + "<- not interested");
        isInterestedReceived = false;

        if (stateChangedListener != null) {
            stateChangedListener.onStateChanged();
        }
    }

    private void handleHave(int index) {
        isPieceDownloaded[index] = true;
        System.out.println(this + "<- have " + index + " - " + getPiecesDownloadedCount() +
                " available (" + getPiecesDownloaded() + ")"
        );

        if (stateChangedListener != null) {
            stateChangedListener.onStateChanged();
        }
    }

    private void handleBitfield(boolean[] isPieceDownloaded) {
        for (int i = 0; i < torrent.getPieceCount(); i++) {
            // Keep true if already true, otherwise set to passed param
            this.isPieceDownloaded[i] = this.isPieceDownloaded[i] || isPieceDownloaded[i];
        }

        System.out.println(this + "<- bitfield " + getPiecesDownloadedCount() +
                " available (" + getPiecesDownloaded() + ")"
        );

        if (stateChangedListener != null) {
            stateChangedListener.onStateChanged();
        }
    }

    private void handleRequest(int index, int begin, int length) {
        System.out.println(this + "<- request " + index + ", " + begin + ", " + length);

        if (blockRequestedListener != null) {
            blockRequestedListener.onBlockRequested(new DataRequest(this, index, begin, length));
        }
    }

    private void handlePiece(int index, int begin, byte[] data) {
        System.out.println(this + "<- piece " + index + ", " + begin + ", " + data.length);
        downloaded += data.length;

        if (blockReceivedListener != null) {
            blockReceivedListener.onBlockReceived(new DataPackage(this, index, begin / torrent.blockSize, data));
        }
    }

    private void handleCancel(int index, int begin, int length) {
        System.out.println(this + "<- cancel");

        if (blockCancelledListener != null) {
            blockCancelledListener.onBlockCancelled(new DataRequest(this, index, begin, length));
        }
    }
}