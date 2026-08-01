package com.vanilla;

import com.vanilla.memory.MemoryManager;
import com.vanilla.skill.SkillManager;

public class SystemMessageBuilder {

    public static final String SYSTEM_PROMPT_TEMPLATE = """
            You are a coding agent at %s.
            OS is %s.
            Before starting any multi-step task, use todo_write to plan your steps.
            Update status as you go.


            Use load_skill to get full details of skills when needed.
            Skills avalibale:
            %s


            Relevant memories are injected below. Respect user preferences from memory.
            When the user says 'remember' or expresses a clear preference, extract it as a memory.
            Memories available:
            %s
            """;

    public static String buildSystemMessage() {
        return String.format(SYSTEM_PROMPT_TEMPLATE,
            System.getProperty("user.dir"),
            System.getProperty("os.name"),
            SkillManager.skillIndex(),
            MemoryManager.readMemoryIndex()
        );
    }
}
