package com.minh.banking.transaction.dto.request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Money must arrive as a JSON string, never a coerced JSON floating-point number. */
public class MoneyStringDeserializer extends ValueDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return context.reportInputMismatch(String.class, "amount must be a decimal string");
        }
        return parser.getString();
    }
}
