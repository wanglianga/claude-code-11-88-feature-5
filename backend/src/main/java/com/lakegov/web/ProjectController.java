package com.lakegov.web;

import com.lakegov.service.ProjectService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return projects.listProjects();
    }

    @GetMapping("/{code}/review")
    public Map<String, Object> review(@PathVariable String code) {
        return projects.review(code);
    }

    /** 项目退出（共享对象保护在服务内执行） */
    @PostMapping("/{code}/withdraw")
    public Map<String, Object> withdraw(@PathVariable String code,
                                        @RequestBody(required = false) Map<String, String> body) {
        return projects.withdraw(code, body == null ? null : body.get("reason"));
    }

    @PostMapping("/{code}/reactivate")
    public Map<String, Object> reactivate(@PathVariable String code) {
        return projects.reactivate(code);
    }
}
