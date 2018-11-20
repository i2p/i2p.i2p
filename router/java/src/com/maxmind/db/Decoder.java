package com.maxmind.db;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

/*
 * Decoder for MaxMind DB data.
 *
 * This class CANNOT be shared between threads
 */
final class Decoder {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int[] POINTER_VALUE_OFFSETS = { 0, 0, 1 << 11, (1 << 19) + ((1) << 11), 0 };

    // XXX - This is only for unit testings. We should possibly make a
    // constructor to set this
    boolean POINTER_TEST_HACK = false;

    private final NodeCache cache;

    private final long pointerBase;

    private final CharsetDecoder utfDecoder = UTF_8.newDecoder();

    private final ByteBuffer buffer;

    static enum Type {
        EXTENDED, POINTER, UTF8_STRING, DOUBLE, BYTES, UINT16, UINT32, MAP, INT32, UINT64, UINT128, ARRAY, CONTAINER, END_MARKER, BOOLEAN, FLOAT;

        // Java clones the array when you call values(). Caching it increased
        // the speed by about 5000 requests per second on my machine.
        final static Type[] values = Type.values();

        public static Type get(int i) {
            return Type.values[i];
        }

        private static Type get(byte b) {
            // bytes are signed, but we want to treat them as unsigned here
            return Type.get(b & 0xFF);
        }

        public static Type fromControlByte(int b) {
            // The type is encoded in the first 3 bits of the byte.
            return Type.get((byte) ((0xFF & b) >>> 5));
        }
    }

    Decoder(NodeCache cache, ByteBuffer buffer, long pointerBase) {
        this.cache = cache;
        this.pointerBase = pointerBase;
        this.buffer = buffer;
    }

    private final NodeCache.Loader cacheLoader = new NodeCache.Loader() {
        @Override
        public JsonNode load(int key) throws IOException {
            return decode(key);
        }
    };

    JsonNode decode(int offset) throws IOException {
        if (offset >= this.buffer.capacity()) {
            throw new InvalidDatabaseException(
                    "The MaxMind DB file's data section contains bad data: "
                            + "pointer larger than the database.");
        }

        this.buffer.position(offset);
        return decode();
    }

    JsonNode decode() throws IOException {
        int ctrlByte = 0xFF & this.buffer.get();

        Type type = Type.fromControlByte(ctrlByte);

        // Pointers are a special case, we don't read the next 'size' bytes, we
        // use the size to determine the length of the pointer and then follow
        // it.
        if (type.equals(Type.POINTER)) {
            int pointerSize = ((ctrlByte >>> 3) & 0x3) + 1;
            int base = pointerSize == 4 ? (byte) 0 : (byte) (ctrlByte & 0x7);
            int packed = this.decodeInteger(base, pointerSize);
            long pointer = packed + this.pointerBase + POINTER_VALUE_OFFSETS[pointerSize];

            // for unit testing
            if (this.POINTER_TEST_HACK) {
                return new LongNode(pointer);
            }

            int targetOffset = (int) pointer;
            int position = buffer.position();
            JsonNode node = cache.get(targetOffset, cacheLoader);
            buffer.position(position);
            return node;
        }

        if (type.equals(Type.EXTENDED)) {
            int nextByte = this.buffer.get();

            int typeNum = nextByte + 7;

            if (typeNum < 8) {
                throw new InvalidDatabaseException(
                        "Something went horribly wrong in the decoder. An extended type "
                                + "resolved to a type number < 8 (" + typeNum
                                + ")");
            }

            type = Type.get(typeNum);
        }

        int size = ctrlByte & 0x1f;
        if (size >= 29) {
            int bytesToRead = size - 28;
            int i = this.decodeInteger(bytesToRead);
            switch (size) {
            case 29:
                size = 29 + i;
                break;
            case 30:
                size = 285 + i;
                break;
            default:
                size = 65821 + (i & (0x0FFFFFFF >>> 32 - 8 * bytesToRead));
            }
        }

        return this.decodeByType(type, size);
    }

