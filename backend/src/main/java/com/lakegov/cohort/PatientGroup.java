package com.lakegov.cohort;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 患者不可拆分组的紧凑特征表示。
 * 求解器只保留该对象（特征集合 + 扫描数 + 许可范围），不持有任何扫描行，
 * 因此流式路线的常驻内存与扫描总数 1600 解耦。
 */
public final class PatientGroup {

    public final String uid;
    public final int diseaseGrade;
    public final Set<String> devices;
    public final Set<String> protocols;
    /** 许可允许进入的分区编码集合（许可范围硬约束）。 */
    public final Set<String> allowedSplits;
    public final int scanCount;

    public PatientGroup(String uid,
                        int diseaseGrade,
                        List<String> devices,
                        List<String> protocols,
                        List<String> allowedSplits,
                        int scanCount) {
        this.uid = uid;
        this.diseaseGrade = diseaseGrade;
        this.devices = devices == null ? new LinkedHashSet<>() : new LinkedHashSet<>(devices);
        this.protocols = protocols == null ? new LinkedHashSet<>() : new LinkedHashSet<>(protocols);
        this.allowedSplits = allowedSplits == null || allowedSplits.isEmpty()
                ? new LinkedHashSet<>(List.of("TRAIN", "VAL"))
                : new LinkedHashSet<>(allowedSplits);
        this.scanCount = scanCount;
    }

    /** 是否属于指定覆盖维度取值。 */
    public boolean hasFeature(String dimension, String value) {
        return switch (dimension) {
            case "DISEASE_GRADE" -> String.valueOf(diseaseGrade).equals(value);
            case "DEVICE" -> devices.contains(value);
            case "PROTOCOL" -> protocols.contains(value);
            default -> false;
        };
    }
}
