package org.salt.regnexe.cli.db;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import org.salt.jlangchain.core.message.BaseMessage;

/**
 * Jackson mix-in for BaseMessage deserialization from SQLite.
 *
 * HumanMessage / AIMessage / SystemMessage / ToolMessage all lack a no-arg
 * constructor (only the builder constructor exists), so the default
 * @JsonSubTypes on BaseMessage fails at load time.
 *
 * This mix-in overrides @JsonSubTypes to map every role value back to
 * BaseMessage, which does have a no-arg constructor.  All fields (role,
 * content, reasoningContent, etc.) are set via Lombok-generated setters.
 * The role field value ("human"/"ai"/…) is preserved via @JsonTypeInfo
 * visible=true on the original class.
 */
@JsonSubTypes({
        @JsonSubTypes.Type(value = BaseMessage.class, name = "human"),
        @JsonSubTypes.Type(value = BaseMessage.class, name = "ai"),
        @JsonSubTypes.Type(value = BaseMessage.class, name = "system"),
        @JsonSubTypes.Type(value = BaseMessage.class, name = "tool")
})
abstract class BaseMessageDeserMixin {
}
