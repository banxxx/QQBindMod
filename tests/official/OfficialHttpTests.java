package com.poso.qqbind.official;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.poso.qqbind.official.OfficialCenterTests.*;

/** HttpExchange in memory only: no HttpServer, sockets, real config or driver. */
public final class OfficialHttpTests {
    static final String PREFIX=OfficialHttpHandler.PREFIX, AUTH="Bearer " + OfficialRuntimeTests.TOKEN;
    public static final class Exchange extends HttpExchange {
        final Headers request=new Headers(),response=new Headers();
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final URI uri; final String method; final byte[] body;
        int status=-1,reads,sends; long length; boolean closed,forbidRead,failWrite;
        public Exchange(String method,String uri,String body) { this(method,uri,body.getBytes(StandardCharsets.UTF_8)); }
        public Exchange(String method,String uri,byte[] body) {
            this.method=method; this.uri=URI.create(uri); this.body=body;
            request.set("Authorization",AUTH); request.set("Content-Type","application/json"); request.set("Content-Length",Integer.toString(body.length));
        }
        public Headers getRequestHeaders() { return request; }
        public Headers getResponseHeaders() { return response; }
        public URI getRequestURI() { return uri; }
        public String getRequestMethod() { return method; }
        public HttpContext getHttpContext() { throw new AssertionError("No real server context"); }
        public void close() { closed=true; }
        public InputStream getRequestBody() {
            if (forbidRead) throw new AssertionError("Body must not be read");
            return new ByteArrayInputStream(body) {
                public synchronized int read(byte[] bytes,int offset,int size) { reads++; return super.read(bytes,offset,size); }
            };
        }
        public OutputStream getResponseBody() { return failWrite ? new OutputStream() {
            public void write(int value) throws IOException { throw new IOException("SYNTHETIC_PRIVATE_OUTPUT_FAILURE"); }
        } : output; }
        public void sendResponseHeaders(int status,long length) { this.status=status; this.length=length; sends++; }
        public InetSocketAddress getRemoteAddress() { throw new AssertionError("No remote address lookup"); }
        public int getResponseCode() { return status; }
        public InetSocketAddress getLocalAddress() { throw new AssertionError("No socket"); }
        public String getProtocol() { return "HTTP/1.1"; }
        public Object getAttribute(String name) { throw new AssertionError("No authentication attributes"); }
        public void setAttribute(String name,Object value) { throw new AssertionError("No authentication attributes"); }
        public void setStreams(InputStream input,OutputStream output) { throw new AssertionError("No stream replacement"); }
        public HttpPrincipal getPrincipal() { throw new AssertionError("No logged-in principal"); }
        public JsonObject json() throws Exception { return OfficialModState.parse(output.toString(StandardCharsets.UTF_8)).getAsJsonObject(); }
    }
    static final class Probe {
        int auth,calls; JsonObject input; String method,path,header; Runnable before=() -> {};
        OfficialTokenApi.Reply reply=OfficialTokenApi.error("NOT_SUPPORTED");
        final OfficialHttpHandler handler=new OfficialHttpHandler(value -> { auth++; check(value.equals(AUTH)); },(m,p,r,h) -> {
            calls++; method=m; path=p; input=r.deepCopy(); header=h; before.run(); return reply;
        });
    }
    public static String query(OfficialScope scope) {
        StringJoiner value=new StringJoiner("&");
        scope.wire().entrySet().forEach(entry -> value.add(entry.getKey()+"="+URLEncoder.encode(entry.getValue().getAsString(),StandardCharsets.UTF_8)));
        return value.toString();
    }
    static Exchange get(String suffix) { return new Exchange("GET",PREFIX+suffix+"?"+query(S),""); }
    static Exchange post(String suffix,String body) { return new Exchange("POST",PREFIX+suffix,body); }
    static void run(OfficialHttpHandler handler,Exchange exchange,String code,int status) throws Exception {
        handler.handle(exchange); check(exchange.closed && exchange.sends == 1 && exchange.status == status);
        check(exchange.json().get("code").getAsString().equals(code));
        check(exchange.length == exchange.output.size() && exchange.response.getFirst("Cache-Control").equals("no-store"));
        check(!exchange.output.toString(StandardCharsets.UTF_8).contains("SYNTHETIC_PRIVATE"));
    }
    static void invalid(Exchange exchange,String code,int status) throws Exception {
        Probe probe=new Probe(); run(probe.handler,exchange,code,status); check(probe.calls == 0);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Explicit synthetic fixture required");
        fixture=OfficialModState.parse(Files.readString(Path.of(args[0]))).getAsJsonObject(); check(fixture.get("synthetic").getAsBoolean()); passed=0;
        test("missing_or_duplicate_authentication_never_reads_body",() -> {
            for (boolean duplicate : List.of(false,true)) {
                Probe probe=new Probe(); Exchange exchange=post("/sync","SYNTHETIC_PRIVATE_BODY"); exchange.forbidRead=true;
                if (duplicate) exchange.request.add("Authorization",AUTH); else exchange.request.remove("Authorization");
                run(probe.handler,exchange,"UNAUTHORIZED",401); check(probe.auth == 0 && probe.calls == 0);
            }
        });
        test("failed_runtime_authentication_precedes_route_and_body_parsing",() -> {
            AtomicInteger dispatch=new AtomicInteger(); Exchange exchange=post("/sync","SYNTHETIC_PRIVATE_BODY"); exchange.forbidRead=true;
            OfficialHttpHandler handler=new OfficialHttpHandler(h -> { throw new OfficialException("UNAUTHORIZED"); },(m,p,r,h) -> { dispatch.incrementAndGet(); throw new AssertionError(); });
            run(handler,exchange,"UNAUTHORIZED",401); check(dispatch.get() == 0);
        });
        test("python_style_get_scope_decodes_protocol_number_and_exact_openid",() -> {
            Probe probe=new Probe(); OfficialScope scope=new OfficialScope(S.environmentId(),"SYNTHETIC_群+Case",S.serverId());
            Exchange exchange=new Exchange("GET",PREFIX+"/capabilities?"+query(scope),""); run(probe.handler,exchange,"NOT_SUPPORTED",400);
            check(probe.calls == 1 && probe.method.equals("GET") && probe.path.equals(PREFIX+"/capabilities") && probe.header.equals(AUTH));
            check(probe.input.equals(scope.wire()) && probe.input.get("protocol_version").getAsJsonPrimitive().isNumber());
        });
        test("get_check_query_preserves_cursor_and_numeric_page_limit",() -> {
            Probe probe=new Probe(); Exchange exchange=new Exchange("GET",PREFIX+"/check?"+query(S)+"&user_id=SYNTHETIC_USER_A&limit=100&cursor=A%2BB%2FC%3D","");
            run(probe.handler,exchange,"NOT_SUPPORTED",400); check(probe.input.get("limit").getAsJsonPrimitive().isNumber() && probe.input.get("limit").getAsInt() == 100);
            check(probe.input.get("cursor").getAsString().equals("A+B/C="));
        });
        test("get_rejects_unknown_duplicate_and_control_query_fields",() -> {
            for (String extra : List.of("&token_code=123456","&authorization=SYNTHETIC_PRIVATE","&%67roup_id=SYNTHETIC_OTHER","&server_id=duplicate","&group_id=%00","&","&missing"))
                invalid(new Exchange("GET",PREFIX+"/capabilities?"+query(S)+extra,""),"INVALID_INPUT",400);
        });
        test("malformed_utf8_and_controls_are_not_silently_replaced",() -> {
            for (String group : List.of("%FF","%C0%AF","%ED%A0%80","%E7%BE","%0A","%7F"))
                invalid(new Exchange("GET",PREFIX+"/capabilities?protocol_version=2&group_id="+group,""),"INVALID_INPUT",400);
        });
        test("query_version_and_limit_have_fixed_wire_types",() -> {
            for (String version : List.of("02","2.0","true","3")) invalid(new Exchange("GET",PREFIX+"/capabilities?protocol_version="+version,""),"NOT_SUPPORTED",400);
            for (String limit : List.of("0","01","101","-1","1.0","true")) invalid(new Exchange("GET",PREFIX+"/check?"+query(S)+"&limit="+limit,""),"INVALID_INPUT",400);
        });
        test("query_size_is_bounded_before_dispatch",() -> {
            invalid(new Exchange("GET",PREFIX+"/check?"+query(S)+"&cursor="+"A".repeat(8193),""),"LIMIT_EXCEEDED",400);
        });
        test("get_body_and_post_query_are_rejected_without_dispatch",() -> {
            Exchange get=new Exchange("GET",PREFIX+"/capabilities?"+query(S),"{}"); get.forbidRead=true; invalid(get,"INVALID_INPUT",400);
            Exchange post=new Exchange("POST",PREFIX+"/sync?token_code=123456","{}"); post.forbidRead=true; invalid(post,"INVALID_INPUT",400);
            Exchange hidden=new Exchange("GET",PREFIX+"/capabilities?"+query(S),"{}"); hidden.request.remove("Content-Length"); invalid(hidden,"INVALID_INPUT",400);
        });
        test("post_preserves_json_types_and_never_moves_code_to_url",() -> {
            Probe probe=new Probe(); JsonObject body=S.wire(); body.addProperty("token_code","123456");
            Exchange exchange=post("/token/validate",body.toString()); exchange.request.set("Content-Type","Application/JSON;\tcharset=\"UTF-8\"");
            run(probe.handler,exchange,"NOT_SUPPORTED",400); check(probe.calls == 1 && probe.input.equals(body) && probe.path.equals(PREFIX+"/token/validate"));
            check(!exchange.output.toString(StandardCharsets.UTF_8).contains("123456"));
        });
        test("json_requires_object_strict_syntax_unique_keys_and_shallow_nesting",() -> {
            for (String body : List.of("[]","null","{} {}","{token_code:'123456'}","{/*comment*/}","{\"a\":1,\"a\":2}","{\"x\":{\"a\":1,\"a\":2}}","[".repeat(18)+"0"+"]".repeat(18)))
                invalid(post("/sync",body),"INVALID_INPUT",400);
        });
        test("post_requires_utf8_json_without_compression_or_duplicate_content_type",() -> {
            for (String type : List.of("text/plain","application/json; charset=gbk","application/json; other=1","application/json, text/plain")) {
                Exchange exchange=post("/sync","{}"); exchange.request.set("Content-Type",type); invalid(exchange,"INVALID_INPUT",400);
            }
            Exchange missing=post("/sync","{}"); missing.request.remove("Content-Type"); invalid(missing,"INVALID_INPUT",400);
            Exchange duplicate=post("/sync","{}"); duplicate.request.add("Content-Type","application/json"); invalid(duplicate,"INVALID_INPUT",400);
            Exchange zipped=post("/sync","{}"); zipped.request.set("Content-Encoding","gzip"); invalid(zipped,"INVALID_INPUT",400);
            invalid(new Exchange("POST",PREFIX+"/sync",new byte[]{'{','"','x','"',':','"',(byte)0xFF,'"','}'}),"INVALID_INPUT",400);
        });
        test("exact_one_mib_json_is_accepted_and_larger_body_is_bounded",() -> {
            String exact="{}"+" ".repeat(OfficialHttpHandler.MAX_BYTES-2); Probe probe=new Probe(); run(probe.handler,post("/sync",exact),"NOT_SUPPORTED",400); check(probe.calls == 1);
            Exchange over=post("/sync",exact+" "); over.request.remove("Content-Length"); over.request.set("Transfer-Encoding","chunked"); invalid(over,"LIMIT_EXCEEDED",400);
            Exchange announced=post("/sync","{}"); announced.request.set("Content-Length",Integer.toString(OfficialHttpHandler.MAX_BYTES+1)); announced.forbidRead=true; invalid(announced,"LIMIT_EXCEEDED",400);
        });
        test("framing_rejects_duplicate_ambiguous_negative_and_truncated_lengths",() -> {
            for (String length : List.of("-1","2,2","1","3","99999999999")) {
                Exchange exchange=post("/sync","{}"); exchange.request.set("Content-Length",length); invalid(exchange,"INVALID_INPUT",400);
            }
            Exchange duplicate=post("/sync","{}"); duplicate.request.add("Content-Length","2"); invalid(duplicate,"INVALID_INPUT",400);
            Exchange both=post("/sync","{}"); both.request.set("Transfer-Encoding","chunked"); invalid(both,"INVALID_INPUT",400);
            Exchange transfer=post("/sync","{}"); transfer.request.remove("Content-Length"); transfer.request.set("Transfer-Encoding","gzip, chunked"); invalid(transfer,"INVALID_INPUT",400);
        });
        test("chunked_json_dispatches_once",() -> {
            Probe probe=new Probe(); Exchange exchange=post("/token/validate",S.wire().toString()); exchange.request.remove("Content-Length"); exchange.request.set("Transfer-Encoding","chunked");
            run(probe.handler,exchange,"NOT_SUPPORTED",400); check(probe.calls == 1);
        });
        test("routes_do_not_accept_encoded_or_prefix_aliases_or_wrong_methods",() -> {
            for (String path : List.of(PREFIX+"/",PREFIX+"extra/capabilities",PREFIX+"/%73ync",PREFIX+"/../binding/sync",PREFIX+"//sync",PREFIX+"/sync/",PREFIX+"/unknown"))
                invalid(new Exchange("POST",path,"{}"),"NOT_SUPPORTED",400);
            invalid(new Exchange("DELETE",PREFIX+"/sync","{}"),"NOT_SUPPORTED",400);
            invalid(get("/operations/"+OP+"/extra"),"INVALID_INPUT",400);
            invalid(new Exchange("POST","http://example.invalid"+PREFIX+"/sync","{}"),"INVALID_INPUT",400);
        });
        test("unexpected_failure_after_mutation_dispatch_is_unknown_and_not_replayed",() -> {
            Probe probe=new Probe(); probe.before=() -> { throw new IllegalStateException("SYNTHETIC_PRIVATE_FAILURE"); };
            run(probe.handler,post("/sync","{}"),"RESULT_UNKNOWN",202); check(probe.calls == 1);
            run(probe.handler,get("/capabilities"),"STORAGE_ERROR",503); check(probe.calls == 2);
            Probe unknown=new Probe(); unknown.before=() -> { throw new OfficialException("SYNTHETIC_PRIVATE_UNKNOWN_CODE"); };
            run(unknown.handler,get("/capabilities"),"STORAGE_ERROR",503);
        });
        test("oversized_or_missing_response_does_not_claim_mutation_failure",() -> {
            Probe probe=new Probe(); JsonObject data=new JsonObject(); data.addProperty("padding","x".repeat(OfficialHttpHandler.MAX_BYTES+1)); probe.reply=new OfficialTokenApi.Reply(200,data);
            run(probe.handler,post("/sync","{}"),"RESULT_UNKNOWN",202); check(probe.calls == 1);
            probe.reply=null; run(probe.handler,get("/capabilities"),"STORAGE_ERROR",503); check(probe.calls == 2);
        });
        test("lost_output_closes_exchange_without_replaying_or_sending_second_response",() -> {
            Probe probe=new Probe(); Exchange exchange=post("/sync","{}"); exchange.failWrite=true; probe.handler.handle(exchange);
            check(probe.calls == 1 && exchange.closed && exchange.sends == 1);
        });
        test("eight_in_flight_dispatches_reject_overload_then_release_capacity",() -> {
            ExecutorService pool=Executors.newFixedThreadPool(8); CountDownLatch entered=new CountDownLatch(8),release=new CountDownLatch(1); AtomicInteger calls=new AtomicInteger();
            OfficialHttpHandler handler=new OfficialHttpHandler(h -> {},(m,p,r,h) -> {
                calls.incrementAndGet(); entered.countDown(); try { check(release.await(3,TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(); }
                return OfficialTokenApi.error("NOT_SUPPORTED");
            });
            try {
                List<Future<?>> pending=new ArrayList<>();
                for (int i=0;i<8;i++) pending.add(pool.submit(() -> handler.handle(get("/capabilities"))));
                check(entered.await(2,TimeUnit.SECONDS)); Exchange overload=get("/capabilities"); overload.forbidRead=true;
                run(handler,overload,"DB_UNAVAILABLE",503); check(calls.get() == 8); release.countDown();
                for (Future<?> task : pending) task.get(3,TimeUnit.SECONDS);
                run(handler,get("/capabilities"),"NOT_SUPPORTED",400); check(calls.get() == 9);
            } finally { release.countDown(); pool.shutdownNow(); }
        });
        test("real_token_core_validate_reserve_operations_and_finalize_use_same_http_boundary",() -> {
            try (var c=new OfficialTokenTests.Case()) {
                OfficialHttpHandler handler=new OfficialHttpHandler(h -> { if (!AUTH.equals(h)) throw new OfficialException("UNAUTHORIZED"); },(m,p,r,h) -> c.api.dispatch(m,p,r,true));
                JsonObject body=S.wire(); body.addProperty("token_code",c.issued.tokenCode());
                Exchange validate=post("/token/validate",body.toString()); run(handler,validate,"OK",200); check(validate.json().getAsJsonObject("data").get("token_generation").getAsString().equals(c.issued.tokenGeneration()));
                body.addProperty("operation_id",OP); body.addProperty("user_id","SYNTHETIC_USER_A"); body.addProperty("game_key","player_a"); body.addProperty("token_generation",c.issued.tokenGeneration());
                Exchange reserve=post("/token/reserve",body.toString()); run(handler,reserve,"OK",200); String reservation=reserve.json().getAsJsonObject("data").get("reservation_id").getAsString();
                Exchange operation=get("/operations/"+OP); run(handler,operation,"PREPARED",202); check(operation.json().getAsJsonObject("data").getAsJsonObject("reservation").get("state").getAsString().equals("RESERVED"));
                c.center.state="COMMITTED"; body=S.wire(); body.addProperty("operation_id",OP); body.addProperty("reservation_id",reservation); body.addProperty("decision","COMMITTED");
                Exchange finalize=post("/token/finalize",body.toString()); run(handler,finalize,"OK",200); check(finalize.json().getAsJsonObject("data").get("token_state").getAsString().equals("CONSUMED"));
                check(!Files.readString(c.dir.resolve("official-state.json")).contains("123456"));
            }
        });
        test("session_http_rechecks_scope_publisher_permission_and_stopped_owner",() -> {
            var registry=new OfficialRuntimeRegistry(); var settings=OfficialRuntimeTests.selected(registry,OfficialRuntimeTests.config());
            try (var session=new OfficialRuntimeSession(registry,settings,OfficialRuntimeTests.root(),OfficialRuntimeTests::forbiddenDriver,() -> false)) {
                OfficialHttpHandler handler=new OfficialHttpHandler(session::authorizeHttp,session::dispatch);
                run(handler,get("/capabilities"),"NOT_SUPPORTED",400);
                JsonObject wrong=OfficialRuntimeTests.request(); wrong.addProperty("group_id","SYNTHETIC_OTHER_GROUP"); run(handler,post("/sync",wrong.toString()),"SCOPE_MISMATCH",404);
                run(handler,post("/sync",OfficialRuntimeTests.request().toString()),"OK",200); check(session.isBoundFast("Player_A"));
                check(session.stop(1000)); Exchange stopped=post("/sync","SYNTHETIC_PRIVATE_BODY"); stopped.forbidRead=true; run(handler,stopped,"NOT_SUPPORTED",400);
            }
        });
        test("stop_between_http_preflight_and_dispatch_cannot_publish_mirror",() -> {
            var registry=new OfficialRuntimeRegistry(); var settings=OfficialRuntimeTests.selected(registry,OfficialRuntimeTests.config());
            try (var session=new OfficialRuntimeSession(registry,settings,OfficialRuntimeTests.root(),OfficialRuntimeTests::forbiddenDriver,() -> false)) {
                OfficialHttpHandler handler=new OfficialHttpHandler(h -> { session.authorizeHttp(h); check(session.stop(1000)); },session::dispatch);
                run(handler,post("/sync",OfficialRuntimeTests.request().toString()),"NOT_SUPPORTED",400); check(!session.isBoundFast("Player_A"));
            }
        });
        test("valid_bearer_without_center_publication_permission_cannot_sync",() -> {
            JsonObject json=OfficialRuntimeTests.config(); json.getAsJsonObject("official").addProperty("allowCenterSync",false);
            var registry=new OfficialRuntimeRegistry(); var settings=OfficialRuntimeTests.selected(registry,json);
            try (var session=new OfficialRuntimeSession(registry,settings,OfficialRuntimeTests.root(),OfficialRuntimeTests::forbiddenDriver,() -> false)) {
                run(new OfficialHttpHandler(session::authorizeHttp,session::dispatch),post("/sync",OfficialRuntimeTests.request().toString()),"UNAUTHORIZED",401); check(!session.isBoundFast("Player_A"));
            }
        });
        test("http_registration_does_not_open_unwired_bind_unbind_or_check",() -> {
            var registry=new OfficialRuntimeRegistry(); var settings=OfficialRuntimeTests.selected(registry,OfficialRuntimeTests.config());
            try (var session=new OfficialRuntimeSession(registry,settings,OfficialRuntimeTests.root(),OfficialRuntimeTests::forbiddenDriver,() -> false)) {
                var handler=new OfficialHttpHandler(session::authorizeHttp,session::dispatch);
                for (String path : List.of("/bind","/unbind")) run(handler,post(path,S.wire().toString()),"NOT_SUPPORTED",400);
                run(handler,get("/check"),"NOT_SUPPORTED",400); check(!session.isBoundFast("Player_A"));
            }
        });
        test("fresh_initialization_http_never_reads_body_or_opens_driver",() -> {
            JsonObject json=OfficialRuntimeTests.config(); json.getAsJsonObject("official").addProperty("createFreshState",true);
            var registry=new OfficialRuntimeRegistry(); var settings=OfficialRuntimeTests.selected(registry,json); Path root=Files.createTempDirectory("official-http-fresh-synthetic-");
            try (var session=new OfficialRuntimeSession(registry,settings,root,OfficialRuntimeTests::forbiddenDriver,() -> false)) {
                var exchange=post("/sync","SYNTHETIC_PRIVATE_BODY"); exchange.forbidRead=true;
                run(new OfficialHttpHandler(session::authorizeHttp,session::dispatch),exchange,"NOT_SUPPORTED",400); check(!session.active());
            }
        });
        System.out.println("Official HTTP: " + passed + " tests passed (in-memory exchanges; no network/server)");
    }
}
