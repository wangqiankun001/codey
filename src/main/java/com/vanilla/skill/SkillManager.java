package com.vanilla.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** 扫描并读取项目中的 Skill。 */
public final class SkillManager {

    private static final Map<String, Skill> SKILLS = new HashMap<>();

    static {
        Path dir = Paths.get(System.getProperty("user.dir"), ".codey", "skills");
        if (Files.exists(dir)) {
            try {
                Files.list(dir).forEach(skillDir -> {
                    String skillName = skillDir.getFileName().toString();
                    Path skillMd = skillDir.resolve("SKILL.md");
                    String desc, fullContent;
                    try {
                        String data = Files.readString(skillMd);
                        fullContent = data;
                        desc = resolveDescription(data);
                    } catch (IOException e) {
                        desc = "skill 不符合标准，未获取到描述.";
                        fullContent = "未知";
                    }
                    SKILLS.put(skillName, new Skill(skillName, desc, fullContent));
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static String resolveDescription(String data) {
        String descriptionLine = data.lines().filter(line -> line.startsWith("description:"))
                .findFirst().orElse("未找到 skill 描述");
        return descriptionLine.split(":", 2)[1];
    }

    public static String skillIndex() {
        return SKILLS.entrySet().stream().map(entry -> {
            return entry.getKey() + ":\n" + entry.getValue().description();
        }).collect(Collectors.joining("\n"));
    }

    public static String getFullContent(String skillName) {
        Skill s = SKILLS.get(skillName);
        return s != null ? s.content() : "Not found skill :" + skillName;
    }
}
