package com.github.h3yow.ordermatchingenginejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.ordermatchingenginejava.config.TradingProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基于 JSON Lines 文件的事件存储：目录下 events.jsonl，每行一个事件 JSON。
 *
 * <p>语义：
 * <ul>
 *   <li>append 为同步追加并 flush，事件在业务状态生效前落盘，崩溃最多丢失“未落盘的请求”，
 *       不会出现“已扣款但无流水”；</li>
 *   <li>readAll 严格按行（写入）顺序返回，作为重放顺序；</li>
 *   <li>事件内容含完整 Order / Fill 对象，重放不依赖任何额外快照。</li>
 * </ul>
 */
@Component
public class FileEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(FileEventStore.class);
    private static final String FILE_NAME = "events.jsonl";

    private final ObjectMapper objectMapper;
    private final Path logFile;

    public FileEventStore(TradingProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.logFile = Path.of(properties.getEventLogDir(), FILE_NAME);
        try {
            Files.createDirectories(logFile.getParent());
            if (Files.notExists(logFile)) {
                Files.createFile(logFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("init event log failed: " + logFile, e);
        }
    }

    @Override
    public synchronized void append(Event event) {
        try {
            String line = objectMapper.writeValueAsString(event);
            Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("append event failed", e);
        }
    }

    @Override
    public synchronized List<Event> readAll() {
        List<Event> events = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                events.add(objectMapper.readValue(line, Event.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("read event log failed", e);
        }
        log.info("loaded {} events from {}", events.size(), logFile);
        return events;
    }
}
