package org.salt.regnexe.cli.db;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.message.HumanMessage;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class SqliteConversationStorageTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void collidingAndNumericSessionNamesStayIsolatedAcrossRestart() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("rex.db");
        List<String> sessions = List.of("Aa", "BB", "A", "65");
        try (RexDatabase db = new RexDatabase(path)) {
            SqliteConversationStorage storage = new SqliteConversationStorage(db);
            for (String session : sessions) {
                storage.append("0", "0", session, HistoryInfos.builder()
                        .messages(List.of(HumanMessage.builder().content("Question " + session).build(),
                                AIMessage.builder().content("Answer " + session).build())).build());
            }
        }
        try (RexDatabase db = new RexDatabase(path)) {
            SqliteConversationStorage storage = new SqliteConversationStorage(db);
            for (String session : sessions) {
                List<HistoryInfos> turns = storage.loadAll("0", "0", session);
                assertEquals(1, turns.size());
                assertEquals("Question " + session, turns.get(0).getMessages().get(0).getContent());
                assertEquals("Answer " + session, turns.get(0).getMessages().get(1).getContent());
            }
        }
    }
}
