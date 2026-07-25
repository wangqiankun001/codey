package com.vanilla.content;

import java.lang.String;

import com.vanilla.skill.SkillManager;

public interface Prompt {
    String SYSTEM = String.format("""
            You are a coding agent at %s.
            OS is %s
            Before starting any multi-step task, use todo_write to plan your steps.
            Update status as you go.
            Use load_skill to get full details of skills when needed.
            Skills avalibale:
            %s
            """,
            System.getProperty("user.dir"), System.getProperty("os.name"), SkillManager.listSkills());

}
