import java.io.IOException;
import java.net.*;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

public class Client {
    public int port;
    public Torrent torrent;
    public String id;

    private Random random = new Random();

    public Client(int port, String torrentPath, String downloadPath) {
        id = "";
        for (int i = 0; i < 20; i++) {
            id += random.nextInt(10);
        }

        this.port = port;

        torrent = Torrent.loadFromFile(torrentPath, downloadPath);
        torrent.setPieceVerifiedListener(this::handlePieceVerified);
        torrent.setPeerListUpdatedListener(this::handlePeerListUpdated);

        System.out.println(torrent);
    }

    //----------------------------------
    //             Threads
    //----------------------------------

    private boolean isStopping;
    private AtomicBoolean isProcessingPeers = new AtomicBoolean(false);
    private AtomicBoolean isProcessingUploads = new AtomicBoolean(false);
    private AtomicBoolean isProcessingDownloads = new AtomicBoolean(false);

    public void start() {
        System.out.println("Starting client");

        isStopping = false;

        torrent.resetTrackersLastRequest();

        enablePeerConnections();

        // tracker
        new Thread(() -> {
            while (!isStopping) {
                torrent.updateTrackers(Tracker.TrackerEvent.started, id, port);
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();

        // peer
        new Thread(() -> {
            while (!isStopping) {
                processPeers();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();

        // upload
        new Thread(() -> {
            while (!isStopping) {
                processUploads();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();

        // download
        new Thread(() -> {
            while (!isStopping) {
                processDownloads();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    public void stop() {
        System.out.println("Stopping client");

        isStopping = true;
        disablePeerConnections();
        torrent.updateTrackers(Tracker.TrackerEvent.stopped, id, port);
    }

    public ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();
    public ConcurrentHashMap<String, Peer> seeders = new ConcurrentHashMap<>();
    public ConcurrentHashMap<String, Peer> leechers = new ConcurrentHashMap<>();

    private static InetAddress getLocalIPAddress() throws UnknownHostException, SocketException {
        InetAddress ip = null;
        Enumeration<NetworkInterface> netInterfaces = NetworkInterface.getNetworkInterfaces();

        while (netInterfaces.hasMoreElements()) {
            NetworkInterface netInterface = netInterfaces.nextElement();
            Enumeration<InetAddress> addresses = netInterface.getInetAddresses();

            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address.isSiteLocalAddress() && !address.isLoopbackAddress()) {
                    ip = address;
                    break;
                }
            }

            if (ip != null) {
                break;
            }
        }

        if (ip == null) {
            throw new UnknownHostException("Local IP address not found");
        }

        return ip;
    }

    private void handlePeerListUpdated(Object sender, List<InetSocketAddress> endPoints) {
        InetAddress localIP;
        try {
            localIP = getLocalIPAddress();
        } catch (UnknownHostException | SocketException e) {
            throw new RuntimeException(e);
        }

        for (var endPoint : endPoints) {
            if (endPoint.getAddress() == localIP && endPoint.getPort() == port) continue;

            addPeer(new Peer(torrent, id, endPoint));
        }

        System.out.println("Received peer info from " + sender);
        System.out.println("Peer count: " + peers.size());
    }

    private AsynchronousServerSocketChannel serverSocketChannel;

    public void enablePeerConnections() {
        try {
            serverSocketChannel = AsynchronousServerSocketChannel.open().bind(new InetSocketAddress(port));
            System.out.println("Listening on port " + port);
            acceptConnections();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void acceptConnections() {
        serverSocketChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {

            @Override
            public void completed(AsynchronousSocketChannel result, Void attachment) {
                handleNewConnection(result);
                serverSocketChannel.accept(null, this);
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                exc.printStackTrace();
            }
        });
    }

    private void handleNewConnection(AsynchronousSocketChannel clientSocketChannel) {
        addPeer(new Peer(torrent, id, clientSocketChannel));
    }

    private void disablePeerConnections() {
        try {
            serverSocketChannel.close();
            serverSocketChannel = null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        for (var peer : peers.values()) {
            peer.disconnect();
        }

        System.out.println("Stopped listening on port " + port);
    }

    private void addPeer(Peer peer) {
        peer.setBlockRequestedListener(this::handleBlockRequested);
        peer.setBlockCancelledListener(this::handleBlockCancelled);
        peer.setBlockReceivedListener(this::handleBlockReceived);
        peer.setDisconnectedListener(this::handlePeerDisconnected);
        peer.setStateChangedListener(this::handlePeerStateChanged);

        peer.connect();

        // Disconnect peer if it is already in the list
        if (peers.putIfAbsent(peer.getKey(), peer) != null) peer.disconnect();
    }

    private void handlePeerDisconnected(Peer peer) {
        peer.removeListeners();

        peers.remove(peer.getKey());
        seeders.remove(peer.getKey());
        leechers.remove(peer.getKey());
    }

    private void handlePeerStateChanged() {
        processPeers();
    }

    private void handlePieceVerified(int index) {
        processPeers();

        for (var peer : peers.values()) {
            if (!peer.isHandshakeReceived || !peer.isHandshakeSent) continue;

            peer.sendHave(index);
        }
    }

    //TODO include these in setting menu
    private final int maxLeechers = 5;
    private final int maxSeeders = 5;
    private final int maxUploadBytesPerSec = 16384;
    private final int maxDownloadBytesPerSec = 16384;

    private final Duration peerTimeout = Duration.ofSeconds(30);

    private void processPeers() {
        if (!isProcessingPeers.compareAndSet(false, true)) return;

        peers.values().stream()
                .sorted(Comparator.comparingInt(Peer::getPiecesRequiredAvailable).reversed())
                .forEach(peer -> {
                    if (Instant.now().isAfter(peer.lastActive.plus(peerTimeout))) {
                        peer.disconnect();
                        return;
                    }

                    if (!peer.isHandshakeSent || !peer.isHandshakeReceived) {
                        return;
                    }

                    if (torrent.isCompleted()) {
                        peer.sendNotInterested();
                    } else {
                        peer.sendInterested();
                    }

                    if (peer.isCompleted() && torrent.isCompleted()) {
                        peer.disconnect();
                        return;
                    }

                    peer.sendKeepAlive();

                    if (torrent.hasStarted() && leechers.size() < maxLeechers) {
                        if (peer.isInterestedReceived && peer.isChokeSent) {
                            peer.sendUnchoke();
                        }
                    }

                    if (!torrent.isCompleted() && seeders.size() <= maxSeeders) {
                        if (!peer.isChokeReceived) {
                            seeders.put(peer.getKey(), peer);
                        }
                    }
                });
        isProcessingPeers.set(false);
    }

    //-----------------------------------
    //             Uploads
    //-----------------------------------

    private ConcurrentLinkedQueue<DataRequest> outgoingBlocks = new ConcurrentLinkedQueue<>();

    private void handleBlockRequested(DataRequest block) {
        outgoingBlocks.add(block);
        processUploads();
    }

    private void handleBlockCancelled(DataRequest block) {
        for (var item : outgoingBlocks) {
            if (item.peer != block.peer || item.piece != block.piece ||
                    item.begin != block.begin || item.length != block.length) {
                continue;
            }
            item.isCancelled = true;
        }
        processUploads();
    }

    private Throttle uploadThrottle = new Throttle(
            maxUploadBytesPerSec,
            Duration.ofSeconds(1)
    );

    private void processUploads() {
        if (!isProcessingUploads.compareAndSet(false, true)) return;

        DataRequest block;
        while (!uploadThrottle.isThrottled() && (block = outgoingBlocks.poll()) != null) {
            if (block.isCancelled) continue;
            if (!torrent.isPieceVerified[block.piece]) continue;

            byte[] data = torrent.readBlock(block.piece, block.begin, block.length);
            if (data == null) continue;

            block.peer.sendPiece(block.piece, block.begin, data);
            uploadThrottle.add(block.length);
            torrent.uploaded += block.length;
        }
        isProcessingUploads.set(false);
    }

    //----------------------------------
    //            Downloads
    //----------------------------------

    private ConcurrentLinkedQueue<DataPackage> incomingBlocks = new ConcurrentLinkedQueue<>();

    private void handleBlockReceived(DataPackage args) {
        incomingBlocks.add(args);

        args.peer.isBlockRequested[args.piece][args.block] = false;

        peers.values().forEach(peer -> {
            if (!peer.isBlockRequested[args.piece][args.block]) return;
            peer.sendRequest(Peer.MessageType.cancel, args.piece, args.block * torrent.blockSize, torrent.blockSize);
            peer.isBlockRequested[args.piece][args.block] = false;
        });

        processDownloads();
    }

    private Throttle downloadThrottle = new Throttle(
            maxDownloadBytesPerSec,
            Duration.ofSeconds(1)
    );

    private void processDownloads() {
        if (!isProcessingDownloads.compareAndSet(false, true)) return;

        DataPackage incomingBlock;
        while((incomingBlock = incomingBlocks.poll()) != null) {
            torrent.writeBlock(incomingBlock.piece, incomingBlock.block, incomingBlock.data);
        }

        if (torrent.isCompleted()) {
            isProcessingDownloads.set(false);
            return;
        }

        int[] ranked = getRankedPieces();

        for (var piece : ranked) {
            if (torrent.isPieceVerified[piece]) continue;

            for (Peer peer : getRankedSeeders()) {
                if (!peer.isPieceDownloaded[piece]) continue;

                for (int block = 0; block < torrent.getBlockCount(piece); block++) {
                    if (downloadThrottle.isThrottled()) continue;
                    if (torrent.isBlockAcquired[piece][block]) continue;

                    // Request max one block from each peer
                    if (peer.getBlocksRequested() > 0) continue;

                    // Request from one peer
                    int finalBlock = block;
                    if (peers.values().stream().anyMatch(x -> x.isBlockRequested[piece][finalBlock])) continue;

                    int size = torrent.getBlockSize(piece, finalBlock);
                    peer.sendRequest(Peer.MessageType.request, piece, finalBlock * torrent.blockSize, size);
                    downloadThrottle.add(size);
                    peer.isBlockRequested[piece][finalBlock] = true;
                }
            }
        }
        isProcessingDownloads.set(false);
    }

    // Randomly order seeders
    private Peer[] getRankedSeeders() {
        List<Peer> seederList = new ArrayList<>(seeders.values());
        Collections.shuffle(seederList, new Random());
        return seederList.toArray(new Peer[0]);
    }

    private int[] getRankedPieces() {
        int[] indices = IntStream.range(0, torrent.getPieceCount()).toArray();
        double[] scores = Arrays.stream(indices).mapToDouble(this::getPieceScore).toArray();

        // Sort indices based on scores
        Integer[] indicesCopy = Arrays.stream(indices).boxed().toArray(Integer[]::new);
        Arrays.sort(indicesCopy, Comparator.comparingDouble(x -> scores[x]));
        //TODO ensure this works properly
        Collections.reverse(Arrays.asList(indicesCopy));
        indices = Arrays.stream(indicesCopy).mapToInt(Integer::intValue).toArray();

        return indices;
    }

    // Rank pieces based on progress, rarity, and a random value to prevent identical rankings
    private double getPieceScore(int piece) {
        double progress = getPieceProgress(piece);
        double rarity = getPieceRarity(piece);

        if (progress == 1.0) progress = 0;

        double rand = new Random().nextDouble(100) / 1000.0;

        return progress + rarity + rand;
    }

    // Find the piece progress from percentage of blocks already acquired
    private double getPieceProgress(int index) {
        return Arrays.stream(torrent.isBlockAcquired[index])
                .mapToDouble(x -> x ? 1.0 : 0.0)
                .average().orElse(0.0);
    }

    private double getPieceRarity(int index) {
        if (peers.isEmpty()) return 0;
        return peers.values().stream()
                .mapToDouble(x -> x.isPieceDownloaded[index] ? 0.0 : 1.0)
                .average().orElse(0.0);
    }
}
