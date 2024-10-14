import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class BEncoding {

    private static final byte DICT_START = "d".getBytes(StandardCharsets.UTF_8)[0];
    private static final byte LIST_START = "l".getBytes(StandardCharsets.UTF_8)[0];
    private static final byte NUM_START = "i".getBytes(StandardCharsets.UTF_8)[0];
    private static final byte END = "e".getBytes(StandardCharsets.UTF_8)[0];
    private static final byte DIVIDER = ":".getBytes(StandardCharsets.UTF_8)[0];


    //--------------------DECODING---------------------


    // Read file into byte array then convert to list for iterator
    public static Object decodeFile(String path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        List<Byte> byteList = new ArrayList<>();
        for (byte b : bytes) {
            byteList.add(b);
        }
        return decode(byteList);
    }

    public static Object decode(List<Byte> bytes) {
        Iterator<Byte> iter = bytes.iterator();
        byte current = iter.next();
        return decodeNext(current, iter);
    }

    // Identify next object to decode and call relevant method
    private static Object decodeNext(byte current, Iterator<Byte> iter) {
        if (current == DICT_START) return decodeDict(iter);
        if (current == LIST_START) return decodeList(iter);
        if (current == NUM_START) return decodeNum(iter);
        return decodeByteArray(current, iter);
    }

    private static long decodeNum(Iterator<Byte> iter) {
        List<Byte> byteList = new ArrayList<>();

        // Step through iterator to create number as list
        while (iter.hasNext()) {
            byte current = iter.next();
            if (current == END) break;
            byteList.add(current);
        }

        // Convert list to string
        String numString = new String(byteListToArray(byteList), StandardCharsets.UTF_8);

        return Long.parseLong(numString);
    }

    private static byte[] byteListToArray(List<Byte> byteList) {
        byte[] byteArray = new byte[byteList.size()];
        for (int i = 0; i < byteList.size(); i++) {
            byteArray[i] = byteList.get(i);
        }
        return byteArray;
    }

    // Represented by the length of the byte array, followed by a separator then the content itself
    private static byte[] decodeByteArray(byte current, Iterator<Byte> iter) {
        List<Byte> lengthList = new ArrayList<>();

        // Read bytes into list up to divider
        lengthList.add(current);
        while (iter.hasNext()) {
            current = iter.next();
            if (current == DIVIDER) break;
            lengthList.add(current);
        }
        // Get the integer representation
        String lengthString = new String(byteListToArray(lengthList), StandardCharsets.UTF_8);
        int length = Integer.parseInt(lengthString);

        // Read actual content
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = iter.next();
        }
        return bytes;
    }

    private static List<Object> decodeList(Iterator<Byte> iter) {
        List<Object> list = new ArrayList<>();

        while (iter.hasNext()) {
            byte current = iter.next();
            if (current == END) break;
            list.add(decodeNext(current, iter));
        }

        return list;
    }

    // Uses TreeMap with custom comparator to sort by byte representation of keys
    private static TreeMap<String, Object> decodeDict(Iterator<Byte> iter) {
        TreeMap<String, Object> dict = new TreeMap<>(
                Comparator.comparing(key -> Arrays.toString(key.getBytes(StandardCharsets.UTF_8)))
        );

        while (iter.hasNext()) {
            byte current = iter.next();
            if (current == END) break;

            String key = new String(decodeByteArray(current, iter), StandardCharsets.UTF_8);
            current = iter.next();
            Object val = decodeNext(current, iter);

            dict.put(key, val);
        }

        return dict;
    }


    //----------------------ENCODING------------------------


    public static void encodeFile(Object obj, String path) {
        try {
            Files.write(Path.of(path), encode(obj));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] encode(Object obj) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        encodeNext(buffer, obj);
        return buffer.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void encodeNext(ByteArrayOutputStream buffer, Object obj) throws Exception {
        if (obj instanceof byte[]) encodeByteArray(buffer, (byte[])obj);
        else if (obj instanceof String) encodeString(buffer, (String)obj);
        else if (obj instanceof Long) encodeNum(buffer, (Long)obj);
        else if (obj instanceof List<?>) encodeList(buffer, (List<Object>) obj);
        else if (obj instanceof Map<?,?>) encodeDict(buffer, (Map<String, Object>) obj);
        else throw new Exception("Unable to encode type " + obj.getClass());

    }

    private static void encodeNum(ByteArrayOutputStream buffer, long num) throws IOException {
        buffer.write(NUM_START);
        buffer.write(Long.toString(num).getBytes(StandardCharsets.UTF_8));
        buffer.write(END);
    }

    private static void encodeByteArray(ByteArrayOutputStream buffer, byte[] bytes) throws IOException {
        buffer.write(new String(bytes, StandardCharsets.UTF_8).length());
        buffer.write(DIVIDER);
        buffer.write(bytes);
    }

    private static void encodeString(ByteArrayOutputStream buffer, String str) throws IOException {
        encodeByteArray(buffer, str.getBytes(StandardCharsets.UTF_8));
    }

    private static void encodeList(ByteArrayOutputStream buffer, List<Object> list) throws Exception {
        buffer.write(LIST_START);
        for (Object item : list) encodeNext(buffer, item);
        buffer.write(END);
    }

    private static void encodeDict(ByteArrayOutputStream buffer, Map<String, Object> dict) throws Exception {
        TreeMap<String, Object> sortedDict = new TreeMap<>(
                Comparator.comparing(key -> Arrays.toString(key.getBytes(StandardCharsets.UTF_8)))
        );
        sortedDict.putAll(dict);

        buffer.write(DICT_START);
        for (String key : sortedDict.keySet()) {
            encodeString(buffer, key);
            encodeNext(buffer, sortedDict.get(key));
        }
        buffer.write(END);
    }
}