package com.lakegov.web;

import com.lakegov.service.RestoreService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/restore")
public class RestoreController {

    private final RestoreService restore;

    public RestoreController(RestoreService restore) {
        this.restore = restore;
    }

    /** 创建恢复计划并执行启动前可满足性检查 */
    @PostMapping("/plans")
    public Map<String, Object> createPlan(@RequestBody Map<String, Object> body) {
        return restore.createPlan((String) body.get("targetUid"),
                (String) body.getOrDefault("strategy", "SCHEDULED_TIERING"),
                body.get("frequencyPerYear") == null
                        ? 1d : Double.valueOf(body.get("frequencyPerYear").toString()));
    }

    @GetMapping("/plans")
    public List<Map<String, Object>> plans() {
        return restore.listPlans();
    }

    /** 执行恢复计划（不可满足则阻断） */
    @PostMapping("/plans/{id}/execute")
    public Map<String, Object> execute(@PathVariable long id) {
        return restore.executePlan(id);
    }

    /** 恢复包校验 */
    @PostMapping("/packages/validate")
    public Map<String, Object> validatePackage(@RequestBody Map<String, String> body) {
        return restore.validatePackage(body.getOrDefault("packageUid", "PKG-RESULT-A01"));
    }

    /** 模拟缺件：设置目录可用性（故意移除校准文件用） */
    @PostMapping("/availability")
    public Map<String, Object> availability(@RequestBody Map<String, Object> body) {
        return restore.setAvailability((String) body.get("uid"),
                Boolean.TRUE.equals(body.get("available")));
    }

    /** 72h 窗口内按 DAG 的预取日程 */
    @GetMapping("/prefetch-schedule")
    public List<Map<String, Object>> prefetchSchedule(@RequestParam String targetUid) {
        return restore.prefetchSchedule(targetUid);
    }
}
