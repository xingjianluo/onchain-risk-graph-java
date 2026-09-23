package com.github.xingjianluo.onchainriskgraphjava.controller.dto;

public record TransactionRequest(String txId, String fromAddress, String toAddress,
                                 Long amount, Long visibleAt) {
}
