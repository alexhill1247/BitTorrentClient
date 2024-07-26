import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

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
        byte current = iter.next();
        return DecodeNext(current, iter);
    }

    private static Object DecodeNext(byte current, Iterator<Byte> iter) {
        if (current == DICT_START) return DecodeDict(iter);
        if (current == LIST_START) return DecodeList(iter);
        if (current == NUM_START) return DecodeNum(iter);
        return DecodeByteArray(current, iter);
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

    private static List<Object> DecodeList(Iterator<Byte> iter) {
        List<Object> list = new ArrayList<>();

        while (iter.hasNext()) {
            byte current = iter.next();
            if (current == END) break;
            list.add(DecodeNext(current, iter));
        }

        return list;
    }

    private static HashMap<String, Object> DecodeDict(Iterator<Byte> iter) {
        HashMap<String, Object> dict = new HashMap<>();
        List<String> keys = new ArrayList<>();

        while (iter.hasNext()) {
            byte current = iter.next();
            if (current == END) break;

            String key = new String(DecodeByteArray(current, iter), StandardCharsets.UTF_8);
            current = iter.next();
            Object val = DecodeNext(current, iter);

            keys.add(key);
            dict.put(key, val);
        }

        // TODO Verify dict is sorted correctly
        // Important to ensure we are able to encode correctly

        return dict;
    }
}
