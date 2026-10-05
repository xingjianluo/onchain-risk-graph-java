package com.github.xingjianluo.onchainriskgraphjava.ledger;

import org.springframework.stereotype.Component;

/**
 * 全局提交水位钟：为每一次“已完整落库”的写入分配单调递增的提交序号。
 *
 * <p>语义：
 * <ul>
 *   <li>写入方必须在 {@code synchronized (clock)} 临界区内完成
 *       “分配序号 + 真正写入存储”两个动作，保证序号与可见性原子绑定；</li>
 *   <li>读取方（批量历史回放）在批次开始时通过 {@link #current()} 固定一个水位
 *       （commit fence），整批只见 {@code seq <= fence} 的数据；
 *       回放进行期间新提交的写入序号必然大于 fence，被整批隔离；</li>
 *   <li>普通单点查询不携带水位（等价于 {@code Long.MAX_VALUE}），行为与之前完全一致。</li>
 * </ul>
 */
@Component
public class CommitClock {

    private long current;

    /**
     * 分配下一个提交序号。调用方必须持有本对象监视器，并在同一临界区内完成写入，
     * 使得“序号已分配但数据尚未落库”的中间态对水位读取者不可见。
     */
    public synchronized long next() {
        return ++current;
    }

    /** 当前提交水位：序号小于等于该值的写入都已完整提交、对读取者可见 */
    public synchronized long current() {
        return current;
    }
}