    private JsonNode decodeByType(Type type, int size)
            throws IOException {
        switch (type) {
            case MAP:
                return this.decodeMap(size);
            case ARRAY:
                return this.decodeArray(size);
            case BOOLEAN:
                return Decoder.decodeBoolean(size);
            case UTF8_STRING:
                return new TextNode(this.decodeString(size));
            case DOUBLE:
                return this.decodeDouble(size);
            case FLOAT:
                return this.decodeFloat(size);
            case BYTES:
                return new BinaryNode(this.getByteArray(size));
            case UINT16:
                return this.decodeUint16(size);
            case UINT32:
                return this.decodeUint32(size);
            case INT32:
                return this.decodeInt32(size);
            case UINT64:
                return this.decodeBigInteger(size);
            case UINT128:
                return this.decodeBigInteger(size);
            default:
                throw new InvalidDatabaseException(
                        "Unknown or unexpected type: " + type.name());
        }
    }

    private String decodeString(int size) throws CharacterCodingException {
        int oldLimit = buffer.limit();
        buffer.limit(buffer.position() + size);
        String s = utfDecoder.decode(buffer).toString();
        buffer.limit(oldLimit);
        return s;
    }

    private IntNode decodeUint16(int size) {
        return new IntNode(this.decodeInteger(size));
    }

    private IntNode decodeInt32(int size) {
        return new IntNode(this.decodeInteger(size));
    }

    private long decodeLong(int size) {
        long integer = 0;
        for (int i = 0; i < size; i++) {
            integer = (integer << 8) | (this.buffer.get() & 0xFF);
        }
        return integer;
    }

    private LongNode decodeUint32(int size) {
        return new LongNode(this.decodeLong(size));
    }

    private int decodeInteger(int size) {
        return this.decodeInteger(0, size);
    }

    private int decodeInteger(int base, int size) {
        return Decoder.decodeInteger(this.buffer, base, size);
    }

    static int decodeInteger(ByteBuffer buffer, int base, int size) {
        int integer = base;
        for (int i = 0; i < size; i++) {
            integer = (integer << 8) | (buffer.get() & 0xFF);
        }
        return integer;
    }

    private BigIntegerNode decodeBigInteger(int size) {
        byte[] bytes = this.getByteArray(size);
        return new BigIntegerNode(new BigInteger(1, bytes));
    }

    private DoubleNode decodeDouble(int size) throws InvalidDatabaseException {
        if (size != 8) {
            throw new InvalidDatabaseException(
                    "The MaxMind DB file's data section contains bad data: "
                            + "invalid size of double.");
        }
        return new DoubleNode(this.buffer.getDouble());
    }

    private FloatNode decodeFloat(int size) throws InvalidDatabaseException {
        if (size != 4) {
            throw new InvalidDatabaseException(
                    "The MaxMind DB file's data section contains bad data: "
                            + "invalid size of float.");
        }
        return new FloatNode(this.buffer.getFloat());
    }

    private static BooleanNode decodeBoolean(int size)
            throws InvalidDatabaseException {
        switch (size) {
            case 0:
                return BooleanNode.FALSE;
            case 1:
                return BooleanNode.TRUE;
            default:
                throw new InvalidDatabaseException(
                        "The MaxMind DB file's data section contains bad data: "
                                + "invalid size of boolean.");
        }
    }

    private JsonNode decodeArray(int size) throws IOException {

        List<JsonNode> array = new ArrayList<JsonNode>(size);
        for (int i = 0; i < size; i++) {
            JsonNode r = this.decode();
            array.add(r);
        }

        return new ArrayNode(OBJECT_MAPPER.getNodeFactory(), Collections.unmodifiableList(array));
    }

    private JsonNode decodeMap(int size) throws IOException {
        int capacity = (int) (size / 0.75F + 1.0F);
        Map<String, JsonNode> map = new HashMap<String, JsonNode>(capacity);

        for (int i = 0; i < size; i++) {
            String key = this.decode().asText();
            JsonNode value = this.decode();
            map.put(key, value);
        }

        return new ObjectNode(OBJECT_MAPPER.getNodeFactory(), Collections.unmodifiableMap(map));
    }

    private byte[] getByteArray(int length) {
        return Decoder.getByteArray(this.buffer, length);
    }

    private static byte[] getByteArray(ByteBuffer buffer, int length) {
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }
}
