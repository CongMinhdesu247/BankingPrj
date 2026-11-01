package com.minh.banking.redis;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class RedisKeyFactory {
    private final String prefix;
    private final SecretKey key;
    public RedisKeyFactory(RedisProperties properties, SecretKey jwtSecretKey) {
        prefix=properties.namespace()+":v1:";
        key=new SecretKeySpec(mac(jwtSecretKey,"banking:redis:key-fingerprint:v1"),"HmacSHA256");
    }
    public String prefix() { return prefix; }
    public String login(String username) { return prefix+"login-fail:"+hash(username.strip().toLowerCase(Locale.ROOT)); }
    public String rate(String route,String actor) { return prefix+"rate:"+route+":"+hash(actor); }
    public String customer(UUID id) { return prefix+"customer:"+id; }
    public String counter(UUID account,long window) { return prefix+"tx-count:"+account+":"+window; }
    private String hash(String value) { return HexFormat.of().formatHex(mac(key,value)); }
    private static byte[] mac(SecretKey key,String text) {
        try { var mac=Mac.getInstance("HmacSHA256"); mac.init(key); return mac.doFinal(text.getBytes(StandardCharsets.UTF_8)); }
        catch (GeneralSecurityException exception) { throw new IllegalStateException("Redis fingerprint unavailable",exception); }
    }
}
