package com.poso.qqbind.official;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Actual V2 HTTP boundary. No legacy handler, logging, retries or game access.
 * Authentication is checked before reading a request body, then checked again
 * by the runtime dispatcher under its configuration lease.
 */
public final class OfficialHttpHandler implements HttpHandler {
    public static final String PREFIX="/api/v2/binding";
    static final int MAX_BYTES=1024*1024, MAX_QUERY=8192;
    private static final Set<String> SCOPE=Set.of("protocol_version","environment_id","group_id","server_id");
    private static final Set<String> POSTS=Set.of("/token/validate","/token/reserve","/token/finalize","/sync","/bind","/unbind","/command","/settings/whitelist");
    private static final Set<String> ERRORS=Set.of("INVALID_INPUT","UNAUTHORIZED","NOT_SUPPORTED","SCOPE_MISMATCH","SCHEMA_MISMATCH",
        "DB_UNAVAILABLE","CONFLICT","RESULT_UNKNOWN","TOKEN_INVALID","STORAGE_ERROR","LIMIT_EXCEEDED");
    @FunctionalInterface public interface Authentication { void check(String authorization); }
    @FunctionalInterface public interface Dispatcher {
        OfficialTokenApi.Reply dispatch(String method,String path,JsonObject request,String authorization);
    }
    private final Authentication authentication;
    private final Dispatcher dispatcher;
    private final Semaphore requests=new Semaphore(8);
    public OfficialHttpHandler(Authentication authentication,Dispatcher dispatcher) {
        this.authentication=Objects.requireNonNull(authentication); this.dispatcher=Objects.requireNonNull(dispatcher);
    }
    @Override public void handle(HttpExchange exchange) {
        OfficialTokenApi.Reply reply; boolean acquired=false,dispatched=false,mutation=false;
        try {
            String authorization=single(exchange.getRequestHeaders(),"Authorization","UNAUTHORIZED");
            if (authorization == null) throw new OfficialException("UNAUTHORIZED");
            authentication.check(authorization);
            acquired=requests.tryAcquire();
            if (!acquired) throw new OfficialException("DB_UNAVAILABLE");
            String method=exchange.getRequestMethod(); URI uri=exchange.getRequestURI();
            String path=route(method,uri); mutation=method.equals("POST");
            JsonObject request=read(exchange,uri,mutation);
            dispatched=true;
            reply=dispatcher.dispatch(method,path,request,authorization);
            if (reply == null || reply.body() == null || reply.status() < 200 || reply.status() > 599
                    || encode(reply).length > MAX_BYTES) throw new IllegalStateException();
        } catch (OfficialException e) { reply=error(e.code()); }
        catch (Exception e) { reply=error(dispatched && mutation ? "RESULT_UNKNOWN" : "STORAGE_ERROR"); }
        finally { if (acquired) requests.release(); }
        try {
            byte[] body=encode(reply);
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            exchange.getResponseHeaders().set("Connection","close");
            exchange.sendResponseHeaders(reply.status(),body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception ignored) {
            // A lost response cannot authorize a second dispatch or undo a commit.
        } finally { exchange.close(); }
    }
    private static byte[] encode(OfficialTokenApi.Reply reply) { return reply.body().toString().getBytes(StandardCharsets.UTF_8); }
    private static OfficialTokenApi.Reply error(String code) { return OfficialTokenApi.error(ERRORS.contains(code) ? code : "STORAGE_ERROR"); }
    private static String single(Headers headers,String name,String code) {
        List<String> values=headers.get(name);
        if (values == null) return null;
        if (values.size() != 1 || values.get(0) == null) throw new OfficialException(code);
        return values.get(0);
    }
    private static String route(String method,URI uri) {
        if (uri == null || uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) throw new OfficialException("INVALID_INPUT");
        String path=uri.getRawPath();
        if (path == null || !path.startsWith(PREFIX+"/")) throw new OfficialException("NOT_SUPPORTED");
        String suffix=path.substring(PREFIX.length());
        if ("GET".equals(method)) {
            if (suffix.equals("/capabilities") || suffix.equals("/check") || suffix.equals("/operations")) return path;
            if (suffix.startsWith("/operations/")) { OfficialScope.uuid(suffix.substring("/operations/".length())); return path; }
        } else if ("POST".equals(method) && POSTS.contains(suffix)) return path;
        throw new OfficialException("NOT_SUPPORTED");
    }
    private static JsonObject read(HttpExchange exchange,URI uri,boolean post) {
        Headers headers=exchange.getRequestHeaders();
        String length=single(headers,"Content-Length","INVALID_INPUT"),transfer=single(headers,"Transfer-Encoding","INVALID_INPUT");
        long expected=-1;
        if (length != null) {
            if (!length.matches("[0-9]{1,10}")) throw new OfficialException("INVALID_INPUT");
            expected=Long.parseLong(length);
            if (expected > MAX_BYTES) throw new OfficialException("LIMIT_EXCEEDED");
        }
        if (transfer != null && (length != null || !transfer.equalsIgnoreCase("chunked"))) throw new OfficialException("INVALID_INPUT");
        String encoding=single(headers,"Content-Encoding","INVALID_INPUT");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new OfficialException("INVALID_INPUT");
        if (post) {
            if (uri.getRawQuery() != null) throw new OfficialException("INVALID_INPUT");
            String content=single(headers,"Content-Type","INVALID_INPUT");
            if (content == null || !content.matches("(?i)application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*(?:utf-8|\"utf-8\"))?")) throw new OfficialException("INVALID_INPUT");
        } else if (expected > 0 || transfer != null) throw new OfficialException("INVALID_INPUT");
        byte[] body;
        try { body=exchange.getRequestBody().readNBytes(post ? MAX_BYTES+1 : 1); }
        catch (IOException e) { throw new OfficialException("INVALID_INPUT"); }
        if (body.length > MAX_BYTES) throw new OfficialException("LIMIT_EXCEEDED");
        if (expected >= 0 && expected != body.length || !post && body.length != 0) throw new OfficialException("INVALID_INPUT");
        if (!post) return query(uri);
        try {
            JsonElement parsed=OfficialModState.parse(utf8(body));
            if (!parsed.isJsonObject()) throw new IOException();
            return parsed.getAsJsonObject();
        } catch (Exception e) { throw new OfficialException("INVALID_INPUT"); }
    }
    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static JsonObject query(URI uri) {
        String raw=uri.getRawQuery(); JsonObject result=new JsonObject();
        if (raw == null || raw.isEmpty()) return result;
        if (raw.length() > MAX_QUERY) throw new OfficialException("LIMIT_EXCEEDED");
        String[] entries=raw.split("&",-1);
        if (entries.length > 8) throw new OfficialException("INVALID_INPUT");
        for (String entry : entries) {
            int at=entry.indexOf('=');
            if (at < 1) throw new OfficialException("INVALID_INPUT");
            String name=decode(entry.substring(0,at)),value=decode(entry.substring(at+1));
            boolean check=uri.getRawPath().equals(PREFIX+"/check");
            boolean operations=uri.getRawPath().equals(PREFIX+"/operations");
            if (result.has(name) || !SCOPE.contains(name) && !(check && Set.of("game_id","user_id","limit","cursor","view").contains(name))
                    && !(operations && Set.of("filter","limit").contains(name))) throw new OfficialException("INVALID_INPUT");
            if (name.equals("protocol_version")) {
                if (!value.equals("2")) throw new OfficialException("NOT_SUPPORTED"); result.addProperty(name,2);
            } else if (name.equals("limit")) {
                if (!value.matches("[1-9][0-9]{0,2}") || Integer.parseInt(value) > (operations ? 201 : 100)) throw new OfficialException("INVALID_INPUT");
                result.addProperty(name,Integer.parseInt(value));
            } else result.addProperty(name,value);
        }
        return result;
    }
    private static String decode(String raw) {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for (int i=0;i<raw.length();i++) {
            char c=raw.charAt(i);
            if (c == '%') {
                if (i+2 >= raw.length()) throw new OfficialException("INVALID_INPUT");
                int high=Character.digit(raw.charAt(++i),16),low=Character.digit(raw.charAt(++i),16);
                if (high < 0 || low < 0) throw new OfficialException("INVALID_INPUT"); bytes.write(high*16+low);
            } else {
                if (c < 33 || c > 126) throw new OfficialException("INVALID_INPUT"); bytes.write(c == '+' ? ' ' : c);
            }
        }
        try {
            String decoded=utf8(bytes.toByteArray());
            if (decoded.chars().anyMatch(c -> c < 32 || c == 127)) throw new OfficialException("INVALID_INPUT"); return decoded;
        } catch (CharacterCodingException e) { throw new OfficialException("INVALID_INPUT"); }
    }
}
