package proto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON value model with parser and serializer (insertion-ordered). */
public final class Json {
    private Json() {}

    public enum Type { OBJECT, ARRAY, STRING, NUMBER, BOOL, NULL }

    public static final class Value {
        public final Type type;
        private final Object data;

        Value(Type type, Object data) { this.type = type; this.data = data; }

        public static Value ofNull() { return new Value(Type.NULL, null); }
        public static Value of(boolean b) { return new Value(Type.BOOL, b); }
        public static Value of(long n) { return new Value(Type.NUMBER, n); }
        public static Value of(double n) { return new Value(Type.NUMBER, n); }
        public static Value str(String s) { return new Value(Type.STRING, s); }

        public static Value obj() { return new Value(Type.OBJECT, new LinkedHashMap<String, Value>()); }
        public static Value arr() { return new Value(Type.ARRAY, new ArrayList<Value>()); }

        public boolean is(Type t) { return type == t; }

        @SuppressWarnings("unchecked")
        public Map<String, Value> asObj() { return (Map<String, Value>) data; }
        @SuppressWarnings("unchecked")
        public List<Value> asArr() { return (List<Value>) data; }
        public String asStr() { return type == Type.STRING ? (String) data : null; }
        public boolean asBool() { return type == Type.BOOL && (Boolean) data; }
        public long asLong() { return ((Number) data).longValue(); }
        public double asDouble() { return ((Number) data).doubleValue(); }

        public Value get(String key) {
            if (type != Type.OBJECT) return null;
            return asObj().get(key);
        }
        public String reqStr(String key) {
            Value v = get(key);
            if (v == null || v.type != Type.STRING) throw new JsonException("missing string field: " + key);
            return v.asStr();
        }
        public List<Value> optArr(String key) {
            Value v = get(key);
            if (v == null) return new ArrayList<>();
            if (v.type != Type.ARRAY) throw new JsonException("field must be array: " + key);
            return v.asArr();
        }
        public boolean has(String key) { return get(key) != null; }

        public Value put(String key, Value v) { asObj().put(key, v); return this; }
        public Value put(String key, String v) { return put(key, Value.str(v)); }
        public Value put(String key, long v) { return put(key, Value.of(v)); }
        public Value put(String key, boolean v) { return put(key, Value.of(v)); }
        public Value add(Value v) { asArr().add(v); return this; }
        public Value add(String v) { return add(Value.str(v)); }
        public Value add(long v) { return add(Value.of(v)); }

        public String dump() {
            StringBuilder sb = new StringBuilder();
            write(sb, this);
            return sb.toString();
        }
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String m) { super(m); }
    }

    public static Value parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Value v = p.readValue();
        p.skipWs();
        if (!p.eof()) throw new JsonException("trailing content at " + p.pos);
        return v;
    }

    private static final class Parser {
        final String s; int pos;
        Parser(String s) { this.s = s; }
        boolean eof() { return pos >= s.length(); }
        char peek() { return s.charAt(pos); }
        void skipWs() { while (!eof() && Character.isWhitespace(peek())) pos++; }
        void expect(char c) {
            if (eof() || s.charAt(pos) != c) throw new JsonException("expected '" + c + "' at " + pos);
            pos++;
        }
        Value readValue() {
            skipWs();
            if (eof()) throw new JsonException("unexpected end");
            char c = peek();
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return Value.str(readString());
                case 't': case 'f': return readBool();
                case 'n': return readNull();
                default: return readNumber();
            }
        }
        Value readObject() {
            expect('{'); skipWs();
            Value o = Value.obj();
            if (!eof() && peek() == '}') { pos++; return o; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs(); expect(':');
                Value val = readValue();
                o.asObj().put(key, val);
                skipWs();
                if (eof()) throw new JsonException("unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') break;
                if (c != ',') throw new JsonException("expected , or } at " + pos);
            }
            return o;
        }
        Value readArray() {
            expect('['); skipWs();
            Value a = Value.arr();
            if (!eof() && peek() == ']') { pos++; return a; }
            while (true) {
                a.asArr().add(readValue());
                skipWs();
                if (eof()) throw new JsonException("unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') break;
                if (c != ',') throw new JsonException("expected , or ] at " + pos);
            }
            return a;
        }
        Value readBool() {
            if (s.startsWith("true", pos)) { pos += 4; return Value.of(true); }
            if (s.startsWith("false", pos)) { pos += 5; return Value.of(false); }
            throw new JsonException("bad literal at " + pos);
        }
        Value readNull() {
            if (s.startsWith("null", pos)) { pos += 4; return Value.ofNull(); }
            throw new JsonException("bad literal at " + pos);
        }
        Value readNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!eof() && (Character.isDigit(peek()) || peek() == '.' || peek() == 'e' || peek() == 'E'
                    || peek() == '+' || peek() == '-')) pos++;
            String num = s.substring(start, pos);
            try {
                if (num.contains(".") || num.contains("e") || num.contains("E"))
                    return Value.of(Double.parseDouble(num));
                return Value.of(Long.parseLong(num));
            } catch (NumberFormatException e) {
                throw new JsonException("bad number: " + num);
            }
        }
        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new JsonException("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (eof()) throw new JsonException("bad escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new JsonException("bad escape \\" + e);
                    }
                } else sb.append(c);
            }
        }
    }

    private static void write(StringBuilder sb, Value v) {
        switch (v.type) {
            case NULL: sb.append("null"); break;
            case BOOL: sb.append(v.asBool()); break;
            case NUMBER: {
                double d = v.asDouble();
                if (d == Math.rint(d) && !Double.isInfinite(d)
                        && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                    sb.append(v.asLong());
                } else sb.append(v.data);
                break;
            }
            case STRING: writeStr(sb, v.asStr()); break;
            case ARRAY: {
                sb.append('[');
                boolean first = true;
                for (Value e : v.asArr()) { if (!first) sb.append(','); first = false; write(sb, e); }
                sb.append(']');
                break;
            }
            case OBJECT: {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Value> e : v.asObj().entrySet()) {
                    if (!first) sb.append(','); first = false;
                    writeStr(sb, e.getKey()); sb.append(':'); write(sb, e.getValue());
                }
                sb.append('}');
                break;
            }
        }
    }

    private static void writeStr(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
