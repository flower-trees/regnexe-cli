import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.salt.function.flow.FlowEngine;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.common.enums.ExecutionStatus;
import org.salt.regnexe.agent.core.task.state.execution.ExecutionOutput;
import org.salt.regnexe.agent.core.config.RegnexeAgentConfig;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.event.SpanIds;
import org.salt.regnexe.agent.core.event.TokenAggregatingEventListener;
import org.salt.regnexe.agent.core.llm.DefaultModelProvider;
import org.salt.regnexe.agent.core.llm.ModelProvider;
import org.salt.regnexe.agent.core.llm.ModelSpec;
import org.salt.regnexe.agent.core.marketplace.SimpleMarketplace;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.task.state.ExecutionBudget;
import org.salt.regnexe.agent.core.task.state.RoundRecord;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.TaskRequest;
import org.salt.regnexe.agent.core.task.state.plan.PlanOutput;
import org.salt.regnexe.agent.core.task.store.InMemoryTaskStore;
import org.salt.regnexe.agent.core.task.worker.ContextBusKeys;
import org.salt.regnexe.agent.core.task.worker.FactualGrounding;
import org.salt.regnexe.agent.core.task.worker.Reflector;
import org.salt.regnexe.cli.CliMain;
import org.salt.regnexe.cli.config.RexConfig;
import org.salt.regnexe.cli.event.OpikEventListener;
import org.salt.regnexe.cli.observe.OtlpSpanExporter;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Replay only the actual Reflector against the frozen P07 answer. No execution/tools/Planner. */
public class FactualReviewReplay {
    @Configuration
    @EnableAutoConfiguration
    @Import(RegnexeAgentConfig.class)
    public static class Config {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Object cliHelper(String name, String value) throws Exception {
        Method method = CliMain.class.getDeclaredMethod(name, String.class);
        method.setAccessible(true);
        return method.invoke(null, value);
    }

