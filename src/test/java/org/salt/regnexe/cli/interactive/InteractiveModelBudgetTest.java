package org.salt.regnexe.cli.interactive;

import org.junit.Test;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.regnexe.agent.core.task.state.*;
import org.salt.regnexe.agent.core.task.worker.BudgetedChatModel;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class InteractiveModelBudgetTest {
    private static class Model extends BaseChatModel {
        private final InteractiveTaskRunner runner;
        int calls;
        Model(InteractiveTaskRunner runner) { this.runner=runner; }
        @Override public AIMessage invoke(Object input) {
            if (++calls==1) assertTrue(runner.steer("additional instruction"));
            return AIMessage.builder().content("local response").build();
        }
        @Override public void otherInformation(AiChatInput input) {}
        @Override public Class<? extends AiChatActuator> getActuator() { throw new AssertionError("No network"); }
        @Override public BaseChatModel copy() { return this; }
    }
    @Test public void steeringReRequestsCountAtUnderlyingRequestBoundary() throws Exception {
        for (int cap : new int[]{1,2}) {
            try(var runner=new InteractiveTaskRunner()) {
                var state=new TaskExecutionState();var request=new TaskRequest();var budget=new ExecutionBudget();
                budget.setMaxModelCalls(cap);request.setExecutionBudget(budget);state.setRequest(request);
                var delegate=new Model(runner);
                var model=BudgetedChatModel.wrap(new InteractiveChatModel(delegate,runner),state,"execute");
                AtomicReference<Throwable> error=new AtomicReference<>();
                runner.start(run->model.invoke("local input"),error::set);
                assertTrue(runner.awaitIdle(Duration.ofSeconds(3)));
                assertEquals(cap,delegate.calls);
                assertEquals(Integer.valueOf(cap),state.getModelCallAttempts().get("execute"));
                if(cap==1) {assertNotNull(error.get());assertEquals("MODEL_CALL_LIMIT",state.getTerminationReason());}
                else assertNull(error.get());
            }
        }
    }
}
