package com.github.xingjianluo.onchainriskgraphjava.controller.dto;

public record RuleRequest(Integer maxHops, Double decay, Double riskThreshold,
                          Long effectiveAt) {
}
