import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.salt.regnexe.cli.config.RexConfig;
import org.salt.regnexe.cli.event.OpikEventListener;
import org.salt.regnexe.cli.observe.OtlpSpanExporter;
import org.salt.regnexe.agent.core.event.*;

/** P02: actual packaged Shell backend plus clearly labelled OTLP preflight, no Agent/model. */
public class SkillEnvironmentProbe {
    static final ObjectMapper JSON = new ObjectMapper();
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toRealPath();
        String python = args[1]; Path output = Path.of(args[2]);
        String task = UUID.randomUUID().toString(), sessionId = UUID.randomUUID().toString();
        String sessionName = "preflight-p02-" + task.substring(0, 8);
        var config = new RexConfig.OpikConfig();
        config.setEndpoint("http://localhost:28173/api/v1/private/otel");
        config.setProject("regnexe-skill-preflight");
        var exporter = new OtlpSpanExporter(config);
        var model = new OpikEventListener.ModelRef("preflight", "no-model");
        var listener = new OpikEventListener(exporter, () -> sessionId, () -> sessionName, 20000, model, model, model);
        String root = SpanIds.rootSpanId(task), phase = SpanIds.next();
        listener.onEvent(AgentEvent.of(task, 0, EventType.AGENT_STARTED,
                "P02 PREFLIGHT ONLY: actual controlled Shell checks; no LLM, business DB or scores").inSpan(root, null));
        listener.onEvent(AgentEvent.of(task, 0, EventType.EXECUTION_STARTED, "P02 Shell preflight").inSpan(phase, root));
        Method execute = Class.forName("org.salt.regnexe.cli.tools.ShellCommandExecutor").getDeclaredMethod(
                "execute", String.class, Path.class, Duration.class, int.class, BooleanSupplier.class);
        execute.setAccessible(true);
        String success = "python3 -I -B probe-business.py";
        String[] commands = {success, "printf 'controlled stderr marker\\n' >&2; exit 7", "printf '%0512d' 0", "sleep 2"};
        String[] names = {"cwd_utf8_imports", "nonzero_stderr", "bounded_capture", "deadline"};
        var results = new ArrayList<Map<String,Object>>();
        for (int n = 0; n < commands.length; n++) {
            String tool = SpanIds.next();
            listener.onEvent(AgentEvent.of(task, 0, EventType.TOOL_CALLED,
                    "bash " + JSON.writeValueAsString(Map.of("command",commands[n],"preflight",true))).inSpan(tool, phase));
            long start = System.nanoTime();
            String result = (String) execute.invoke(null, commands[n], workspace,
                    n == 3 ? Duration.ofMillis(250) : Duration.ofSeconds(5), n == 2 ? 128 : 2000, (BooleanSupplier) () -> false);
            long elapsed = (System.nanoTime() - start) / 1000000;
            boolean pass = switch(n) {
                case 0 -> result.startsWith("Exit: 0") && result.contains("中文😀") && Files.exists(workspace.resolve("business.json"));
                case 1 -> result.startsWith("Exit: 7") && result.contains("controlled stderr marker");
                case 2 -> result.startsWith("Exit: 0") && result.contains("saved copy was cut off") && result.length() < 500;
                default -> result.startsWith("Error: timed out") && elapsed < 2500;
            };
            results.add(Map.of("check",names[n],"passed",pass,"elapsed_ms",elapsed,"result",result));
            listener.onEvent(AgentEvent.of(task, 0, EventType.TOOL_RESULT,"bash -> " + result).inSpan(tool, phase));
        }
        boolean passed = results.stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed")));
        listener.onEvent(AgentEvent.of(task,0,EventType.EXECUTION_COMPLETED,
                (passed ? "SUCCESS" : "FAILED") + " | P02 preflight only; four controlled checks, zero model calls").inSpan(phase,root));
        listener.onEvent(AgentEvent.of(task,0,EventType.AGENT_COMPLETED,
                "Status: " + (passed ? "FINISHED" : "FAILED") + " | PREFLIGHT_NOT_BUSINESS_EVALUATION").inSpan(root,null));
        exporter.flush(15000);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                "checks",results,"passed",passed,"task_id",task,"session_name",sessionName,"session_id",sessionId,
                "project","regnexe-skill-preflight","model_calls",0,"business_db_connections",0));
        if (!passed) throw new IllegalStateException("Controlled Shell check failed; inspect JSON");
    }
}
