package io.github.lilaschuda.guanaco.core;

import org.apache.camel.spi.IdempotentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a delegate {@link IdempotentRepository} to guarantee a duplicate is
 * always logged, regardless of Camel's own internal component logging
 * configuration. This is a thin pass-through — all actual dedup logic,
 * storage, and lifecycle stay with the delegate; this class adds exactly
 * one thing: an unconditional, framework-owned log line whenever a message
 * is recognized as a duplicate.
 *
 * <p>Camel signals "this key already existed" via {@link #add} returning
 * {@code false} — that's the single point this wrapper hooks.
 *
 * <p>{@link #start()}/{@link #stop()} are forwarded to the delegate rather
 * than left as no-ops — {@code MemoryIdempotentRepository} doesn't need
 * either today, but a future pluggable-repository extension (e.g. a
 * JDBC- or Hazelcast-backed one) would, and this wrapper is meant to be
 * reusable for that without silently dropping lifecycle calls.
 *
 * <p>Not currently configurable — always wraps
 * {@code MemoryIdempotentRepository} in v1.0. The delegate is accepted as a
 * constructor parameter specifically so a future {@code repositoryRef}
 * extension (mirroring how {@code strategyRef} works for Aggregate) can
 * reuse this wrapper unchanged.
 */
public class LoggingIdempotentRepository implements IdempotentRepository {

    private static final Logger log = LoggerFactory.getLogger(LoggingIdempotentRepository.class);

    private final IdempotentRepository delegate;
    private final String processorName;
    private final String messageIdHeader;

    public LoggingIdempotentRepository(IdempotentRepository delegate, String processorName, String messageIdHeader) {
        this.delegate = delegate;
        this.processorName = processorName;
        this.messageIdHeader = messageIdHeader;
    }

    @Override
    public boolean add(String key) {
        boolean added = delegate.add(key);
        if (!added) {
            log.debug("[{}] Duplicate message skipped — {}='{}' was already seen.",
                    processorName, messageIdHeader, key);
        }
        return added;
    }

    @Override
    public boolean contains(String key) {
        return delegate.contains(key);
    }

    @Override
    public boolean remove(String key) {
        return delegate.remove(key);
    }

    @Override
    public boolean confirm(String key) {
        return delegate.confirm(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
    }
}