    public static void main(String[] args) throws Exception {
        Path inputPath = Path.of(args[0]);
        Path output = Path.of(args[1]);
        boolean mock = args.length == 3;
        Map<String, Object> input = JSON.readValue(Files.readAllBytes(inputPath), new TypeReference<Map<String, Object>>() {});
        RexConfig config = RexConfig.load();
        if (!mock) {
            String key = System.getenv("DEEPSEEK_KEY");
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("DEEPSEEK_KEY missing");
            }
            System.setProperty((String) cliHelper("apiKeyPropFor", "deepseek"), key);
            System.setProperty((String) cliHelper("chatUrlPropFor", "deepseek"), config.getModel().getChatUrl());
        }
        try (var context = new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF).properties("logging.level.root=ERROR").run()) {
            String taskId = UUID.randomUUID().toString();
            String session = "p09-reflect-" + taskId.substring(0, 8);
            var request = new TaskRequest();
            request.setGoal((String) input.get("goal"));
            Map<String, Object> facts = JSON.convertValue(input.get("facts"), new TypeReference<Map<String, Object>>() {});
            request.setAuthoritativeFacts(FactualGrounding.snapshot(facts));
            request.setOfflineTextReview(config.getAgent().getOfflineTextReview());
            var budget = new ExecutionBudget();
            budget.setMaxModelCalls(1);
            request.setExecutionBudget(budget);
            var state = new TaskExecutionState();
            state.setTaskId(taskId);
            state.setRequest(request);
            state.setStatus(TaskStatus.RUNNING);
            state.setCurrentRound(1);
            state.setMaxRounds(1);
            var round = new RoundRecord();
            round.setRoundNumber(1);
            var execution = new ExecutionOutput();
            execution.setStatus(ExecutionStatus.SUCCESS);
            round.setExecutionResult(execution);
            var plan = new PlanOutput();
            plan.setNarrative((String) input.get("planNarrative"));
            plan.setSelectedCapabilityIds(List.of("robot-description-style"));
            round.setPlan(plan);
            state.setRounds(new ArrayList<>(List.of(round)));
            var market = new SimpleMarketplace();
            market.install(PluginDescriptor.builder().pluginId("robot-description-style").name("description")
                    .capabilities(List.of(CapabilityDescriptor.builder().pluginId("robot-description-style")
                            .capabilityId("robot-description-style").type(CapabilityType.SKILL)
                            .skillConfig(SkillConfig.builder().name("robot-description-style").description("review only").build()).build())).build());
            List<Map<String, Object>> events = new ArrayList<>();
            OtlpSpanExporter exporter = mock ? null : new OtlpSpanExporter(config.getOpik());
            var modelRef = new OpikEventListener.ModelRef("deepseek", "deepseek-flash");
            AgentEventListener opik = mock ? null : new OpikEventListener(exporter, () -> session, () -> session, 50000,
                    modelRef, modelRef, modelRef);
            AgentEventListener listener = new TokenAggregatingEventListener(event -> {
                events.add(Map.of("type", event.getType().name(), "text", event.getText() == null ? "" : event.getText()));
                if (opik != null) {
                    opik.dispatch(event);
                }
            });
            ModelProvider provider = new DefaultModelProvider();
            if (mock) {
                String response = Files.readString(Path.of(args[2]));
                provider = spec -> new BaseChatModel() {
                    @Override
                    public AIMessage invoke(Object value) {
                        return AIMessage.builder().content(response).build();
                    }
                    @Override
                    public void otherInformation(AiChatInput value) {}
                    @Override
                    public Class<? extends AiChatActuator> getActuator() {
                        throw new AssertionError("No network in mock");
                    }
                    @Override
                    public BaseChatModel copy() {
                        return this;
                    }
                    @Override
                    public BaseChatModel withJsonMode() {
                        return this;
                    }
                };
            }
            String root = SpanIds.rootSpanId(taskId);
            Map<String, Object> bus = new LinkedHashMap<>();
            bus.put(ContextBusKeys.STATE, state);
            bus.put(ContextBusKeys.CHAIN_ACTOR, context.getBean(ChainActor.class));
            bus.put(ContextBusKeys.LLM_PROVIDER, provider);
            Map<String, Object> kwargs = JSON.convertValue(cliHelper("deepseekThinkingKwargs", "deepseek-flash"),
                    new TypeReference<Map<String, Object>>() {});
            bus.put(ContextBusKeys.DEFAULT_MODEL, ModelSpec.of("deepseek", "deepseek-flash", kwargs));
            bus.put(ContextBusKeys.EVENT_LISTENER, listener);
            bus.put(ContextBusKeys.MARKETPLACE, market);
            bus.put(ContextBusKeys.RUN_SPAN_ID, root);
            bus.put(ContextBusKeys.TASK_STORE, new InMemoryTaskStore());
            bus.put(ContextBusKeys.EXEC_TEXT, input.get("answer"));
            listener.dispatch(AgentEvent.of(taskId, 1, EventType.AGENT_STARTED,
                    "P09 reflection-only replay of P07 trace " + input.get("originalTraceId") + "; no generation or tools").inSpan(root, null));
            String failure = null;
            try {
                FlowEngine engine = context.getBean(FlowEngine.class);
                engine.execute(engine.builder().next(new Reflector()).build(), request, bus);
            } catch (Exception error) {
                failure = error.getClass().getSimpleName();
            }
            listener.dispatch(AgentEvent.of(taskId, 1, EventType.AGENT_COMPLETED,
                    "Status: DIAGNOSTIC_COMPLETE | P09 reflection only | " + JSON.writeValueAsString(round.getReflection())).inSpan(root, null));
            if (exporter != null) {
                exporter.flush(10000);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("kind", mock ? "mock-reflection-only" : "real-reflection-only");
            result.put("task_id", taskId);
            result.put("session", session);
            result.put("original_trace_id", input.get("originalTraceId"));
            result.put("failure", failure);
            result.put("termination_reason", state.getTerminationReason());
            result.put("model_call_attempts", state.getModelCallAttempts());
            result.put("decision", round.getReflection());
            result.put("events", events);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        }
    }
}
