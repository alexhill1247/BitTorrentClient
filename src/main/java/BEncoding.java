import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class BEncoding {

    private static final byte DICT_START = "d".getBytes(StandardCharsets.UTF_8)[0];
    private static final byte LIST_START = "l".getBytes(StandardCharsets.UTF_8)[0];
    private static final byte NUM_START = "i".getBytes(StandardCharsets.UTF_8)[0];
    private static final byte END = "e".getBytes(StandardCharsets.UTF_8)[0];
    private static final byte DIVIDER = ":".getBytes(StandardCharsets.UTF_8)[0];

    // Read file into byte array then convert to List for iterator
    public static Object DecodeFile(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        List<Byte> byteList = new ArrayList<>();
        for (byte b : bytes) {
            byteList.add(b);
        }
        return Decode(byteList);
    }

    public static Object Decode(List<Byte> bytes) {
        Iterator<Byte> iter = bytes.iterator();
        return DecodeNext(iter);
    }

    private static Object DecodeNext(Iterator<Byte> iter) {
        byte b = iter.next();
        if (b == DICT_START) return DecodeDict(iter);
        if (b == LIST_START) return DecodeList(iter);
        if (b == NUM_START) return DecodeNum(iter);
        return DecodeByteArray(b, iter);
    }

    private static long DecodeNum(Iterator<Byte> iter) {
        List<Byte> byteList = new ArrayList<>();

        // Step through iterator to create number as list
        while (iter.hasNext()) {
            byte current = iter.next();
            if (current == END) break;
            byteList.add(current);
        }

        // Convert list to string
        String numString = new String(ByteListToArray(byteList), StandardCharsets.UTF_8);

        return Long.parseLong(numString);
    }

    private static byte[] ByteListToArray(List<Byte> byteList) {
        byte[] byteArray = new byte[byteList.size()];
        for (int i = 0; i < byteList.size(); i++) {
            byteArray[i] = byteList.get(i);
        }
        return byteArray;
    }

    // Represented by the length of the byte array, followed by a separator then the content itself
    private static byte[] DecodeByteArray(byte current, Iterator<Byte> iter) {
        List<Byte> lengthList = new ArrayList<>();

        // Read bytes into list up to divider
        lengthList.add(current);
        while (iter.hasNext()) {
            current = iter.next();
            if (current == DIVIDER) break;
            lengthList.add(current);
        }
        // Get the integer representation
        String lengthString = new String(ByteListToArray(lengthList), StandardCharsets.UTF_8);
        int length = Integer.parseInt(lengthString);

        // Read actual content
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = iter.next();
        }
        return bytes;
    }
}
