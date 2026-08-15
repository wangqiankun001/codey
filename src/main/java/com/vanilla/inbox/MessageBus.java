package com.vanilla.inbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanilla.Codey;
import com.vanilla.util.ConsoleRenderer;

public class MessageBus {

    public static final MessageBus INSTANCE = new MessageBus();

    public static final Path INBOX_DIR = Codey.CONFIG_DIR.resolve("inbox");

    private ObjectMapper mapper = new ObjectMapper();

    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss");

    public static MessageBus getInstance() {
        return INSTANCE;
    }

    public void send(String fromAgent, String toAgent, String content, AgentMsgType msgType) {
        var msg = new AgentMessage(fromAgent, toAgent, content, msgType, LocalDateTime.now().format(formatter));
        Path inbox = INBOX_DIR.resolve(toAgent + ".jsonl");
        try {
            Files.createDirectories(inbox.getParent());
            Files.writeString(inbox, mapper.writeValueAsString(msg) + System.lineSeparator(), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            ConsoleRenderer.getShared().printDebug("agent消息发送失败");
        }
    }

    public List<AgentMessage> readInbox(String agent) {
        var inbox = INBOX_DIR.resolve(agent + ".jsonl");
        try (var parser = mapper.getFactory().createParser(inbox.toFile())) {
            JavaType type = mapper.getTypeFactory().constructType(AgentMessage.class);
            MappingIterator<AgentMessage> inboxs = mapper.readValues(parser, type);
            // 先读取全部数据，try-with-resources 会在退出前自动关闭 parser 释放文件句柄
            List<AgentMessage> messages = inboxs.readAll();
            parser.close();
            Files.delete(inbox);
            return messages;
        } catch (IOException e) {
            return List.of();
        }
    }

    public boolean peek(String agent) {
        Path path = INBOX_DIR.resolve(agent + ".jsonl");
        return path.toFile().exists() && path.toFile().canRead();
    }
}
