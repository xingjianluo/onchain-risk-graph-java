package com.github.xingjianluo.onchainriskgraphjava.query;

import com.github.xingjianluo.onchainriskgraphjava.model.QueryRejection;
import com.github.xingjianluo.onchainriskgraphjava.model.RejectReason;

/**
 * 可解释拒绝的查询异常标记：携带稳定原因码与可机读拒绝结果。
 */
public interface RejectedQuery {

    RejectReason reason();

    QueryRejection rejection();
}
