package com.lakegov.web;

import com.lakegov.service.CostService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cost")
public class CostController {

    private final CostService cost;

    public CostController(CostService cost) {
        this.cost = cost;
    }

    /** 生成指定月（YYYY-MM）日账与月账单，含分摊核对 */
    @PostMapping("/billing/generate")
    public Map<String, Object> generate(@RequestBody Map<String, String> body) {
        return cost.generateMonthlyBilling(body.getOrDefault("billingMonth", "2026-11"));
    }

    @GetMapping("/billing")
    public List<Map<String, Object>> billing(@RequestParam(required = false) String billingMonth) {
        return cost.monthlyBills(billingMonth);
    }

    /**
     * 两路线对比与盈亏平衡频次。
     * frequencies 默认 [1, 4, 12]（年1次 / 季度 / 月1次）
     */
    @GetMapping("/strategy-comparison")
    public Map<String, Object> comparison(@RequestParam String targetUid,
                                          @RequestParam(required = false) List<Double> frequencies) {
        List<Double> f = (frequencies == null || frequencies.isEmpty())
                ? List.of(1d, 4d, 12d) : frequencies;
        return cost.strategyComparison(targetUid, f);
    }

    @GetMapping("/retrieval-events")
    public List<Map<String, Object>> retrievalEvents() {
        return cost.retrievalEvents();
    }
}
