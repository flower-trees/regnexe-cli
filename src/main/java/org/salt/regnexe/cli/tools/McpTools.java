package org.salt.regnexe.cli.tools;

import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.mcp.McpClient;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolDesc;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolResult;
import org.salt.regnexe.cli.ui.CliRenderer;
import org.salt.regnexe.cli.ui.ConfirmChoice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapts an already-connected {@link McpClient}'s tools for one server into confirmation-gated
 * {@link Tool} objects, for use with {@code RegnexeAgentBuilder.withTool(...)}. Deliberately does
 * not use {@code McpAgentExecutor.Builder.tools(McpClient, String)} (j-langchain) — that bridge
 * calls {@code mcpClient.callTool(...)} directly with no confirmation, and it registers tools
 * straight into one executor's tool list rather than the marketplace, bypassing Search/Plan.
 */
public class McpTools {

    private McpTools() {}

    /**
     * Builds one confirmation-gated {@link Tool} per tool the server exposes. {@code namePrefix}
     * becomes the capabilityId prefix — {@code <server>} for a directly-configured server,
     * {@code <pluginId>_<server>} for one carried by a Plugin — so the final tool name is
     * {@code <namePrefix>_<toolName>}. Joined with {@code _} rather than {@code .}: this string
     * is sent verbatim as the function-calling tool name, and OpenAI-compatible chat completion
     * APIs reject any name that doesn't match {@code ^[a-zA-Z0-9_-]+$} — confirmed against a real
     * deepseek 400 response (a dotted name was rejected outright). {@code ManifestPluginLoader}'s
     * {@code pluginId + "." + name} avoids this because that dotted string is only ever the
     * marketplace-internal capabilityId there — the actual {@code Tool} it registers keeps the
     * bare, undotted name; the two are deliberately different strings. This class doesn't have
     * that luxury (registration goes through {@code withTool()}, which forces capabilityId and
     * {@code Tool.name} to be the same string), so {@code _} is used everywhere instead of mixing
     * separators.
     */
    public static List<Tool> forServer(McpClient client, String serverName, String namePrefix,
                                       CliRenderer renderer, Runnable pauseAction) {
        List<Tool> tools = new ArrayList<>();
        List<ToolDesc> descs = client.listAllTools().getOrDefault(serverName, List.of());
        for (ToolDesc desc : descs) {
            String capabilityId = namePrefix + "_" + desc.getName();
            tools.add(Tool.builder()
                    .name(capabilityId)
                    .description(desc.getDescription() != null ? desc.getDescription() : desc.getName())
                    .params(schemaToParams(desc.getInputSchema()))
                    // The real, unmodified JSON Schema — sent directly as the native function-
                    // calling `parameters` field (McpAgentExecutor.toAiTool() prefers this over
                    // re-deriving one from `params`). `params` above still feeds whatever
                    // text-based tool listing PromptTemplate renders elsewhere; this is the one
                    // that actually reaches the LLM API's tool schema, types/required included.
                    .parametersSchema(asSchemaMap(desc.getInputSchema()))
                    .func(raw -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> args = (raw instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
                        renderer.mcpToolPreview(serverName, desc.getName(), args);
                        ConfirmChoice choice = renderer.confirm("call", capabilityId);
                        if (choice == ConfirmChoice.PAUSE) {
                            if (pauseAction != null) pauseAction.run();
                            return "Task paused by user.";
                        }
                        if (choice != ConfirmChoice.YES && choice != ConfirmChoice.ALWAYS) {
                            return "Call cancelled by user.";
                        }
                        try {
                            ToolResult result = client.callTool(serverName, desc.getName(), args);
                            if (result == null) return "";
                            return result.isError()
                                    ? "Error: " + result.getAllText()
                                    : result.getAllText();
                        } catch (Exception e) {
                            return "Error calling MCP tool '" + capabilityId + "': " + e.getMessage();
                        }
                    })
                    .build());
        }
        return tools;
    }

    /** Casts an MCP tool's {@code inputSchema} (already a parsed JSON-object map) for direct use
     * as the native function-calling {@code parameters} field — {@code null}/non-map schemas (an
     * MCP tool that declares no parameters) pass through as {@code null}, matching what an absent
     * schema means to {@code McpAgentExecutor.toAiTool()}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asSchemaMap(Object inputSchema) {
        return (inputSchema instanceof Map<?, ?>) ? (Map<String, Object>) inputSchema : null;
    }

    /**
     * Flattens a JSON Schema's {@code properties} into the {@code "name: Type"} comma-separated
     * format {@link Tool#getParams()} expects — mirrors the private
     * {@code McpAgentExecutor.Builder.schemaToParams(Object)} in j-langchain (not reused directly
     * since that method isn't public; duplicated rather than widening that class's visibility,
     * same reasoning as {@code CapabilityExecutor.buildSkillSystemPrompt()} not touching
     * {@code Skill.java}'s internals). Nested {@code object}/{@code array} properties are known to
     * degrade to {@code String} — the model passes those as a JSON-encoded string rather than a
     * structured value; that's a limitation of the {@code "name: Type"} params format itself, not
     * something specific to MCP tools.
     *
     * <p>Each property's own {@code description} (a Zod {@code .describe()} in the common case) is
     * appended after the type, and properties absent from the schema's top-level {@code required}
     * array are marked {@code (optional)}. This isn't cosmetic: a real Playwright MCP session
     * showed the model burning ~15 tool-call round trips blindly guessing values for
     * {@code browser_snapshot}'s {@code target} param ("page", "main", "browser", ...) — the type
     * alone ({@code String}) was correct the whole time, but the actual constraint ("exact element
     * ref from a prior snapshot, or omit for the whole page") only existed in the per-property
     * {@code description} this method used to discard. Losing that text, not the type mapping, was
     * the real driver of the flailing.
     */
    @SuppressWarnings("unchecked")
    static String schemaToParams(Object inputSchema) {
        if (inputSchema == null) return "";
        try {
            Map<String, Object> schema = (Map<String, Object>) inputSchema;
            Object propsObj = schema.get("properties");
            if (!(propsObj instanceof Map)) return "";
            Map<String, Object> props = (Map<String, Object>) propsObj;
            java.util.Set<String> required = new java.util.HashSet<>();
            if (schema.get("required") instanceof java.util.List<?> reqList) {
                for (Object r : reqList) required.add(String.valueOf(r));
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> e : props.entrySet()) {
                String pName = e.getKey();
                String pType = "String";
                String pDesc = null;
                if (e.getValue() instanceof Map<?, ?> pDef) {
                    Object t = pDef.get("type");
                    if (t != null) {
                        pType = switch (t.toString()) {
                            case "integer" -> "int";
                            case "number" -> "double";
                            case "boolean" -> "boolean";
                            default -> "String";
                        };
                    }
                    Object d = pDef.get("description");
                    if (d != null) pDesc = d.toString();
                }
                if (sb.length() > 0) sb.append(", ");
                sb.append(pName).append(": ").append(pType);
                if (!required.contains(pName)) sb.append(" (optional)");
                if (pDesc != null && !pDesc.isBlank()) {
                    String truncated = pDesc.length() > 220 ? pDesc.substring(0, 220) + "..." : pDesc;
                    sb.append(" — ").append(truncated);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
