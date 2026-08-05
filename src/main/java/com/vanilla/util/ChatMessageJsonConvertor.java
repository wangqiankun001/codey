package com.vanilla.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

public class ChatMessageJsonConvertor {

    public static final ChatMessageJsonConvertor INSTANCE = new ChatMessageJsonConvertor();

    private static final ObjectMapper om = new ObjectMapper();

    private ChatMessageJsonConvertor() {
    }

    public String convert(ChatMessage chatMessage) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", chatMessage.type().name());
        if (chatMessage instanceof UserMessage um) {
            data.put("content", extractInfoFromContents(um.contents()));
        } else if (chatMessage instanceof SystemMessage sm) {
            data.put("content", sm.text());
        } else if (chatMessage instanceof AiMessage am) {
            if (StrUtil.isNotBlank(am.text())) {
                data.put("text", am.text());
            }
            if (am.hasToolExecutionRequests()) {
                List<ToolExeRequest> toolExecRequests = am.toolExecutionRequests().stream().map(toolReq -> {
                    return new ToolExeRequest(toolReq.name(), toolReq.arguments());
                }).toList();
                data.put("toolExecutionRequests", toolExecRequests);
            }
        } else if (chatMessage instanceof ToolExecutionResultMessage rm) {
            data.put("toolName", rm.toolName());
            data.put("content", extractInfoFromContents(rm.contents()));
        } else {
            throw new RuntimeException("暂不支持的类型：" + chatMessage.type().name());
        }
        try {
            return om.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<String> extractInfoFromContents(List<Content> contents) {
        List<String> textContent = contents.stream().map(content -> {
            if (content instanceof TextContent tc) {
                return tc.text();
            }
            return String.format("<媒体文件类型:[%s]>", content.type().name());
        }).toList();
        return textContent;
    }

    public static record ToolExeRequest(String name, String arguments) {
    }

}
