package com.xiaohei.tv.ui

/**
 * 切台预测：决定「下一个要预播谁」。
 *
 * 背景与取舍（实测数据支撑，不是拍脑袋）：
 * - 预播槽只有 1~2 个（每槽一路硬解，多开会闪退，见 [com.xiaohei.tv.DeviceProfile]）；
 * - 预播命中的切换只要 23ms，没命中就是 2~8 秒的冷启动——所以「预播谁」几乎等于「切台快不快」；
 * - 原来的顺序是「同方向下一台 → 同方向再下一台」。
 *
 * 但看电视最高频的动作不是连按同向，而是**广告时间切走、广告结束切回来**。
 * 按老顺序，用户切回来时槽里装的是「再往前一个台」，回切必然冷启动 4 秒起步。
 * 所以把「来时的那个台」提到第二位：
 * 连按同向的第 2 下会退到第三位，但那时第 1 次切台已经 swap 完成、流水线会重排，
 * 「再往前一个台」自然变成新的「同方向下一台」，损失有限；回切则每次都是白捡。
 *
 * 纯函数，下标运算全部对负数取模，JVM 单测可直接覆盖边界。
 */
object ZapPredict {

    /**
     * @param index  当前台在序列里的下标
     * @param count  序列长度
     * @param dir    最近一次切台方向（>0 往后，<0 往前）
     * @param slots  可用的预播槽数量
     * @return 按优先级排序的候选下标，不含 [index] 本身，长度不超过 [slots]
     */
    fun targets(index: Int, count: Int, dir: Int, slots: Int): List<Int> {
        if (count < 2 || slots <= 0) return emptyList()
        if (index !in 0 until count) return emptyList()
        val step = if (dir >= 0) 1 else -1
        // 顺序：同方向下一台 → 来时的上一台 → 同方向再下一台
        val offsets = intArrayOf(step, -step, 2 * step)
        val out = ArrayList<Int>(3)
        for (d in offsets) {
            if (out.size >= slots) break
            val t = ((index + d) % count + count) % count
            if (t == index || out.contains(t)) continue
            out.add(t)
        }
        return out
    }
}
