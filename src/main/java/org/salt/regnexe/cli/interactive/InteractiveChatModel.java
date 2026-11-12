package org.salt.regnexe.cli.interactive;

import java.util.*;
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.*;
import org.salt.jlangchain.core.prompt.value.*;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.store.TaskStore;
import org.salt.regnexe.agent.core.task.worker.BudgetedChatModel;
import org.salt.regnexe.agent.core.task.worker.ContextBusKeys;
import org.salt.regnexe.agent.core.task.worker.TaskBudgetAwareModel;

/** Decorates synchronous models with live user messages and targeted HTTP cancellation. */
public final class InteractiveChatModel extends BaseChatModel implements TaskBudgetAwareModel {
    private record RequestScope(InteractiveTaskRunner.Run run, int revision, List<AutoCloseable> registrations) {
    }

    private static final ThreadLocal<RequestScope> REQUEST = new ThreadLocal<>();
    private final BaseChatModel delegate;
    private final InteractiveTaskRunner runner;
    private TaskExecutionState budgetState;
    private String budgetPhase;
    @Override
    public BaseChatModel withTaskBudget(TaskExecutionState state, String phase) {
        var copy = new InteractiveChatModel(configuredDelegate(), runner);
        copy.budgetState = state;
        copy.budgetPhase = phase;
        return copy;
    }

    private BaseChatModel meteredDelegate() {
        TaskExecutionState state = budgetState != null ? budgetState
                : BudgetedChatModel.currentTask();
        return BudgetedChatModel.wrap(configuredDelegate(), state,
                budgetPhase == null ? "summary" : budgetPhase);
    }

    public InteractiveChatModel(BaseChatModel delegate, InteractiveTaskRunner runner) {
        this.delegate = delegate;
        this.runner = runner;
        setVendor(delegate.getVendor());
        setModel(delegate.getModel());
        setTemperature(delegate.getTemperature());
        setModelKwargs(delegate.getModelKwargs());
        setTools(delegate.getTools());
    }

    public static void installCancellation(HttpStreamClient client) {
        client.setOkHttpClient(client.getOkHttpClient().newBuilder().addInterceptor(chain -> {
            RequestScope scope = REQUEST.get();
            if (scope == null) {
                return chain.proceed(chain.request());
            }
            scope.registrations.add(scope.run.onCancel(chain.call()::cancel, true));
            if (scope.run.revision() != scope.revision) {
                chain.call().cancel();
            }
            return chain.proceed(chain.request());
        }).build());
    }

    @Override
    public AIMessage invoke(Object input) {
        var run = runner.current();
        if (run == null) {
            return meteredDelegate().invoke(input);
        }
        while (true) {
            run.checkCancelled();
            int revision = run.revision();
            var bus = ContextBus.get();
            TaskExecutionState state = bus == null ? null : bus.getTransmit(ContextBusKeys.STATE);
            run.persistMessages(state);
            if (state != null && bus != null) {
                TaskStore store = bus.getTransmit(ContextBusKeys.TASK_STORE);
                if (store != null) {
                    store.save(state);
                }
            }
            Object prompt = appendMessages(input, run.messages());
            RequestScope scope = new RequestScope(run, revision, new ArrayList<>());
            REQUEST.set(scope);
            try {
                AIMessage answer = meteredDelegate().invoke(prompt);
                run.checkCancelled();
                if (run.revision() != revision) {
                    continue;
                }
                run.observed(revision);
                return answer;
            } catch (RuntimeException e) {
                run.checkCancelled();
                if (run.revision() != revision) {
                    continue;
                }
                throw e;
            } finally {
                REQUEST.remove();
                scope.registrations.forEach(handle -> {
                    try {
                        handle.close();
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private BaseChatModel configuredDelegate() {
        BaseChatModel copy = delegate.copy();
        copy.setTools(getTools());
        copy.setTemperature(getTemperature());
        copy.setModelKwargs(getModelKwargs());
        return copy;
    }
    static Object appendMessages(Object input, List<String> additions) {
        if (additions.isEmpty()) {
            return input;
        }
        List<BaseMessage> messages = new ArrayList<>();
        if (input instanceof ChatPromptValue chat) {
            messages.addAll(chat.getMessages());
        }
        else {
            messages.add(HumanMessage.builder().content(input instanceof StringPromptValue text ? text.getText() : String.valueOf(input)).build());
        }
        additions.forEach(text -> messages.add(HumanMessage.builder().content(text).build()));
        return ChatPromptValue.builder().messages(messages).build();
    }

    @Override
    public void otherInformation(AiChatInput input) {
        delegate.otherInformation(input);
    }

    @Override
    public Class<? extends AiChatActuator> getActuator() {
        return delegate.getActuator();
    }

    @Override
    public BaseChatModel copy() {
        var copy = new InteractiveChatModel(configuredDelegate(), runner);
        copy.budgetState = budgetState;
        copy.budgetPhase = budgetPhase;
        return copy;
    }

    @Override
    public BaseChatModel withJsonMode() {
        var copy = new InteractiveChatModel(configuredDelegate().withJsonMode(), runner);
        copy.budgetState = budgetState;
        copy.budgetPhase = budgetPhase;
        return copy;
    }
}
