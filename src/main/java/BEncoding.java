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
    public static Object DecodeFile(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(Path.of(path));
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

    // Identify next object to decode and call relevant method
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

    // Uses TreeMap with custom comparator to sort by byte representation of keys
    private static TreeMap<String, Object> DecodeDict(Iterator<Byte> iter) {
        TreeMap<String, Object> dict = new TreeMap<>(
                Comparator.comparing(key -> Arrays.toString(key.getBytes(StandardCharsets.UTF_8)))
        );

        while (iter.hasNext()) {
            byte current = iter.next();
            if (current == END) break;

            String key = new String(DecodeByteArray(current, iter), StandardCharsets.UTF_8);
            current = iter.next();
            Object val = DecodeNext(current, iter);

            dict.put(key, val);
        }

        return dict;
    }


    //----------------------ENCODING------------------------


    public static void EncodeFile(Object obj, String path) throws Exception {
        Files.write(Path.of(path), Encode(obj));
    }

    public static byte[] Encode(Object obj) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        EncodeNext(buffer, obj);
        return buffer.toByteArray();
    }

    private static void EncodeNext(ByteArrayOutputStream buffer, Object obj) throws Exception {
        if (obj instanceof byte[]) EncodeByteArray(buffer, (byte[])obj);
        else if (obj instanceof String) EncodeString(buffer, (String)obj);
        else if (obj instanceof Long) EncodeNum(buffer, (Long)obj);
        else if (obj instanceof List<?>) EncodeList(buffer, (List<Object>)obj);
        else if (obj instanceof HashMap<?,?>) EncodeDict(buffer, (HashMap<String, Object>)obj);
        else throw new Exception("Unable to encode type " + obj.getClass());

    }

    private static void EncodeNum(ByteArrayOutputStream buffer, long num) throws IOException {
        buffer.write(NUM_START);
        buffer.write(Long.toString(num).getBytes(StandardCharsets.UTF_8));
        buffer.write(END);
    }

    private static void EncodeByteArray(ByteArrayOutputStream buffer, byte[] bytes) throws IOException {
        buffer.write(new String(bytes, StandardCharsets.UTF_8).length());
        buffer.write(DIVIDER);
        buffer.write(bytes);
    }

    private static void EncodeString(ByteArrayOutputStream buffer, String str) throws IOException {
        EncodeByteArray(buffer, str.getBytes(StandardCharsets.UTF_8));
    }

    private static void EncodeList(ByteArrayOutputStream buffer, List<Object> list) throws Exception {
        buffer.write(LIST_START);
        for (Object item : list) EncodeNext(buffer, item);
        buffer.write(END);
    }

    private static void EncodeDict(ByteArrayOutputStream buffer, Map<String, Object> dict) throws Exception {
        TreeMap<String, Object> sortedDict = new TreeMap<>(
                Comparator.comparing(key -> Arrays.toString(key.getBytes(StandardCharsets.UTF_8)))
        );
        sortedDict.putAll(dict);

        buffer.write(DICT_START);
        for (String key : sortedDict.keySet()) {
            EncodeString(buffer, key);
            EncodeNext(buffer, sortedDict.get(key));
        }
        buffer.write(END);
    }
}

//TODO fix access modifiers