package com.rayneo.customai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe sliding window multi-turn conversation memory keyed by dialogId.
 */
public final class ConversationMemory {

    public static final class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static final Map<String, Deque<Message>> DIALOGS = new HashMap<>();

    private ConversationMemory() {}

    public static synchronized List<Message> getHistory(String dialogId, int maxTurns) {
        if (maxTurns <= 0) {
            return Collections.emptyList();
        }
        String key = (dialogId == null || dialogId.trim().isEmpty()) ? "default" : dialogId.trim();
        Deque<Message> deque = DIALOGS.get(key);
        if (deque == null || deque.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = maxTurns * 2;
        List<Message> all = new ArrayList<>(deque);
        if (all.size() <= limit) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    public static synchronized void appendTurn(String dialogId, String userText, String assistantText, int maxTurns) {
        if (maxTurns <= 0) return;
        String key = (dialogId == null || dialogId.trim().isEmpty()) ? "default" : dialogId.trim();
        Deque<Message> deque = DIALOGS.get(key);
        if (deque == null) {
            deque = new ArrayDeque<>();
            DIALOGS.put(key, deque);
        }
        if (userText != null && !userText.trim().isEmpty()) {
            deque.addLast(new Message("user", userText.trim()));
        }
        if (assistantText != null && !assistantText.trim().isEmpty()) {
            deque.addLast(new Message("assistant", assistantText.trim()));
        }
        int maxMessages = maxTurns * 2;
        while (deque.size() > maxMessages) {
            deque.removeFirst();
        }
    }

    public static synchronized void clearDialog(String dialogId) {
        if (dialogId == null) return;
        DIALOGS.remove(dialogId.trim());
    }

    public static synchronized void clearAll() {
        DIALOGS.clear();
    }
}
