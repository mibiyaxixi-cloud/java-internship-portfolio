package com.bookflow.admin;

import com.bookflow.common.BusinessException;
import java.util.Locale;
import java.util.Set;

public record DeviceInput(String code, String name, String category, String location,
                          String description, String status) {
    public DeviceInput {
        code = text(code, "设备编号", 40).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9][A-Z0-9-]{1,39}")) throw BusinessException.invalid("编号须为 2～40 位英文、数字或连字符");
        name = text(name, "设备名称", 100);
        category = text(category, "设备类别", 60);
        location = text(location, "设备位置", 100);
        description = description == null ? "" : description.trim();
        if (description.length() > 500) throw BusinessException.invalid("设备说明不能超过 500 字");
        if (status == null || !Set.of("ACTIVE", "INACTIVE").contains(status)) throw BusinessException.invalid("设备状态无效");
    }

    private static String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw BusinessException.invalid(field + "不能为空且不能超过 " + max + " 字");
        }
        return value.trim();
    }
}
