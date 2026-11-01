package com.minh.banking.kafka;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
public final class EventIds {
    private EventIds() {}
    public static UUID forAggregate(EventType type,UUID id) {
        return UUID.nameUUIDFromBytes(("banking:event:v1:"+type+":"+id).getBytes(StandardCharsets.UTF_8));
    }
}
