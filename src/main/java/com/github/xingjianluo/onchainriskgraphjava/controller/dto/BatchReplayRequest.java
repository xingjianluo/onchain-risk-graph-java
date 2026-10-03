package com.github.xingjianluo.onchainriskgraphjava.controller.dto;

import com.github.xingjianluo.onchainriskgraphjava.model.ReplayPoint;

import java.util.List;

/**
 * 批量历史回放请求。
 *
 * @param points 回放点位列表（非空、不超过上限）
 */
public record BatchReplayRequest(List<ReplayPoint> points) {
}
