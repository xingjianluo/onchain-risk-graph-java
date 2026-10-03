package com.github.xingjianluo.onchainriskgraphjava.controller.dto;

import java.util.List;

/**
 * 批量历史回放请求。
 *
 * @param address     被回放的地址
 * @param asOfs       一组时间点，结果顺序与该列表一一对应
 * @param ruleVersion 可选：显式指定用某个已发布规则版本复现；为空按各时间点语义选取
 */
public record ReplayRequest(String address, List<Long> asOfs, Integer ruleVersion) {
}
