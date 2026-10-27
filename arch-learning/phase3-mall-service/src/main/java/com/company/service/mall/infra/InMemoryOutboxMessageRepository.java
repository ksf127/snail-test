package com.company.service.mall.infra;

import com.company.service.mall.domain.OutboxMessage;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryOutboxMessageRepository implements OutboxMessageRepository {

    private final ConcurrentMap<Long, OutboxMessage> messages = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, OutboxMessage> dedupeIndex = new ConcurrentHashMap<>();

    @Override
    public void save(OutboxMessage message) {
        messages.put(message.id(), message);
        dedupeIndex.putIfAbsent(message.dedupeKey(), message);
    }

    @Override
    public Optional<OutboxMessage> findById(long id) {
        return Optional.ofNullable(messages.get(id));
    }

    @Override
    public Optional<OutboxMessage> findByDedupeKey(String dedupeKey) {
        return Optional.ofNullable(dedupeIndex.get(dedupeKey));
    }

    @Override
    public List<OutboxMessage> findDue(Instant now, int limit) {
        return messages.values().stream()
                .filter(message -> message.status() == OutboxMessage.Status.PENDING)
                .filter(message -> !message.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(OutboxMessage::nextAttemptAt).thenComparingLong(OutboxMessage::id))
                .limit(limit)
                .toList();
    }

    @Override
    public List<OutboxMessage> findAll() {
        return messages.values().stream()
                .sorted(Comparator.comparingLong(OutboxMessage::id))
                .toList();
    }

    @Override
    public long countByStatus(OutboxMessage.Status status) {
        return messages.values().stream().filter(message -> message.status() == status).count();
    }

    @Override
    public long count() {
        return messages.size();
    }
}
