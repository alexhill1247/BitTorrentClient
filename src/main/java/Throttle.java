import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Throttle {
    public long maxSize;
    public Duration maxWindow;

    static class Item {
        public Instant time;
        public long size;

        public Item(Instant time, long size) {
            this.time = time;
            this.size = size;
        }
    }

    private final Object lock = new Object();
    private final List<Item> items = new ArrayList<>();

    public Throttle(int maxSize, Duration maxWindow) {
        this.maxSize = maxSize;
        this.maxWindow = maxWindow;
    }

    public void add(long size) {
        synchronized (lock) {
            items.add(new Item(Instant.now(), size));
        }
    }

    public boolean isThrottled() {
        synchronized (lock) {
            Instant cutoff = Instant.now().minus(maxWindow);
            items.removeIf(x -> x.time.isBefore(cutoff));
            return items.stream().mapToLong(x -> x.size).sum() >= maxSize;
        }
    }
}
