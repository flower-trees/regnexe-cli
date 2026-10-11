package org.salt.regnexe.cli.db;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.HumanMessage;
import org.salt.jlangchain.core.message.SystemMessage;
import org.salt.jlangchain.core.message.ToolMessage;
import org.salt.jlangchain.utils.JsonUtil;

import java.io.IOException;

/**
 * Reads a stored message back as its real subtype (HumanMessage / AIMessage / SystemMessage /
 * ToolMessage), chosen by its {@code role}.
 *
 * <p>Those classes have no no-arg constructor, so Jackson cannot instantiate them on its own.
 * {@link BaseMessageDeserMixin} works around that by reading everything as a plain BaseMessage,
 * which is fine for conversation turns but not for a task's saved agent steps: those hold
 * {@code AIMessage} fields and need a ToolMessage's tool calls / tool call id intact to be
 * replayed on resume. Here the instance is created through the class's builder and then filled
 * in from the JSON.
 */
final class MessageDeserializer<T extends BaseMessage> extends StdDeserializer<T> {

    // Without this module registered, so filling in a message does not recurse back into here.
    private static final ObjectMapper PLAIN = JsonUtil.objectMapper.copy();

    private MessageDeserializer(Class<T> type) {
        super(type);
    }

    /** A copy of the shared mapper that can load messages held in task state. */
    static ObjectMapper loadMapper() {
        SimpleModule module = new SimpleModule("rex-messages");
        module.addDeserializer(BaseMessage.class, new MessageDeserializer<>(BaseMessage.class));
        module.addDeserializer(AIMessage.class, new MessageDeserializer<>(AIMessage.class));
        return JsonUtil.objectMapper.copy().registerModule(module);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        String role = node.path("role").asText("");
        BaseMessage message = switch (role) {
            case "tool" -> ToolMessage.builder().build();
            case "ai" -> AIMessage.builder().build();
            case "human" -> HumanMessage.builder().build();
            case "system" -> SystemMessage.builder().build();
            default -> new BaseMessage();
        };
        return (T) PLAIN.readerForUpdating(message).readValue(node);
    }

    @Override
    public Object deserializeWithType(JsonParser p, DeserializationContext ctxt,
                                      TypeDeserializer typeDeserializer) throws IOException {
        // BaseMessage is polymorphic on "role"; the subtype is resolved above instead.
        return deserialize(p, ctxt);
    }
}
