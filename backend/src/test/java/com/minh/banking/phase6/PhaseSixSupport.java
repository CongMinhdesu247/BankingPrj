package com.minh.banking.phase6;

import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import com.minh.banking.user.enums.RoleCode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

abstract class PhaseSixSupport extends TransferIntegrationTestSupport {
    protected HttpRequest asRequest(String token,String method,String path,String body,String key) {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path))
            .timeout(Duration.ofSeconds(12)).header("Content-Type","application/json").header("X-Request-Id","transfer-it");
        if (token!=null) builder.header("Authorization",token);
        if (key!=null) builder.header("Idempotency-Key",key);
        return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    }
    protected HttpResponse<String> as(String token,String method,String path,String body,String key) throws Exception {
        return http.send(asRequest(token,method,path,body,key),HttpResponse.BodyHandlers.ofString());
    }
    protected HttpResponse<String> transfer(String key,String amount) throws Exception {
        return as(testUsers.customer(SOURCE),"POST","/transfers",payload(SOURCE,DESTINATION,amount),key);
    }
    protected HttpResponse<String> limit(String per,String daily) throws Exception {
        return as(testUsers.bearer(RoleCode.ADMIN,null),"PUT","/accounts/"+SOURCE+"/transfer-limit",
            "{\"perTransferAmount\":\""+per+"\",\"dailyAmount\":\""+daily+"\"}",null);
    }
    protected int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    protected long actions(String action) { return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action=?",Long.class,action); }
    protected List<HttpResponse<String>> together(HttpRequest... requests) throws Exception {
        var barrier=new CyclicBarrier(requests.length);
        try (var executor=Executors.newFixedThreadPool(requests.length)) {
            List<Future<HttpResponse<String>>> tasks=new ArrayList<>();
            for (var request:requests) tasks.add(executor.submit(()->{
                barrier.await(5,TimeUnit.SECONDS); return http.send(request,HttpResponse.BodyHandlers.ofString());
            }));
            List<HttpResponse<String>> result=new ArrayList<>();
            for (var task:tasks) result.add(task.get(12,TimeUnit.SECONDS));
            return result;
        }
    }
}
