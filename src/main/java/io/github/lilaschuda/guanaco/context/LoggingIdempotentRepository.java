package io.github.lilaschuda.guanaco.context;

import org.apache.camel.spi.IdempotentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a delegate {@link IdempotentRepository} to guarantee a duplicate is
 * always logged, regardless of Camel's internal logging configuration[cite: 37].
 */
class LoggingIdempotentRepository implements IdempotentRepository {

    private static final Logger log = LoggerFactory.getLogger(LoggingIdempotentRepository.class);

    private final IdempotentRepository delegate;
    private final String processorName;
    private final String messageIdHeader;

    /**
     * Constructs a logging wrapper around an idempotent repository[cite: 37].
     *
     * @param delegate the underlying repository implementation[cite: 37]
     * @param processorName the name of the processor using this repository[cite: 37]
     * @param messageIdHeader the message ID header key being checked[cite: 37]
     */
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