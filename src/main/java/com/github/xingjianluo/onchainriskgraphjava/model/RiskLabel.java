package com.github.xingjianluo.onchainriskgraphjava.model;

/**
 * 地址风险标记：自 effectiveAt 起，该地址作为风险源，基础风险分为 baseScore。
 */
public record RiskLabel(String address, double baseScore, long effectiveAt) {
}
