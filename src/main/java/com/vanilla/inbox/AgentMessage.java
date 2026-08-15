package com.vanilla.inbox;

public record AgentMessage(
        String fromAgent,
        String toAgent,
        String content,
        AgentMsgType type,
        String ts) {

    public AgentMessage {
        if (fromAgent == null || fromAgent.isBlank()) {
            throw new IllegalArgumentException("fromAgent must not be blank");
        }
        if (toAgent == null || toAgent.isBlank()) {
            throw new IllegalArgumentException("toAgent must not be blank");
        }
        if (content == null) {
            content = "";
        }
        if (type == null) {
            type = AgentMsgType.MESSAGE;
        }
        if (ts == null) {
            ts = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }
}
