package com.heartsync.controller;

import com.heartsync.service.PersonaService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {
    private final PersonaService personaService;

    public ConfigController(PersonaService personaService) {
        this.personaService = personaService;
    }

    @GetMapping("/persona")
    public Map<String, String> getPersona() {
        return Map.of("persona", personaService.loadSystemPrompt());
    }

    @PutMapping("/persona")
    public Map<String, String> updatePersona(@RequestBody Map<String, String> body) {
        // ponytail: 阶段 1 简单实现，直接改写 vault 文件
        String newPersona = body.get("persona");
        if (newPersona != null) {
            personaService.updatePersona(newPersona);
        }
        return Map.of("status", "ok");
    }
}
