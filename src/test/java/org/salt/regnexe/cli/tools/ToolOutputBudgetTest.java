package org.salt.regnexe.cli.tools;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.jline.terminal.Terminal;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.*;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.*;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.regnexe.agent.core.RegnexeAgentBuilder;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.config.RegnexeAgentConfig;
import org.salt.regnexe.agent.core.task.state.ExecutionBudget;
import org.salt.regnexe.cli.config.RexConfig;
import org.salt.regnexe.cli.ui.CliRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.*;

/** Verify the real BashTool return value reaches the real shared model loop, before UI/Trace. */
@RunWith(SpringRunner.class)
@SpringBootTest(classes=ToolOutputBudgetTest.Config.class)
public class ToolOutputBudgetTest {
    @Configuration @EnableAutoConfiguration @Import(RegnexeAgentConfig.class)
    public static class Config {
    }
    @Autowired
    private RegnexeAgentBuilder builder;
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();
    private static class Model extends BaseChatModel {
        int calls;
        List<String> receivedToolMessages = new ArrayList<>();
        @Override
        public AIMessage invoke(Object input) {
            calls++;
            if (calls==1) return AIMessage.builder().content("{\"selectedCapabilityIds\":[\"target\"],\"narrative\":\"local output check\"}").build();
            if (calls==2) {
                var function=new AiChatOutput.ToolCall.Function();
                function.setName("bash");
                function.setArguments("{\"command\":\"printf '%05000d' 0\"}");
                var call=new AiChatOutput.ToolCall();
                call.setId("output-probe");
                call.setType("function");
                call.setFunction(function);
                return ToolMessage.builder().role("assistant").content("").toolCalls(List.of(call)).build();
            }
            if (calls==3 && input instanceof ChatPromptValue chat) {
                chat.getMessages().stream().filter(m -> "tool".equals(m.getRole()))
                        .forEach(m -> receivedToolMessages.add(m.getContent()));
            }
            return AIMessage.builder().content(calls==4?"{\"action\":\"FINISH\",\"reason\":\"observed\"}":"local result").build();
        }
        @Override
        public void otherInformation(AiChatInput input) {
        }
        @Override
        public Class<? extends AiChatActuator> getActuator() {
            throw new AssertionError("No network");
        }
        @Override
        public BaseChatModel copy() {
            return this;
        }
        @Override
        public BaseChatModel withJsonMode() {
            return this;
        }
    }

    @Test
    public void modelSeesBoundedPreviewAndFullOutputPointer() throws Exception {
        var model=new Model();
        var budget=new ExecutionBudget();
        budget.setHardMaxIterations(3);
        budget.setMaxModelCalls(6);
        var config=new RexConfig.ToolsConfig.BashConfig();
        config.setRequireConfirmation(false);
        var writer=new PrintWriter(new StringWriter());
        var terminal=(Terminal)Proxy.newProxyInstance(
                Terminal.class.getClassLoader(),new Class<?>[]{
            Terminal.class
        },
                (proxy,method,args)->method.getName().equals("writer")?writer:null);
        var renderer=(CliRenderer)Proxy.newProxyInstance(
                CliRenderer.class.getClassLoader(),new Class<?>[]{
            CliRenderer.class
        },
                (proxy,method,args)->null);
        var bash=BashTool.bash(new WorkspaceContext(List.of(temporary.getRoot().toPath())),config,terminal,renderer,()->{
        });
        var result=builder.withLlmProvider(spec->model).withExecutionBudget(budget).withMaxRounds(1)
                .withTool(bash).withSkill(SkillConfig.builder().name("target").description("test").systemPrompt("local").build())
                .build().executeSkill("target","produce controlled output","output-session",null);
        assertEquals(TaskStatus.FINISHED,result.getStatus());
        assertEquals(4,model.calls);
        assertEquals(1,model.receivedToolMessages.size());
        String content=model.receivedToolMessages.get(0);
        assertTrue(content,content.contains("Full output saved to"));
        assertTrue("length="+content.length(),content.length()<2800);
        Matcher pointer=Pattern.compile("Full output saved to (.*?) —").matcher(content);
        assertTrue(pointer.find());
        Path saved=Path.of(pointer.group(1));
        try {
            assertEquals(5000,Files.readString(saved).length());
        }
        finally {
            Files.deleteIfExists(saved);
        }
        assertEquals("SUCCESS",result.getState().getRounds().get(0).getExecutionResult().getToolExecutions().get(0).getOutcome());
    }
}
