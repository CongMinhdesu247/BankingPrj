package com.minh.banking.auth.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** RFC 6238 SHA-1/30-second TOTP, encrypted at rest with a domain-separated key. */
@Component
public class Totp {
    private static final String ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final byte[] key;
    public Totp(@Value("${banking.security.jwt.secret}") String signingSecret) {
        try { var digest=MessageDigest.getInstance("SHA-256"); digest.update("banking:totp:v1:".getBytes(StandardCharsets.UTF_8)); key=digest.digest(Base64.getDecoder().decode(signingSecret)); }
        catch(Exception error) { throw new IllegalStateException("TOTP encryption key unavailable",error); }
    }
    public String newSecret() { byte[] bytes=new byte[20]; new SecureRandom().nextBytes(bytes); return base32(bytes); }
    public static String base32(byte[] bytes) {
        var result=new StringBuilder(); int bits=0,value=0;
        for(byte b:bytes) { value=(value<<8)|(b&255);bits+=8;while(bits>=5) {bits-=5;result.append(ALPHABET.charAt((value>>>bits)&31));} }
        if(bits>0) result.append(ALPHABET.charAt((value<<(5-bits))&31)); return result.toString();
    }
    private static byte[] decode(String value) {
        var out=new java.io.ByteArrayOutputStream();int bits=0,buffer=0;
        for(char c:value.toCharArray()) {int n=ALPHABET.indexOf(c);if(n<0)throw new IllegalArgumentException("Invalid base32");buffer=(buffer<<5)|n;bits+=5;if(bits>=8){bits-=8;out.write((buffer>>>bits)&255);}} return out.toByteArray();
    }
    public static String code(String secret,long step,int digits) {
        try {var mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(decode(secret),"HmacSHA1"));byte[] h=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());int o=h[h.length-1]&15;
            int binary=((h[o]&127)<<24)|((h[o+1]&255)<<16)|((h[o+2]&255)<<8)|(h[o+3]&255);
            return String.format(Locale.ROOT,"%0"+digits+"d",binary%(digits==8?100000000:1000000));
        } catch(GeneralSecurityException error) {throw new IllegalStateException(error);}
    }
    public long match(String secret,String value,long epochSeconds,long lastStep) {
        if(value==null||!value.matches("[0-9]{6}"))return -1;
        long current=epochSeconds/30;
        for(long s=current-1;s<=current+1;s++)if(s>lastStep&&MessageDigest.isEqual(code(secret,s,6).getBytes(StandardCharsets.US_ASCII),value.getBytes(StandardCharsets.US_ASCII)))return s;
        return -1;
    }
    public String encrypt(UUID user,String secret) {
        try {byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));cipher.updateAAD(user.toString().getBytes(StandardCharsets.US_ASCII));byte[] body=cipher.doFinal(secret.getBytes(StandardCharsets.US_ASCII));return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length+body.length).put(iv).put(body).array());}
        catch(GeneralSecurityException error){throw new IllegalStateException(error);}
    }
    public String decrypt(UUID user,String value) {
        try {byte[] bytes=Base64.getDecoder().decode(value);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));cipher.updateAAD(user.toString().getBytes(StandardCharsets.US_ASCII));return new String(cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),StandardCharsets.US_ASCII);}
        catch(GeneralSecurityException error){throw new IllegalStateException("Unable to read second factor",error);}
    }
    public static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException error){throw new IllegalStateException(error);}}
}
