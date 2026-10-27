package ai.lmctl.lmfiles;

import io.lmctl.lmcode.Json;
import java.util.*;

/** Deterministic JSON interchange; persisted data is never silently redacted. */
public final class J {
    private J() {}
    public static Object parse(String text) { return Json.parse(text); }
    public static Map<String,Object> obj(String text) { return Json.object(parse(text)); }
    public static String text(Map<?,?> map,String name,String fallback) {
        Object value=map.get(name); return value==null?fallback:value.toString();
    }
    public static String str(Object value) {
        if(value==null)return "null";
        if(value instanceof String text)return quote(text);
        if(value instanceof Boolean)return value.toString();
        if(value instanceof Number n) {
            if(n instanceof Double d && (!Double.isFinite(d)))throw new IllegalArgumentException("non-finite JSON number");
            if(n instanceof Float f && (!Float.isFinite(f)))throw new IllegalArgumentException("non-finite JSON number");
            return new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        if(value instanceof Map<?,?> map) {
            var sorted=new TreeMap<String,Object>();
            map.forEach((k,v)->{if(!(k instanceof String))throw new IllegalArgumentException("JSON keys must be strings");sorted.put((String)k,v);});
            var parts=new ArrayList<String>();sorted.forEach((k,v)->parts.add(quote(k)+":"+str(v)));
            return "{"+String.join(",",parts)+"}";
        }
        if(value instanceof Collection<?> list)return "["+String.join(",",list.stream().map(J::str).toList())+"]";
        throw new IllegalArgumentException("unsupported JSON value");
    }
    public static String quote(String text) {
        var out=new StringBuilder("\"");
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            switch(c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f");
                default -> { if(c<32 || Character.isSurrogate(c))out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
