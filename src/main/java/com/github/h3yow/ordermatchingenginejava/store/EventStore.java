package com.github.h3yow.ordermatchingenginejava.store;

import java.util.List;

/**
 * 事件流水存储：追加写 + 全量读，用于重启重放。
 * 生产实现为本地 JSON Lines 文件（每行一个 JSON 对象）。
 */
public interface EventStore {

    /** 追加一条事件（必须在事件生效前落盘，保证重启可见状态一致）。 */
    void append(Event event);

    /** 读取全部事件，按写入顺序返回（即重放顺序）。 */
    List<Event> readAll();
}
