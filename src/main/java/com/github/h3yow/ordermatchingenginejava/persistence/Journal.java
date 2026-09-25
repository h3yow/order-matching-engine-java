package com.github.h3yow.ordermatchingenginejava.persistence;

import com.github.h3yow.ordermatchingenginejava.domain.Order;
import com.github.h3yow.ordermatchingenginejava.domain.Trade;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地追加式流水：订单受理/成交/撤单事件逐行落盘，重启后重放恢复状态。
 * 行格式：
 *   O|orderId|clientOrderId|accountId|side|type|price|qty|seq
 *   T|tradeId|buyOrderId|sellOrderId|buyAcct|sellAcct|price|qty|seq
 *   C|orderId
 */
@Component
public class Journal {

    private final Path path;

    public Journal(@Value("${journal.path:data/journal.log}") String path) {
        this.path = Path.of(path);
    }

    public synchronized void appendOrderAccepted(Order order) {
        append("O|" + order.getOrderId() + "|" + order.getClientOrderId() + "|"
                + order.getAccountId() + "|" + order.getSide() + "|" + order.getType() + "|"
                + (order.getPrice() == null ? "" : order.getPrice()) + "|"
                + order.getQuantity() + "|" + order.getSequence());
    }

    public synchronized void appendTrade(Trade trade) {
        append("T|" + trade.getTradeId() + "|" + trade.getBuyOrderId() + "|"
                + trade.getSellOrderId() + "|" + trade.getBuyAccountId() + "|"
                + trade.getSellAccountId() + "|" + trade.getPrice() + "|"
                + trade.getQuantity() + "|" + trade.getSequence());
    }

    public synchronized void appendCanceled(String orderId) {
        append("C|" + orderId);
    }

    /** 读取全部事件行（重放用）。文件不存在时返回空列表。 */
    public List<String> readAll() {
        try {
            if (!Files.exists(path)) {
                return List.of();
            }
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 清空流水（测试用）。 */
    public synchronized void reset() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void append(String line) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
