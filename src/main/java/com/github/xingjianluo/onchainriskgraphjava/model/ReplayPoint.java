package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * 批量历史回放中的一个回放点。
 *
 * @param address 目标地址；为 null/空白表示回放该时点的全部地址
 * @param asOf    快照时间点（必填，非负）
 */
public record ReplayPoint(String address, Long asOf) {
}
